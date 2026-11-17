package com.li.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.li.stock.common.BizException;
import com.li.stock.common.MarketCacheWarmer;
import com.li.stock.crawl.CrawlAdjustmentRevision;
import com.li.stock.crawl.CrawlCheckpointVO;
import com.li.stock.crawl.CrawlCoveragePlanner;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.crawl.CrawlJobCheckpoint;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.integration.BaoStockClient;
import com.li.stock.integration.BaoStockAccessGate;
import com.li.stock.repository.CrawlCheckpointRepository;
import com.li.stock.repository.CrawlTaskRepository;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 页面触发的 BaoStock 日线增量抓取任务。
 *
 * <p>流程与 Python data/main.py 保持一致：先取股票列表，再按代码前缀筛选，
 * 固定证券池与日期参数，按精确日期和持久成功覆盖请求缺口，支持内部缺口及安全暂停/继续；
 * 00、30、60 股票与 58 ETF 分别写入独立分表，ETF 不进入既有个股模型的数据视图。
 * 任务在后台执行，前端通过 crawl_log 轮询进度，不会让 HTTP 请求长时间阻塞。</p>
 */
@Service
public class CrawlTaskService {
    private static final Logger log = LoggerFactory.getLogger(CrawlTaskService.class);
    private static final int DEFAULT_DAYS_BACK = 548;
    private static final int MAX_DAYS_BACK = 3650;
    private static final int DEFAULT_DELAY_MILLIS = 1500;
    private static final int MAX_DELAY_MILLIS = 10000;
    private static final int MAX_STOCK_LIMIT = 5000;

    private final CrawlTaskRepository taskRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockDailyRepository stockDailyRepository;
    private final MarketCacheWarmer marketCacheWarmer;
    private final CrawlCheckpointRepository checkpoints;
    private final Supplier<BaoStockClient> clientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stock-crawler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public CrawlTaskService(CrawlTaskRepository taskRepository,
                            StockInfoRepository stockInfoRepository,
                            StockDailyRepository stockDailyRepository,
                            MarketCacheWarmer marketCacheWarmer,
                            CrawlCheckpointRepository checkpoints) {
        this(taskRepository, stockInfoRepository, stockDailyRepository, marketCacheWarmer, checkpoints,
                () -> new BaoStockClient(new ObjectMapper(), 10000, 60000));
    }

    /** 包内测试可注入纯内存传输层；生产始终由上面的受管构造器提供真实客户端。 */
    CrawlTaskService(CrawlTaskRepository taskRepository, StockInfoRepository stockInfoRepository,
                     StockDailyRepository stockDailyRepository, MarketCacheWarmer marketCacheWarmer,
                     CrawlCheckpointRepository checkpoints, Supplier<BaoStockClient> clientFactory) {
        this.taskRepository = taskRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
        this.marketCacheWarmer = marketCacheWarmer;
        this.checkpoints = checkpoints;
        this.clientFactory = clientFactory;
    }

    /**
     * 抓取任务只在内存线程中执行，应用重启后数据库里会遗留永远"running"的僵尸任务。
     * 启动时统一标记为失败，避免前端误以为还有任务在跑。
     */
    @PostConstruct
    public void interruptStaleTasksOnStartup() {
        try {
            int count = taskRepository.interruptStaleRunning();
            if (count > 0) {
                log.info("已将 {} 个上次进程遗留的抓取任务标记为中断", count);
            }
        } catch (RuntimeException ex) {
            // 启动清理失败不影响应用正常运行
            log.warn("清理遗留抓取任务失败：{}", ex.getMessage());
        }
        try {
            checkpoints.interruptRunningOnStartup();
        } catch (RuntimeException ex) {
            // 独立迁移尚未安装时仍允许查看旧数据；新建可续采任务会明确失败，不能静默绕开断点。
            log.warn("采集断点初始化检查失败，请确认已显式安装 crawl_checkpoint_schema.sql：{}", ex.getMessage());
        }
    }

