package com.li.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.common.BizException;
import com.li.stock.common.MarketCacheWarmer;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.CrawlerDateRangeVO;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.integration.BaoStockClient;
import com.li.stock.repository.CrawlTaskRepository;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.StockInfoRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 页面触发的 BaoStock 日线增量抓取任务。
 *
 * <p>流程与 Python data/main.py 保持一致：先取股票列表，再按代码前缀筛选，
 * 按证券和复权口径查询数据库日期范围，只请求缺失的历史前段或最新后段；
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "stock-crawler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CrawlTaskService(CrawlTaskRepository taskRepository,
                            StockInfoRepository stockInfoRepository,
                            StockDailyRepository stockDailyRepository,
                            MarketCacheWarmer marketCacheWarmer) {
        this.taskRepository = taskRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.stockDailyRepository = stockDailyRepository;
        this.marketCacheWarmer = marketCacheWarmer;
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
    }

    public CrawlTaskVO start(CrawlTaskRequest request) {
        // 参数校验必须先于占用运行标记；非法日期或前缀不能让后续合法任务永久显示“正在运行”。
        // 同时在提交时固定日期范围，避免排队或跨午夜后重新按“今天”计算窗口。
        CrawlConfig config = normalize(request);
        if (!running.compareAndSet(false, true)) {
            throw BizException.badRequest("已有数据抓取任务正在运行，请先等待当前任务完成");
        }
        CrawlTask task = new CrawlTask();
        task.setTaskName("daily_kline");
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setStartTime(LocalDateTime.now());
        task.setStatus("running");
        task.setRemark("等待连接 BaoStock");
        try {
            taskRepository.create(task);
            executor.submit(() -> execute(task.getId(), config));
            return taskRepository.find(task.getId());
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
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
        try (BaoStockClient client = new BaoStockClient(objectMapper, 10000, 60000)) {
            LocalDate requestedEnd = config.to();
            boolean loggedIn = false;
            List<StockInfoVO> stocks = config.refreshStockList()
                    ? List.of()
                    : stockInfoRepository.crawlerStocks(config.prefixes(), config.limit());
            boolean localPoolSatisfiesRequest = !stocks.isEmpty()
                    && (config.limit() == 0 || stocks.size() >= config.limit());
            if (!localPoolSatisfiesRequest) {
                client.login();
                loggedIn = true;
                List<Map<String, String>> sourceStocks = queryLatestStockList(client, requestedEnd);
                stocks = filterStocks(sourceStocks, config.prefixes());
                if (config.limit() > 0 && stocks.size() > config.limit()) {
                    stocks = new ArrayList<>(stocks.subList(0, config.limit()));
                }
                stockInfoRepository.upsertCrawlerStocks(stocks);
            }
            if (stocks.isEmpty()) throw new IllegalStateException("BaoStock 没有返回符合条件的股票列表");

            taskRepository.updateProgress(taskId, stocks.size(), 0, 0,
                    (localPoolSatisfiesRequest ? "复用本地股票池" : "同步 BaoStock 股票列表")
                            + "，共 " + stocks.size() + " 只，开始增量抓取日线");

            LocalDate initialStart = config.from();
            // 请求的 fqType 与数据库/BaoStock 的 adjustflag 编号不同，必须转换后再读取既有范围。
            // 例如 fqType=1/0 分别对应前复权 adjustflag=2 与不复权 adjustflag=3。
            Map<String, CrawlerDateRangeVO> dateRanges = stockDailyRepository.crawlerDateRanges(
                    stocks.stream().map(StockInfoVO::code).toList(),
                    stocks.stream().flatMap(stock -> adjustFlagsFor(stock, config).stream()).distinct().toList());
            boolean needsSync = stocks.stream()
                    .anyMatch(stock -> needsHistory(stock, requestedEnd, initialStart, config, dateRanges));
            if (!loggedIn && needsSync) {
                client.login();
            }
            // 以最近交易日为同步截止日：已入库当日数据的股票不再请求；周末/节假日整体空转也只花一次日历查询。
            LocalDate syncEnd = needsSync ? lastTradeDay(client, requestedEnd) : requestedEnd;
            for (int index = 0; index < stocks.size(); index++) {
                StockInfoVO stock = stocks.get(index);
                try {
                    CrawlResult result = crawlOneStock(client, stock, initialStart, syncEnd, config, dateRanges);
                    success++;
                    String action = result.requestCount() == 0
                            ? "已是最新，跳过 BaoStock 请求"
                            : "写入 " + result.savedRows() + " 条";
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "："
                                    + stock.code() + "，" + action, false);
                    // 已经是最新的数据不再等待，重复执行时可以快速完成。
                    if (result.requestCount() > 0 && index < stocks.size() - 1) {
                        sleep(config.delayMillis());
                    }
                } catch (Exception ex) {
                    fail++;
                    String message = stock.code() + "：" + rootMessage(ex);
                    errors.add(message);
                    updateProgressIfNeeded(taskId, stocks.size(), index, success, fail,
                            "已完成 " + (index + 1) + "/" + stocks.size() + "，最近失败：" + message, true);
                }
            }
            String remark = "抓取完成，成功 " + success + " 只，失败 " + fail + " 只";
            if (!errors.isEmpty()) remark += "；" + String.join("；", errors);
            taskRepository.finish(taskId, "done", success, fail, limitText(remark));
        } catch (Exception ex) {
            taskRepository.finish(taskId, "failed", success, fail,
                    limitText("任务失败：" + rootMessage(ex)));
        } finally {
            running.set(false);
            // 抓取写入了新行情，后台刷新市场看板缓存，把新增数据加载进缓存。
            marketCacheWarmer.refreshAsync();
        }
    }

    private boolean needsHistory(StockInfoVO stock, LocalDate requestedEnd, LocalDate initialStart,
                                 CrawlConfig config, Map<String, CrawlerDateRangeVO> dateRanges) {
        return adjustFlagsFor(stock, config).stream().anyMatch(adjustFlag -> {
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            return range == null || range.firstDate().isAfter(initialStart)
                    || range.latestDate().isBefore(requestedEnd);
        });
    }

    private CrawlResult crawlOneStock(BaoStockClient client, StockInfoVO stock, LocalDate initialStart,
                                      LocalDate syncEnd, CrawlConfig config,
                                      Map<String, CrawlerDateRangeVO> dateRanges) {
        List<StockDailyVO> rows = new ArrayList<>();
        int requestCount = 0;
        for (int adjustFlag : adjustFlagsFor(stock, config)) {
            CrawlerDateRangeVO range = dateRanges.get(stock.code() + "#" + adjustFlag);
            if (range == null) {
                rows.addAll(queryHistory(client, stock, initialStart, syncEnd, adjustFlag));
                requestCount++;
                continue;
            }
            if (range.firstDate().isAfter(initialStart)) {
                // 已有记录可能全部落在本次指定区间之后，补历史时仍必须受显式截止日约束。
                LocalDate historyEnd = range.firstDate().minusDays(1);
                rows.addAll(queryHistory(client, stock, initialStart,
                        historyEnd.isBefore(syncEnd) ? historyEnd : syncEnd, adjustFlag));
                requestCount++;
            }
            // 最新后段只在数据库还没有最近交易日的数据时才补，已同步的股票不发请求。
            if (range.latestDate().isBefore(syncEnd)) {
                // 同理，已有记录早于整个请求区间时，不向前扩张用户指定的起始日。
                LocalDate historyStart = range.latestDate().plusDays(1);
                rows.addAll(queryHistory(client, stock, historyStart.isAfter(initialStart) ? historyStart : initialStart,
                        syncEnd, adjustFlag));
                requestCount++;
            }
        }
        return new CrawlResult(stockDailyRepository.saveCrawlerBatch(rows), requestCount);
    }

    /**
     * 最近一个交易日（不超过今天）。交易日历查询失败时退化为今天，
     * 此时行为与旧逻辑一致：按自然日尝试增量，非交易日会拿到空结果。
     */
    private LocalDate lastTradeDay(BaoStockClient client, LocalDate today) {
        try {
            List<LocalDate> days = client.queryTradeDates(today.minusDays(31), today);
            if (!days.isEmpty()) return days.get(days.size() - 1);
        } catch (RuntimeException ex) {
            log.warn("查询 BaoStock 交易日历失败，按今天作为同步截止日：{}", rootMessage(ex));
        }
        return today;
    }

    private List<StockDailyVO> queryHistory(BaoStockClient client, StockInfoVO stock,
                                            LocalDate from, LocalDate to, int adjustFlag) {
        if (from.isAfter(to)) return List.of();
        return toDailyRows(client.queryHistory(stock.secid(), from, to, String.valueOf(adjustFlag)),
                stock.code(), stock.exchange(), adjustFlag);
    }

    /** 每 20 只股票更新一次进度，失败和最后一只立即更新，减少无效数据库写入。 */
    private void updateProgressIfNeeded(long taskId, int total, int index, int success, int fail,
                                        String remark, boolean immediate) {
        if (immediate || index == total - 1 || (index + 1) % 20 == 0) {
            taskRepository.updateProgress(taskId, total, success, fail, remark);
        }
    }

    private List<Map<String, String>> queryLatestStockList(BaoStockClient client, LocalDate today) {
        RuntimeException last = null;
        for (int offset = 0; offset < 40; offset++) {
            try {
                List<Map<String, String>> rows = client.queryAllStock(today.minusDays(offset));
                if (!rows.isEmpty()) return rows;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("最近 40 天均未获取到 BaoStock 股票列表"
                + (last == null ? "" : "：" + rootMessage(last)));
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
