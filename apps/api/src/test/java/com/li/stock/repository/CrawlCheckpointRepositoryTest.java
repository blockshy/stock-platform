package com.li.stock.repository;

import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.domain.vo.StockDailyVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用记录型 SQL 替身和可回滚内存事务验证生产 TransactionTemplate 边界。
 * 不启动 Spring 应用，也不创建真实数据源；覆盖/行情失败均必须回滚同一个提交单元。
 */
class CrawlCheckpointRepositoryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private final MemoryTransaction manager = new MemoryTransaction();
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private boolean failDaily;
    private final StockDailyRepository daily = new StockDailyRepository(null) {
        @Override public int saveCrawlerBatch(List<StockDailyVO> rows) {
            if (failDaily) throw new IllegalStateException("模拟行情写入失败");
            manager.rows.addAll(rows);
            return rows.size();
        }
    };
    private final CrawlCheckpointRepository repository = new CrawlCheckpointRepository(jdbc, manager, daily, null);

    @Test
    void coverageFailureRollsBackTheDailyRowsWrittenEarlierInTheSameTransaction() {
        jdbc.failCoverage = true;
        assertThrows(IllegalStateException.class, () -> save(List.of(row(3))));
        assertTrue(manager.rows.isEmpty());
        assertTrue(manager.coverage.isEmpty());
        assertEquals(1, manager.rollbacks);
        assertEquals(0, manager.commits);
    }

    @Test
    void dailyFailureNeverWritesSuccessCoverage() {
        failDaily = true;
        assertThrows(IllegalStateException.class, () -> save(List.of(row(3))));
        assertTrue(manager.coverage.isEmpty());
        assertEquals(0, jdbc.coverageWrites);
        assertEquals(1, manager.rollbacks);
    }

    @Test
    void validEmptyResponseCommitsAnExplicitEmptyManifest() {
        assertEquals(0, save(List.of()));
        assertEquals(1, manager.commits);
        assertEquals("[]", manager.coverage.getFirst());
        assertTrue(manager.rows.isEmpty());
    }

    @Test
    void actualFlagMismatchIsRejectedBeforeAnyTransactionStarts() {
        assertThrows(IllegalArgumentException.class, () -> save(List.of(row(2))));
        assertEquals(0, manager.started);
        assertTrue(manager.coverage.isEmpty());
    }

    @Test
    void noLongerOwningTheRunningTaskRollsBackRowsAndCoverage() {
        jdbc.ownsTask = false;
        assertThrows(IllegalStateException.class, () -> save(List.of(row(3))));
        assertTrue(manager.rows.isEmpty());
        assertTrue(manager.coverage.isEmpty());
        assertEquals(1, manager.rollbacks);
    }

    @Test
    void incompleteCalendarIsRejectedInsteadOfInferringTheMissingDayWasClosed() {
        assertThrows(IllegalArgumentException.class, () -> repository.saveCalendar(
                new Range(DAY, DAY.plusDays(1)), Map.of(DAY, true)));
        assertEquals(0, manager.started);
    }

    private int save(List<StockDailyVO> rows) {
        return repository.saveCompleted(1, "588000", "SH", 3, "raw", new Range(DAY, DAY), rows);
    }

    private StockDailyVO row(int flag) {
        return new StockDailyVO("588000", "SH", DAY, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 100L, BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, flag, 1, null, null, null, null, null, "baostock");
    }

    private final class RecordingJdbc extends JdbcTemplate {
        private boolean failCoverage;
        private boolean ownsTask = true;
        private int coverageWrites;

        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO crawl_coverage")) {
                coverageWrites++;
                if (failCoverage) throw new IllegalStateException("模拟覆盖写入失败");
                manager.coverage.add((String) args[6]);
                return 1;
            }
            if (sql.startsWith("UPDATE crawl_job_checkpoint")) return ownsTask ? 1 : 0;
            throw new AssertionError("非预期 SQL：" + sql);
        }
    }

    /** 快照事务模拟故障回滚，用来证明两种写入确实位于同一生产事务回调内。 */
    private static final class MemoryTransaction implements PlatformTransactionManager {
        private final List<StockDailyVO> rows = new ArrayList<>();
        private final List<String> coverage = new ArrayList<>();
        private List<StockDailyVO> beforeRows;
        private List<String> beforeCoverage;
        private int started, commits, rollbacks;

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            beforeRows = List.copyOf(rows);
            beforeCoverage = List.copyOf(coverage);
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { commits++; }
        @Override public void rollback(TransactionStatus status) {
            rollbacks++;
            rows.clear(); rows.addAll(beforeRows);
            coverage.clear(); coverage.addAll(beforeCoverage);
        }
    }
}
