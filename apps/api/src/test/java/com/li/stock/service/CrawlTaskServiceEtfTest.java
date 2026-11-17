package com.li.stock.service;

import com.li.stock.common.BizException;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.crawl.CrawlCoveragePlanner.Coverage;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.domain.vo.StockInfoVO;
import com.li.stock.integration.BaoStockMemorySocket;
import com.li.stock.mapper.StockDailyMapper;
import com.li.stock.repository.StockDailyRepository;
import com.li.stock.repository.CrawlCheckpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ETF 采集回归测试。只调用任务的纯参数/单证券处理路径，真实外部 Socket 与数据库都被内存替身隔离；
 * 不创建 Spring 上下文，避免启动清理逻辑触碰正在运行的真实采集任务。
 */
class CrawlTaskServiceEtfTest {
    private static final LocalDate FROM = LocalDate.of(2023, 11, 17);
    private static final LocalDate TO = LocalDate.of(2026, 11, 16);
    private final List<List<StockDailyVO>> savedBatches = new ArrayList<>();
    private final List<String> savedTables = new ArrayList<>();
    private final StockDailyRepository daily = new StockDailyRepository(recordingMapper());
    private final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
    private final CrawlTaskService service = new CrawlTaskService(null, null, daily, null, checkpoints);

    @AfterEach
    void shutdownUnusedExecutor() {
        service.shutdown();
    }

    @Test
    void filtersEtfsByExplicitShanghaiIdentityAndKeepsStockClassification() throws Exception {
        @SuppressWarnings("unchecked")
        List<StockInfoVO> result = (List<StockInfoVO>) invoke("filterStocks", List.of(
                source("sh.588000"), source("sz.588000"), source("588001"),
                source("sh.588000"), source("sh.510300"), source("sz.300001"), source("sh.600000")),
                List.of("58", "30", "60"));

        assertEquals(List.of("588000", "300001", "600000"), result.stream().map(StockInfoVO::code).toList());
        StockInfoVO etf = result.getFirst();
        assertEquals("SH", etf.exchange());
        assertEquals(1, etf.market());
        assertEquals("ETF", etf.board());
        assertEquals("sh.588000", etf.secid());
        assertEquals("创业板", result.get(1).board());
        assertEquals("主板", result.get(2).board());
    }

    @Test
    void explicitDateWindowOverridesRelativeDaysAndInvalidRequestsDoNotLockTheService() throws Exception {
        Object config = invoke("normalize", request("58", 1, true, FROM, TO));
        assertEquals(FROM, component(config, "from"));
        assertEquals(TO, component(config, "to"));

        Object oldRequestConfig = invoke("normalize", request("00", 1, true, null, null));
        assertEquals(LocalDate.now(), component(oldRequestConfig, "to"));
        assertEquals(LocalDate.now().minusDays(10), component(oldRequestConfig, "from"));

        assertThrows(BizException.class, () -> service.start(request("58", 0, false, TO, FROM)));
        assertThrows(BizException.class, () -> service.start(request("58", 0, false, FROM, LocalDate.now().plusDays(1))));
        assertThrows(BizException.class, () -> service.start(request("51", 0, false, FROM, TO)));
        Field running = CrawlTaskService.class.getDeclaredField("running");
        running.setAccessible(true);
        assertFalse(((AtomicBoolean) running.get(service)).get(), "参数校验失败不能占用后台任务锁");
    }

    @Test
    void mixedRequestsUseStockFlagsTwoAndThreeButOnlyOneRawEtfRequest() throws Exception {
        Object config = invoke("normalize", request("00,58", 1, true, FROM, TO));
        StockInfoVO stock = security("000001", "SZ", "主板");
        StockInfoVO etf = security("588000", "SH", "ETF");
        assertEquals(List.of(2, 3), invoke("adjustFlagsFor", stock, config));
        assertEquals(List.of(3), invoke("adjustFlagsFor", etf, config));

        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        var client = socket.client();
        crawl(client, stock, config);
        crawl(client, etf, config);

        assertEquals(List.of(2, 3, 3), socket.queries().stream().map(BaoStockMemorySocket.Query::adjustFlag).toList());
        assertEquals(List.of("stock_daily_00", "stock_daily_00", "stock_daily_58"), savedTables);
        assertEquals(1, savedBatches.getFirst().size(), "每个复权口径独立原子提交，不能等下一口径成功才保存");
        StockDailyVO etfRow = savedBatches.get(2).getFirst();
        assertEquals(3, etfRow.adjustFlag());
        assertEquals(1, savedBatches.getFirst().getFirst().isSt(), "股票仍保留上游 ST 字段");
        assertNull(etfRow.isSt());
        assertNull(etfRow.peTtm());
        assertNull(etfRow.pbMrq());
        assertNull(etfRow.psTtm());
        assertNull(etfRow.pcfNcfTtm());
    }