    public CrawlTaskVO start(CrawlTaskRequest request) {
        // 参数校验必须先于占用运行标记；非法日期或前缀不能让后续合法任务永久显示“正在运行”。
        // 同时在提交时固定日期范围，避免排队或跨午夜后重新按“今天”计算窗口。
        CrawlConfig config = normalize(request);
        if (!running.compareAndSet(false, true)) {
            throw BizException.badRequest("已有数据抓取任务正在运行，请先等待当前任务完成");
        }
        CrawlTask task = newTask("等待检查持久断点与 BaoStock 会话");
        boolean persisted = false;
        try {
            checkpoints.create(task, configJson(config), LocalDate.now());
            persisted = true;
            executor.submit(() -> execute(task.getId(), config));
            return taskRepository.find(task.getId());
        } catch (RuntimeException ex) {
            running.set(false);
            if (persisted) checkpoints.finish(task.getId(), "failed", "failed", 0, 0, "后台执行器未接受任务，可从持久断点继续");
            throw ex;
        }
    }

    /** 只提交暂停意图，不打断正在接收的响应或数据库事务。 */
    public CrawlTaskVO pause(long taskId) {
        CrawlTaskVO task = find(taskId);
        CrawlJobCheckpoint job = checkpoints.find(taskId);
        if (job == null) throw BizException.badRequest("旧任务没有持久断点，不能安全暂停；请等待当前采集结束");
        if (job.latestTaskId() != taskId) throw BizException.badRequest("请在最新一轮任务上请求暂停");
        if ("paused".equals(job.status())) return task;
        if (!checkpoints.requestPause(taskId)) throw BizException.badRequest("任务已不在运行，请刷新任务状态");
        // 此处不改 crawl_log.status；工作线程在安全边界观察到请求后才写 paused。
        return find(taskId);
    }

    /** 按原固定参数与证券池创建新一轮任务；旧任务日志与成功覆盖不删除、不改写。 */
    public CrawlTaskVO resume(long taskId) {
        CrawlJobCheckpoint job = checkpoints.find(taskId);
        if (job == null) throw BizException.badRequest("旧任务未保存固定参数，请用原日期和参数重新提交；已有精确行情仍可续采");
        if (!resumable(job.status())) throw BizException.badRequest("任务正在运行或已经完整完成，不能重复继续");
        CrawlConfig config = configFromJson(job.configJson());
        if (!running.compareAndSet(false, true)) throw BizException.badRequest("已有数据抓取任务正在运行，请先等待当前任务完成");
        CrawlTask task = newTask("从任务 " + job.latestTaskId() + " 的持久断点继续");
        boolean persisted = false;
        try {
            checkpoints.resume(job, task, LocalDate.now());
            persisted = true;
            executor.submit(() -> execute(task.getId(), config));
            return taskRepository.find(task.getId());
        } catch (RuntimeException ex) {
            running.set(false);
            if (persisted) checkpoints.finish(task.getId(), "failed", "failed", 0, 0, "后台执行器未接受继续任务，可再次从断点继续");
            throw ex;
        }
    }

    /** 旧任务没有快照时返回 null，前端可以明确展示不可自动恢复而不是猜测其原始参数。 */
    public CrawlCheckpointVO checkpoint(long taskId) {
        CrawlJobCheckpoint job = checkpoints.find(taskId);
        if (job == null) return null;
        CrawlConfig config = configFromJson(job.configJson());
        return new CrawlCheckpointVO(job.rootTaskId(), job.latestTaskId(), job.status(), job.pauseRequested(),
                resumable(job.status()), config.from(), config.to(), String.join(",", config.prefixes()),
                config.fqTypes(), config.delayMillis(), securitiesFromJson(job.securitiesJson()).size(),
                CrawlAdjustmentRevision.POLICY, job.observedOn(), job.lastCode(), job.lastAdjustFlag(),
                job.lastFrom(), job.lastTo(), job.updatedAt());
    }

