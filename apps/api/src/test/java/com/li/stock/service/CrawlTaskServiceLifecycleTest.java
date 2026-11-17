package com.li.stock.service;

import com.li.stock.crawl.CrawlCoveragePlanner.Coverage;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.crawl.CrawlJobCheckpoint;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.integration.BaoStockMemorySocket;
import com.li.stock.mapper.CrawlTaskMapper;
import com.li.stock.repository.CrawlCheckpointRepository;
import com.li.stock.repository.CrawlTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 后台任务生命周期回归：使用内存 Socket、内存日志和断点，不创建数据库连接或额外 BaoStock 登录。 */
class CrawlTaskServiceLifecycleTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);

    @Test
    void pauseStaysRunningUntilSafeBoundaryAndResumeKeepsOriginalParametersAndUniverse() throws Exception {
        CrawlTaskRepository tasks = memoryTasks();
        MemoryCheckpoints checkpoints = new MemoryCheckpoints(tasks);
        CountDownLatch beforeClient = new CountDownLatch(1), releaseClient = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        List<BaoStockMemorySocket> sockets = new ArrayList<>();
        CrawlTaskService service = new CrawlTaskService(tasks, null, null, null, checkpoints, () -> {
            try {
                if (factoryCalls.incrementAndGet() == 1) {
                    beforeClient.countDown();
                    if (!releaseClient.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("测试没有释放客户端边界");
                }
                BaoStockMemorySocket socket = new BaoStockMemorySocket(); sockets.add(socket); return socket.client();
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        try {
            CrawlTaskVO first = service.start(new CrawlTaskRequest(0, 10, 0, false,
                    "58", 0, true, DAY, DAY));
            assertTrue(beforeClient.await(2, TimeUnit.SECONDS));
            assertEquals("running", service.pause(first.id()).status(), "暂停请求本身不能提前报告已暂停");
            assertTrue(service.checkpoint(first.id()).pauseRequested());
            releaseClient.countDown();
            awaitExecutor(service);
            assertEquals("paused", service.find(first.id()).status());
            assertTrue(sockets.getFirst().queries().isEmpty());

            String originalConfig = checkpoints.config;
            CrawlTaskVO resumed = service.resume(first.id());
            awaitExecutor(service);
            assertNotEquals(first.id(), resumed.id());
            assertEquals("paused", service.find(first.id()).status(), "继续不能改写上一轮日志");
            assertEquals("done", service.find(resumed.id()).status());
            assertEquals(first.id().longValue(), service.checkpoint(resumed.id()).rootTaskId());
            assertEquals(resumed.id().longValue(), service.checkpoint(first.id()).latestTaskId());
            assertEquals(originalConfig, checkpoints.config);
            assertEquals(DAY, service.checkpoint(resumed.id()).from());
            assertEquals(DAY, service.checkpoint(resumed.id()).to());
            assertEquals(1, service.checkpoint(resumed.id()).securityCount());
            assertFalse(service.checkpoint(resumed.id()).resumable());
            assertEquals("sh.588000", sockets.getLast().queries().getFirst().secid());
            assertEquals(1, checkpoints.savedRows);
        } finally {
            releaseClient.countDown();
            service.shutdown();
        }
    }

    @Test
    void oneSecurityFailureKeepsPartialCheckpointAndDoneLogWithNonzeroFailureCount() throws Exception {
        CrawlTaskRepository tasks = memoryTasks();
        MemoryCheckpoints checkpoints = new MemoryCheckpoints(tasks);
        // 第一只模拟事务写入失败，第二只继续成功；必须保留兼容日志语义，同时让断点明确可续采。
        checkpoints.securities = "[{\"code\":\"588000\",\"name\":\"失败ETF\",\"market\":1,"
                + "\"secid\":\"sh.588000\",\"board\":\"ETF\",\"exchange\":\"SH\"},"
                + "{\"code\":\"588001\",\"name\":\"成功ETF\",\"market\":1,"
                + "\"secid\":\"sh.588001\",\"board\":\"ETF\",\"exchange\":\"SH\"}]";
        checkpoints.failCode = "588000";
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        CrawlTaskService service = new CrawlTaskService(tasks, null, null, null, checkpoints, () -> {
            try { return socket.client(); } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        });
        try {
            CrawlTaskVO started = service.start(new CrawlTaskRequest(0, 10, 0, false,
                    "58", 0, true, DAY, DAY));
            awaitExecutor(service);
            CrawlTaskVO finished = service.find(started.id());
            assertEquals("done", finished.status(), "旧任务日志中的 done 表示本轮遍历结束，不能丢失兼容语义");
            assertEquals(2, finished.totalCount());
            assertEquals(1, finished.successCount());
            assertEquals(1, finished.failCount(), "前端和流水线必须以失败计数识别部分失败");
            assertEquals("partial", service.checkpoint(started.id()).status());
            assertTrue(service.checkpoint(started.id()).resumable());
            assertEquals(1, checkpoints.savedRows, "失败证券不能生成成功覆盖，后续成功证券仍应提交");
            assertEquals(2, socket.queries().size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void progressWriteFailureStopsAttemptWithoutCountingCommittedSecurityTwice() throws Exception {
        CrawlTaskRepository tasks = memoryTasks(true);
        MemoryCheckpoints checkpoints = new MemoryCheckpoints(tasks);
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        CrawlTaskService service = new CrawlTaskService(tasks, null, null, null, checkpoints, () -> {
            try { return socket.client(); } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        });
        try {
            CrawlTaskVO started = service.start(new CrawlTaskRequest(0, 10, 0, false,
                    "58", 0, true, DAY, DAY));
            awaitExecutor(service);
            CrawlTaskVO finished = service.find(started.id());
            assertEquals("failed", finished.status(), "进度持久化失败不能当成行情局部失败后继续报告done");
            assertEquals(1, finished.totalCount());
            assertEquals(1, finished.successCount(), "行情及覆盖已提交的证券仍保留成功计数");
            assertEquals(0, finished.failCount(), "同一证券不能因后续进度更新失败被重复计入失败");
            assertEquals("failed", service.checkpoint(started.id()).status());
            assertTrue(service.checkpoint(started.id()).resumable());
            assertEquals(1, checkpoints.savedRows);
        } finally {
            service.shutdown();
        }
    }

    /** 任务 Mapper 的纯内存实现；保留仓储调用语义，不连接数据库或启动 Spring。 */
    private CrawlTaskRepository memoryTasks() {
        return memoryTasks(false);
    }

    /** 可单独模拟“行情已提交后写进度失败”，与行情事务失败分开验证状态及计数。 */
    private CrawlTaskRepository memoryTasks(boolean failProgressAfterSuccess) {
        Map<Long, CrawlTask> logs = new HashMap<>();
        AtomicInteger nextId = new AtomicInteger();
        CrawlTaskMapper mapper = (CrawlTaskMapper) Proxy.newProxyInstance(CrawlTaskMapper.class.getClassLoader(),
                new Class<?>[]{CrawlTaskMapper.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "create" -> {
                            CrawlTask task = (CrawlTask) args[0]; task.setId((long) nextId.incrementAndGet());
                            logs.put(task.getId(), task); return 1;
                        }
                        case "find" -> { return view(logs.get((long) args[0])); }
                        case "updateProgress" -> {
                            if (failProgressAfterSuccess && (int) args[2] > 0) {
                                throw new IllegalStateException("模拟行情成功后的进度写入失败");
                            }
                            CrawlTask task = logs.get((long) args[0]);
                            task.setTotalCount((int) args[1]); task.setSuccessCount((int) args[2]);
                            task.setFailCount((int) args[3]); task.setRemark((String) args[4]); return 1;
                        }
                        case "finish" -> {
                            CrawlTask task = logs.get((long) args[0]);
                            task.setStatus((String) args[1]); task.setSuccessCount((int) args[2]);
                            task.setFailCount((int) args[3]); task.setRemark((String) args[4]);
                            task.setEndTime(LocalDateTime.now()); return 1;
                        }
                        default -> throw new AssertionError("非预期任务SQL：" + method.getName());
                    }
                });
        return new CrawlTaskRepository(mapper);
    }

    /** 单线程执行器屏障：等待前一轮 finally 完成，避免用 sleep 猜测任务结束时机。 */
    private void awaitExecutor(CrawlTaskService service) throws Exception {
        Field field = CrawlTaskService.class.getDeclaredField("executor"); field.setAccessible(true);
        ((ExecutorService) field.get(service)).submit(() -> {}).get(3, TimeUnit.SECONDS);
    }

    private static CrawlTaskVO view(CrawlTask task) {
        return new CrawlTaskVO(task.getId(), task.getTaskName(), task.getTotalCount(), task.getSuccessCount(),
                task.getFailCount(), task.getStartTime(), task.getEndTime(), task.getStatus(), task.getRemark());
    }

    private static final class MemoryCheckpoints extends CrawlCheckpointRepository {
        private final CrawlTaskRepository tasks;
        private final Set<Long> attempts = new HashSet<>();
        private long root, latest;
        private String status, config;
        private boolean pause;
        private LocalDate observed;
        private int savedRows;
        private final Set<LocalDate> dates = new HashSet<>();
        private final List<Coverage> coverage = new ArrayList<>();
        // 固定证券池模拟上次已经保存过的清单；StockInfoRepository故意为null，恢复不得访问当前池。
        private String failCode;
        private String securities = "[{\"code\":\"588000\",\"name\":\"测试ETF\",\"market\":1,"
                + "\"secid\":\"sh.588000\",\"board\":\"ETF\",\"exchange\":\"SH\"}]";

        MemoryCheckpoints(CrawlTaskRepository tasks) {
            super(null, new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
                public void commit(TransactionStatus status) {}
                public void rollback(TransactionStatus status) {}
            }, null, tasks);
            this.tasks = tasks;
        }
        @Override public synchronized long create(CrawlTask task, String configJson, LocalDate observedOn) {
            tasks.create(task); root = latest = task.getId(); config = configJson; status = "running"; observed = observedOn;
            attempts.add(latest); return latest;
        }
        @Override public synchronized CrawlJobCheckpoint find(long taskId) {
            if (!attempts.contains(taskId)) return null;
            return new CrawlJobCheckpoint(root, latest, status, pause, config, securities, observed,
                    null, null, null, null, LocalDateTime.now());
        }
        @Override public synchronized long resume(CrawlJobCheckpoint previous, CrawlTask task, LocalDate observedOn) {
            tasks.create(task); latest = task.getId(); attempts.add(latest); status = "running";
            observed = observedOn; pause = false; return latest;
        }
        @Override public synchronized boolean requestPause(long taskId) { pause = true; return true; }
        @Override public synchronized boolean pauseRequested(long taskId) { return pause; }
        @Override public synchronized void finish(long taskId, String next, String logStatus, int success, int fail, String remark) {
            tasks.finish(taskId, logStatus, success, fail, remark); status = next; pause = false;
        }
        @Override public Map<LocalDate, Boolean> calendar(Range range) { return Map.of(DAY, true); }
        @Override public Set<LocalDate> existingDates(String code, String market, int flag, Range range) { return Set.copyOf(dates); }
        @Override public List<Coverage> coverage(String code, String market, int flag, String basis, Range range) { return List.copyOf(coverage); }
        @Override public int saveCompleted(long taskId, String code, String market, int flag, String basis, Range range, List<StockDailyVO> rows) {
            if (code.equals(failCode)) throw new IllegalStateException("模拟该证券原子提交失败");
            rows.forEach(row -> dates.add(row.tradeDate())); savedRows += rows.size();
            coverage.add(new Coverage(range, Set.copyOf(dates))); return rows.size();
        }
    }
}