    @Test
    void etfConversionDropsStockOnlyFieldsEvenIfUpstreamUnexpectedlyIncludesThem() throws Exception {
        Map<String, String> raw = BaoStockMemorySocket.sourceValues(new BaoStockMemorySocket.Query(
                "sh.588000", List.of(), FROM, TO, 2));
        @SuppressWarnings("unchecked")
        List<StockDailyVO> rows = (List<StockDailyVO>) invoke("toDailyRows", List.of(raw), "588000", "SH", 2);
        StockDailyVO row = rows.getFirst();
        assertEquals(3, row.adjustFlag(), "实际源标记优先，不能用请求 fallback 伪造前复权");
        assertNull(row.isSt());
        assertNull(row.peTtm());
        assertNull(row.pbMrq());
        assertNull(row.psTtm());
        assertNull(row.pcfNcfTtm());
        assertEquals(123400L, row.volume());
    }

    @Test
    void backfillRespectsBothExplicitBoundsWhenExistingDataFallsOutsideTheWindow() throws Exception {
        Object config = invoke("normalize", request("58", 0, false, FROM, TO));
        StockInfoVO etf = security("588000", "SH", "ETF");
        BaoStockMemorySocket later = new BaoStockMemorySocket();
        checkpoints.existing.put("588000#3", Set.of(TO.plusDays(10), TO.plusDays(20)));
        crawl(later.client(), etf, config);
        assertEquals(FROM, later.queries().getFirst().from());
        assertEquals(TO, later.queries().getFirst().to(), "历史前段补齐不能越过显式 to");

        BaoStockMemorySocket earlier = new BaoStockMemorySocket();
        checkpoints.completed.clear();
        checkpoints.existing.put("588000#3", Set.of(FROM.minusDays(20), FROM.minusDays(10)));
        crawl(earlier.client(), etf, config);
        assertEquals(FROM, earlier.queries().getFirst().from(), "增量后段补齐不能早于显式 from");
        assertEquals(TO, earlier.queries().getFirst().to());
    }

    @Test
    void existingRawEtfCoverageSkipsNetworkRegardlessOfRequestedStockFlag() throws Exception {
        Object config = invoke("normalize", request("58", 1, true, FROM, TO));
        StockInfoVO etf = security("588000", "SH", "ETF");
        checkpoints.existing.put("588000#3", Set.of(FROM, TO));
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        crawl(socket.client(), etf, config);
        assertTrue(socket.queries().isEmpty());
        assertTrue(savedBatches.isEmpty());
    }

    @Test
    void failureInSecondFlagPreservesFirstFlagButSameDayResumeRechecksAdjustedWindow() throws Exception {
        Object config = invoke("normalize", request("00", 1, true, FROM, TO));
        StockInfoVO stock = security("000001", "SZ", "主板");
        checkpoints.failFlag = 3;
        BaoStockMemorySocket first = new BaoStockMemorySocket();
        assertThrows(IllegalStateException.class, () -> crawl(first.client(), stock, config));
        assertEquals(List.of(2), savedBatches.stream().flatMap(List::stream).map(StockDailyVO::adjustFlag).toList());
        checkpoints.failFlag = -1;
        BaoStockMemorySocket resumed = new BaoStockMemorySocket();
        invoke("crawlOneStock", resumed.client(), 2L, stock, config, Set.of(FROM, TO), LocalDate.now());
        assertEquals(List.of(2, 3), resumed.queries().stream().map(BaoStockMemorySocket.Query::adjustFlag).toList(),
                "恢复任务编号变化后，不能因日期相同而跳过上一轮前复权行情");
        assertEquals(FROM, resumed.queries().getFirst().from());
        assertEquals(TO, resumed.queries().getFirst().to(), "新轮次必须重新确认整个前复权请求窗口");
    }

    @Test
    void rawSuccessfulCoverageIncludingCertifiedEmptyDatesRemainsReusableAcrossAttempts() throws Exception {
        Object config = invoke("normalize", request("58", 0, false, FROM, TO));
        StockInfoVO etf = security("588000", "SH", "ETF");
        BaoStockMemorySocket first = new BaoStockMemorySocket();
        crawl(first.client(), etf, config);
        // 内存源只返回FROM，TO由完整响应确认空结果；换新任务后两种证据都仍可复用。
        assertEquals(1, first.queries().size());
        BaoStockMemorySocket nextAttempt = new BaoStockMemorySocket();
        invoke("crawlOneStock", nextAttempt.client(), 2L, etf, config, Set.of(FROM, TO), LocalDate.now());
        assertTrue(nextAttempt.queries().isEmpty());
        assertEquals(1, savedBatches.size());
    }