    private static boolean resumable(String status) {
        return Set.of("paused", "partial", "failed", "interrupted").contains(status);
    }

    private CrawlTask newTask(String remark) {
        CrawlTask task = new CrawlTask();
        task.setTaskName("daily_kline");
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setStartTime(LocalDateTime.now());
        task.setStatus("running");
        task.setRemark(remark);
        return task;
    }

    public CrawlTaskVO find(long id) {
        CrawlTaskVO task = taskRepository.find(id);
        if (task == null) throw BizException.notFound("抓取任务不存在：" + id);
        return task;
    }

    public List<CrawlTaskVO> recent(Integer limit) {
        int actualLimit = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
        return taskRepository.recent(actualLimit);
    }

    private void execute(long taskId, CrawlConfig config) {
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();
        // 先持有共享租约，再创建/使用客户端；关闭顺序为客户端登出、最后释放租约。
        try (var lease = BaoStockAccessGate.acquire(); BaoStockClient client = clientFactory.get()) {
            LocalDate requestedEnd = config.to();
            checkControl(taskId);
            CrawlJobCheckpoint job = checkpoints.find(taskId);
            if (job == null) throw new IllegalStateException("固定采集参数丢失，停止执行");
            List<StockInfoVO> stocks = securitiesFromJson(job.securitiesJson());
            boolean restored = !stocks.isEmpty();
            if (!restored) stocks = config.refreshStockList() ? List.of()
                    : stockInfoRepository.crawlerStocks(config.prefixes(), config.limit());
            boolean localPoolSatisfiesRequest = !stocks.isEmpty()
                    && (config.limit() == 0 || stocks.size() >= config.limit());
            if (!restored && !localPoolSatisfiesRequest) {
                // query* 会在首次请求时建立会话；不要另外发起第二次登录。
                List<Map<String, String>> sourceStocks = queryLatestStockList(client, requestedEnd, taskId);
                stocks = filterStocks(sourceStocks, config.prefixes());
                if (config.limit() > 0 && stocks.size() > config.limit()) {
                    stocks = new ArrayList<>(stocks.subList(0, config.limit()));
                }
                stockInfoRepository.upsertCrawlerStocks(stocks);
            }
            if (stocks.isEmpty()) throw new IllegalStateException("BaoStock 没有返回符合条件的股票列表");
            if (!restored) {
                String fixedSecurities = securitiesJson(stocks);
                // 首轮也走与恢复相同的身份校验，不能把错误交易所写成之后永远复用的任务快照。
                stocks = securitiesFromJson(fixedSecurities);
                checkpoints.saveSecurities(taskId, fixedSecurities);
            }

            taskRepository.updateProgress(taskId, stocks.size(), 0, 0,
                    (restored ? "复用固定任务股票池" : localPoolSatisfiesRequest ? "复用本地股票池" : "同步 BaoStock 股票列表")
                            + "，共 " + stocks.size() + " 只，开始增量抓取日线");

            Range requested = new Range(config.from(), config.to());
            Set<LocalDate> tradingDates = loadCalendar(client, taskId, requested);
            for (int index = 0; index < stocks.size(); index++) {
                checkControl(taskId);
                StockInfoVO stock = stocks.get(index);
                CrawlResult result;
                try {
                    result = crawlOneStock(client, taskId, stock, config, tradingDates, job.observedOn());
                } catch (Exception ex) {
                    if (ex instanceof PauseRequested || ex instanceof ObservationChanged || Thread.currentThread().isInterrupted()) throw ex;
                    fail++;
                    String message = stock.code() + "：" + rootMessage(ex);
                    errors.add(message);
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "，最近失败：" + message, true);
                    // 严格校验、非瞬时业务错误可能立即失败；失败证券也遵守逐证券间隔，
                    // 避免服务端限频时以零等待连续冲击整个股票池。中断仍直接退出，不继续发请求。
                    if (index < stocks.size() - 1) sleep(config.delayMillis());
                    continue;
                }
                success++;
                String action = result.requestCount() == 0
                        ? "已是最新，跳过 BaoStock 请求"
                        : "写入 " + result.savedRows() + " 条";
                // 单证券提交与进度持久化是不同边界。已成功证券不能因进度写入异常再计为失败，
                // 进度异常由外层终止本轮并标记failed，保留准确计数及已经原子提交的数据/覆盖。
                updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                        "已完成 " + (index + 1) + "/" + stocks.size() + "："
                                + stock.code() + "，" + action, false);
                // 已经是最新的数据不再等待，重复执行时可以快速完成。
                if (result.requestCount() > 0 && index < stocks.size() - 1) {
                    sleep(config.delayMillis());
                }
            }
            checkControl(taskId);
            String remark = "抓取完成，成功 " + success + " 只，失败 " + fail + " 只";
            if (!errors.isEmpty()) remark += "；" + String.join("；", errors);
            checkpoints.finish(taskId, fail == 0 ? "done" : "partial", "done", success, fail, limitText(remark));
        } catch (PauseRequested ex) {
            checkpoints.finish(taskId, "paused", "paused", success, fail,
                    "已在成功区间提交后安全暂停；继续时使用原固定参数和证券池");
        } catch (Exception ex) {
            checkpoints.finish(taskId, Thread.currentThread().isInterrupted() ? "interrupted" : "failed", "failed", success, fail,
                    limitText("任务失败：" + rootMessage(ex)));
        } finally {
            running.set(false);
            // 抓取写入了新行情，后台刷新市场看板缓存，把新增数据加载进缓存。
            if (marketCacheWarmer != null) marketCacheWarmer.refreshAsync();
        }
    }

    private CrawlResult crawlOneStock(BaoStockClient client, long taskId, StockInfoVO stock,
                                      CrawlConfig config, Set<LocalDate> tradingDates, LocalDate observedOn) {
        Range requested = new Range(config.from(), config.to());
        int saved = 0;
        int requestCount = 0;
        for (int adjustFlag : adjustFlagsFor(stock, config)) {
            checkControl(taskId);
            ensureObservationDay(adjustFlag, observedOn);
            // 同日恢复也可能遇到新的上游复权锚点；当前任务编号只是本轮确认标记，
            // 绝不把 observedOn 当成可跨轮复用的因子版本。原始行情仍使用稳定版本。
            String basis = CrawlAdjustmentRevision.key(adjustFlag, taskId);
            List<Range> missing = CrawlCoveragePlanner.missing(requested, tradingDates,
                    checkpoints.existingDates(stock.code(), stock.exchange(), adjustFlag, requested),
                    CrawlAdjustmentRevision.trustsLegacyRows(adjustFlag),
                    checkpoints.coverage(stock.code(), stock.exchange(), adjustFlag, basis, requested));
            for (Range range : missing) {
                checkControl(taskId);
                List<StockDailyVO> rows = queryHistory(client, stock, range.from(), range.to(), adjustFlag);
                ensureObservationDay(adjustFlag, observedOn);
                if (range.to().equals(LocalDate.now()) && tradingDates.contains(range.to())
                        && rows.stream().noneMatch(row -> row.tradeDate().equals(range.to()))) {
                    // 当日尚未更新/证券暂未返回行，不等于已经确认永久无行情。历史成功段仍可提交，
                    // 但当天不能封存为空覆盖，否则收盘前一次查询会让后续增量永远跳过该日。
                    if (range.from().isBefore(range.to())) {
                        Range completedHistory = new Range(range.from(), range.to().minusDays(1));
                        checkpoints.saveCompleted(taskId, stock.code(), stock.exchange(), adjustFlag, basis, completedHistory, rows);
                    }
                    throw new IllegalStateException("当天交易日尚无证券行情，已保留成功历史段；请待上游更新后继续");
                }
                // 网络成功后先原子提交；暂停意图只能在提交完成后生效，不能丢弃已完成口径。
                saved += checkpoints.saveCompleted(taskId, stock.code(), stock.exchange(), adjustFlag, basis, range, rows);
                requestCount++;
                checkControl(taskId);
            }
        }
        return new CrawlResult(saved, requestCount);
    }

    /**
     * 日历按不超过366个自然日的区间获取，避免旧接口单页2000条导致十年窗口被截断。
     * 查询失败时停止本轮，不以已有行情日期猜测交易日，也不把未知日期判为非交易日。
     */
    private Set<LocalDate> loadCalendar(BaoStockClient client, long taskId, Range requested) {
        Map<LocalDate, Boolean> calendar = checkpoints.calendar(requested);
        for (LocalDate from = requested.from(); !from.isAfter(requested.to()); from = from.plusDays(366)) {
            LocalDate to = from.plusDays(365).isAfter(requested.to()) ? requested.to() : from.plusDays(365);
            boolean complete = true;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (!calendar.containsKey(day)) { complete = false; break; }
            }
            if (complete) continue;
            checkControl(taskId);
            Map<LocalDate, Boolean> returned = client.queryTradeCalendar(from, to);
            Range segment = new Range(from, to);
            checkpoints.saveCalendar(segment, returned);
            calendar.putAll(returned);
        }
        Set<LocalDate> trading = new HashSet<>();
        calendar.forEach((day, valid) -> { if (Boolean.TRUE.equals(valid)) trading.add(day); });
        return trading;
    }

    private void checkControl(long taskId) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("采集线程被中断，保留已提交断点");
        if (checkpoints.pauseRequested(taskId)) throw new PauseRequested();
    }

    private void ensureObservationDay(int flag, LocalDate observedOn) {
        if (flag != 3 && !LocalDate.now().equals(observedOn)) {
            throw new ObservationChanged();
        }
    }

    /** 内部安全暂停信号与线程中断严格分开，不能把 shutdown/异常报告成用户暂停成功。 */
    private static final class PauseRequested extends RuntimeException {}

    private static final class ObservationChanged extends RuntimeException {
        private ObservationChanged() {
            super("复权观察日已变化；请继续任务以重新确认完整历史基准，未盲目复用旧覆盖");
        }
    }

    private List<StockDailyVO> queryHistory(BaoStockClient client, StockInfoVO stock,
                                            LocalDate from, LocalDate to, int adjustFlag) {
        if (from.isAfter(to)) return List.of();
        return toDailyRows(client.queryHistoryVerified(stock.secid(), from, to, String.valueOf(adjustFlag)),
                stock.code(), stock.exchange(), adjustFlag);
    }

    /** 每 20 只股票更新一次进度，失败和最后一只立即更新，减少无效数据库写入。 */
    private void updateProgressIfNeeded(long taskId, int total, int index, int success, int fail,
                                        String remark, boolean immediate) {
        if (immediate || index == total - 1 || (index + 1) % 20 == 0) {
            taskRepository.updateProgress(taskId, total, success, fail, remark);
        }
    }

    /**
     * 仅在上游严格成功确认“该日名单为空”时回溯日期，最多检查40个自然日。
     * 客户端已拥有每页共享重试预算；传输耗尽、限频/权限业务错误或响应完整性错误必须立即上抛，
     * 不能再通过更换日期重复40次，更不能把这些错误当作市场休市或合法空名单。
     */
    private List<Map<String, String>> queryLatestStockList(BaoStockClient client, LocalDate today, long taskId) {
        for (int offset = 0; offset < 40; offset++) {
            checkControl(taskId);
            List<Map<String, String>> rows = client.queryAllStockVerified(today.minusDays(offset));
            if (!rows.isEmpty()) return rows;
        }
        throw new IllegalStateException("最近 40 天均已确认 BaoStock 股票列表为空");
    }

    private List<StockInfoVO> filterStocks(List<Map<String, String>> sourceRows, List<String> prefixes) {
        Map<String, StockInfoVO> unique = new LinkedHashMap<>();
        for (Map<String, String> source : sourceRows) {
            String rawCode = text(source, "code");
            // BaoStock 证券标识必须明确包含交易所和六位代码，不能把缺失市场的基金默认归为深市。
            if (!rawCode.matches("(?i)(sh|sz)\\.[0-9]{6}")) continue;
            String code = rawCode.contains(".") ? rawCode.substring(rawCode.indexOf('.') + 1) : rawCode;
            String exchange = rawCode.toLowerCase().startsWith("sh.") ? "SH" : "SZ";
            if (code.isBlank() || !code.chars().allMatch(Character::isDigit)
                    || prefixes.stream().noneMatch(code::startsWith)) continue;
            if ((code.startsWith("60") || code.startsWith("58")) && !"SH".equals(exchange)) continue;
            if ((code.startsWith("00") || code.startsWith("30")) && !"SZ".equals(exchange)) continue;
            int market = "SH".equals(exchange) ? 1 : 0;
            String board = code.startsWith("58") ? "ETF" : code.startsWith("30") ? "创业板" : "主板";
            unique.put(code + "|" + exchange, new StockInfoVO(
                    code, text(source, "code_name"), market, exchange.toLowerCase() + "." + code,
                    board, exchange, null, null, null, null, null, null, null, null));
        }
        return new ArrayList<>(unique.values());
    }

    private List<StockDailyVO> toDailyRows(List<Map<String, String>> sourceRows, String code,
                                           String market, int fallbackAdjustFlag) {
        List<StockDailyVO> rows = new ArrayList<>();
        // ETF 没有个股的 ST/市盈率等含义；即使上游意外携带这些字段也不写入伪造估值。
        boolean etf = code.startsWith("58");
        for (Map<String, String> source : sourceRows) {
            BigDecimal close = decimal(source, "close");
            BigDecimal preclose = decimal(source, "preclose");
            BigDecimal high = decimal(source, "high");
            BigDecimal low = decimal(source, "low");
            BigDecimal amplitude = high != null && low != null && preclose != null && preclose.signum() != 0
                    ? high.subtract(low).divide(preclose, 8, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)) : null;
            BigDecimal chgAmt = close != null && preclose != null ? close.subtract(preclose) : null;
            rows.add(new StockDailyVO(
                    code, market, LocalDate.parse(text(source, "date")), decimal(source, "open"), close,
                    high, low, preclose, longValue(source, "volume"), decimal(source, "amount"), amplitude,
                    decimal(source, "pctChg"), chgAmt, decimal(source, "turn"),
                    integerValue(source, "adjustflag", fallbackAdjustFlag), integerValue(source, "tradestatus", null),
                    etf ? null : integerValue(source, "isST", null),
                    etf ? null : decimal(source, "peTTM"), etf ? null : decimal(source, "pbMRQ"),
                    etf ? null : decimal(source, "psTTM"), etf ? null : decimal(source, "pcfNcfTTM"), "baostock"));
        }
        return rows;
    }

    private CrawlConfig normalize(CrawlTaskRequest request) {
        int limit = request == null || request.limit() == null ? 0 : request.limit();
        if (limit < 0 || limit > MAX_STOCK_LIMIT) throw BizException.badRequest("limit 必须在 0 到 5000 之间");
        int daysBack = request == null || request.daysBack() == null ? DEFAULT_DAYS_BACK : request.daysBack();
        if (daysBack < 1 || daysBack > MAX_DAYS_BACK) throw BizException.badRequest("daysBack 必须在 1 到 3650 之间");
        LocalDate submittedOn = LocalDate.now();
        LocalDate to = request == null || request.to() == null ? submittedOn : request.to();
        LocalDate from = request == null || request.from() == null ? to.minusDays(daysBack) : request.from();
        if (to.isAfter(submittedOn)) throw BizException.badRequest("to 不能晚于任务提交当天");
        if (from.isAfter(to)) throw BizException.badRequest("from 不能晚于 to");
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS_BACK) {
            throw BizException.badRequest("采集日期跨度不能超过 3650 个自然日");
        }
        int fqType = request == null || request.fqType() == null ? 1 : request.fqType();
        if (fqType < 0 || fqType > 2) throw BizException.badRequest("fqType 必须是 0、1 或 2");
        int delay = request == null || request.requestDelayMillis() == null
                ? DEFAULT_DELAY_MILLIS : request.requestDelayMillis();
        if (delay < 0 || delay > MAX_DELAY_MILLIS) throw BizException.badRequest("请求间隔必须在 0 到 10000 毫秒之间");
        boolean refreshStockList = request != null && Boolean.TRUE.equals(request.refreshStockList());
        boolean raw = request == null || request.storeRawPrices() == null || request.storeRawPrices();
        String prefixText = request == null ? null : request.codePrefixes();
        List<String> prefixes = Arrays.stream((prefixText == null || prefixText.isBlank() ? "00" : prefixText).split(","))
                .map(String::trim).filter(s -> s.matches("\\d{1,6}")).distinct().toList();
        if (prefixes.isEmpty()) throw BizException.badRequest("codePrefixes 至少填写一个数字前缀，例如 00,30,60");
        if (prefixes.stream().anyMatch(prefix -> !(prefix.startsWith("00")
                || prefix.startsWith("30") || prefix.startsWith("60") || prefix.startsWith("58")))) {
            throw BizException.badRequest("当前分表只支持 00、30、60 股票及 58 ETF 前缀");
        }
        List<Integer> fqTypes = new ArrayList<>();
        fqTypes.add(fqType);
        if (raw && fqType != 0) fqTypes.add(0);
        return new CrawlConfig(limit, fqTypes, prefixes, delay, refreshStockList, from, to);
    }

    private int adjustFlagOf(int fqType) {
        return switch (fqType) {
            case 0 -> 3; // 不复权
            case 1 -> 2; // 前复权
            case 2 -> 1; // 后复权
            default -> throw new IllegalArgumentException("不支持的复权类型：" + fqType);
        };
    }

    /**
     * 按证券类型确定真实可采口径，而不是为所有证券套用同一份股票复权参数。
     * 实测 BaoStock 的 58 ETF 即使请求前复权也返回 adjustflag=3，故只请求一次不复权，
     * 不复制行情伪造前复权记录；混合任务中的股票仍完整保留原有 fqType/storeRawPrices 语义。
     */
    private List<Integer> adjustFlagsFor(StockInfoVO stock, CrawlConfig config) {
        return stock.code().startsWith("58") ? List.of(3)
                : config.fqTypes().stream().map(this::adjustFlagOf).distinct().toList();
    }

    /** 只持久化已规范化的固定日期和实际参数，不保存今天相对偏移、数据库账号或连接配置。 */
    private String configJson(CrawlConfig config) {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("schemaVersion", 1);
        saved.put("limit", config.limit());
        saved.put("fqTypes", config.fqTypes());
        saved.put("prefixes", config.prefixes());
        saved.put("delayMillis", config.delayMillis());
        saved.put("refreshStockList", config.refreshStockList());
        saved.put("from", config.from().toString());
        saved.put("to", config.to().toString());
        try { return objectMapper.writeValueAsString(saved); }
        catch (Exception ex) { throw new IllegalStateException("无法保存固定采集参数", ex); }
    }

    /** 参数版本不兼容或快照损坏时明确拒绝恢复，禁止悄悄使用当天默认参数扩大采集范围。 */
    private CrawlConfig configFromJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.path("schemaVersion").asInt() != 1) throw new IllegalArgumentException("不支持的采集参数版本");
            List<Integer> flags = new ArrayList<>();
            for (JsonNode item : node.path("fqTypes")) flags.add(item.asInt(-1));
            List<String> prefixes = new ArrayList<>();
            for (JsonNode item : node.path("prefixes")) prefixes.add(item.asText());
            LocalDate from = LocalDate.parse(node.path("from").asText());
            LocalDate to = LocalDate.parse(node.path("to").asText());
            int limit = node.path("limit").asInt(-1), delay = node.path("delayMillis").asInt(-1);
            if (flags.isEmpty() || flags.stream().anyMatch(flag -> flag < 0 || flag > 2)
                    || prefixes.isEmpty() || prefixes.stream().anyMatch(prefix -> !prefix.matches("(?:00|30|60|58)[0-9]{0,4}"))
                    || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS_BACK
                    || limit < 0 || limit > MAX_STOCK_LIMIT || delay < 0 || delay > MAX_DELAY_MILLIS) {
                throw new IllegalArgumentException("采集参数快照的日期、前缀或数值无效");
            }
            return new CrawlConfig(limit, List.copyOf(flags), List.copyOf(prefixes), delay,
                    node.path("refreshStockList").asBoolean(), from, to);
        } catch (Exception ex) { throw new IllegalStateException("固定采集参数损坏，未启动新的网络请求", ex); }
    }

    /** 名称仅用于原任务显示；续跑范围由持久化代码/市场确定，不重新使用当前全市场名单。 */
    private String securitiesJson(List<StockInfoVO> stocks) {
        List<Map<String, Object>> saved = new ArrayList<>();
        for (StockInfoVO stock : stocks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", stock.code()); item.put("name", stock.name()); item.put("market", stock.market());
            item.put("secid", stock.secid()); item.put("board", stock.board()); item.put("exchange", stock.exchange());
            saved.add(item);
        }
        try { return objectMapper.writeValueAsString(saved); }
        catch (Exception ex) { throw new IllegalStateException("无法保存固定证券池", ex); }
    }

    private List<StockInfoVO> securitiesFromJson(String value) {
        if (value == null) return List.of();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray()) throw new IllegalArgumentException("证券池快照不是数组");
            List<StockInfoVO> stocks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : node) {
                String code = item.path("code").asText(), exchange = item.path("exchange").asText();
                String secid = item.path("secid").asText();
                String expectedExchange = code.startsWith("60") || code.startsWith("58") ? "SH" : "SZ";
                if (!code.matches("(?:00|30|60|58)[0-9]{4}") || !Set.of("SH", "SZ").contains(exchange)
                        || !expectedExchange.equals(exchange)
                        || !secid.equals(exchange.toLowerCase(java.util.Locale.ROOT) + "." + code)
                        || !seen.add(secid)) throw new IllegalArgumentException("证券池快照含无效或重复证券");
                stocks.add(new StockInfoVO(code, item.path("name").asText(), "SH".equals(exchange) ? 1 : 0,
                        secid, item.path("board").asText(), exchange, null, null, null, null, null, null, null, null));
            }
            return List.copyOf(stocks);
        } catch (Exception ex) { throw new IllegalStateException("固定证券池损坏，未扩展或猜测任务范围", ex); }
    }

    private String text(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    private BigDecimal decimal(Map<String, String> row, String key) {
        String value = text(row, key);
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(Map<String, String> row, String key) {
        BigDecimal value = decimal(row, key);
        return value == null ? null : value.longValue();
    }

    private Integer integerValue(Map<String, String> row, String key, Integer fallback) {
        String value = text(row, key);
        if (value.isBlank()) return fallback;
        try {
            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void sleep(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抓取任务被中断", ex);
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String limitText(String text) {
        return text.length() <= 60000 ? text : text.substring(0, 60000);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record CrawlConfig(int limit, List<Integer> fqTypes, List<String> prefixes,
                               int delayMillis, boolean refreshStockList, LocalDate from, LocalDate to) {}

    private record CrawlResult(int savedRows, int requestCount) {}
}