    @Test
    void pauseIsObservedAfterTheSuccessfulFlagWasPersisted() throws Exception {
        Object config = invoke("normalize", request("00", 1, true, FROM, TO));
        checkpoints.pauseAfterSave = true;
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        assertThrows(RuntimeException.class, () -> crawl(socket.client(), security("000001", "SZ", "主板"), config));
        assertEquals(List.of(2), socket.queries().stream().map(BaoStockMemorySocket.Query::adjustFlag).toList());
        assertEquals(1, savedBatches.size(), "暂停不能丢弃已收到的成功响应");
        assertEquals(1, checkpoints.completed.size());
    }

    @Test
    void normalizedParametersRoundTripWithoutRecomputingRelativeDates() throws Exception {
        Object config = invoke("normalize", request("00,58", 2, true, FROM, TO));
        String json = (String) invoke("configJson", config);
        Object restored = invoke("configFromJson", json);
        assertEquals(FROM, component(restored, "from"));
        assertEquals(TO, component(restored, "to"));
        assertEquals(List.of(2, 0), component(restored, "fqTypes"));
        assertThrows(IllegalStateException.class, () -> invoke("configFromJson", "{\"schemaVersion\":2}"));
    }

    private Object crawl(com.li.stock.integration.BaoStockClient client, StockInfoVO stock, Object config) throws Exception {
        return invoke("crawlOneStock", client, 1L, stock, config, Set.of(FROM, TO), LocalDate.now());
    }

    /** 只替代断点存储；日线仍通过真实仓储路由到记录型 Mapper，不触碰数据库。 */
    private final class MemoryCheckpoints extends CrawlCheckpointRepository {
        private final Map<String, Set<LocalDate>> existing = new HashMap<>();
        private final Map<String, List<Coverage>> completed = new HashMap<>();
        private int failFlag = -1;
        private boolean pauseAfterSave;
        private boolean paused;

        private MemoryCheckpoints() {
            super(null, new org.springframework.transaction.PlatformTransactionManager() {
                public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }
                public void commit(org.springframework.transaction.TransactionStatus status) {}
                public void rollback(org.springframework.transaction.TransactionStatus status) {}
            }, CrawlTaskServiceEtfTest.this.daily, null);
        }

        @Override public boolean pauseRequested(long taskId) { return paused; }
        @Override public Set<LocalDate> existingDates(String code, String market, int flag, Range range) {
            return existing.getOrDefault(code + "#" + flag, Set.of());
        }
        @Override public List<Coverage> coverage(String code, String market, int flag, String basis, Range range) {
            return completed.getOrDefault(code + "#" + flag + "#" + basis, List.of());
        }
        @Override public int saveCompleted(long taskId, String code, String market, int flag, String basis, Range range, List<StockDailyVO> rows) {
            if (flag == failFlag) throw new IllegalStateException("模拟第二口径提交失败");
            int count = CrawlTaskServiceEtfTest.this.daily.saveCrawlerBatch(rows);
            Set<LocalDate> returned = new HashSet<>();
            rows.forEach(row -> returned.add(row.tradeDate()));
            Set<LocalDate> updated = new HashSet<>(existing.getOrDefault(code + "#" + flag, Set.of()));
            updated.addAll(returned);
            existing.put(code + "#" + flag, updated);
            completed.computeIfAbsent(code + "#" + flag + "#" + basis, ignored -> new ArrayList<>()).add(new Coverage(range, returned));
            if (pauseAfterSave) paused = true;
            return count;
        }
    }

    /** 只替代 SQL 执行，不替代生产仓储的分表路由和数据组装逻辑。 */
    private StockDailyMapper recordingMapper() {
        return (StockDailyMapper) Proxy.newProxyInstance(StockDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockDailyMapper.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("saveCrawlerBatch")) throw new UnsupportedOperationException(method.getName());
                    @SuppressWarnings("unchecked")
                    List<StockDailyVO> rows = (List<StockDailyVO>) args[1];
                    savedTables.add((String) args[0]);
                    savedBatches.add(List.copyOf(rows));
                    return rows.size();
                });
    }

    /** 直接验证纯计算私有路径，避免为测试引入生产公共 API 或创建真实后台任务。 */
    private Object invoke(String name, Object... args) throws Exception {
        Method method = Arrays.stream(CrawlTaskService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }

    private Object component(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private CrawlTaskRequest request(String prefixes, int fqType, boolean raw, LocalDate from, LocalDate to) {
        return new CrawlTaskRequest(0, 10, fqType, raw, prefixes, 0, true, from, to);
    }

    private Map<String, String> source(String code) {
        return Map.of("code", code, "code_name", "测试证券");
    }

    private StockInfoVO security(String code, String exchange, String board) {
        return new StockInfoVO(code, "测试证券", exchange.equals("SH") ? 1 : 0,
                exchange.toLowerCase() + "." + code, board, exchange,
                null, null, null, null, null, null, null, null);
    }
}
