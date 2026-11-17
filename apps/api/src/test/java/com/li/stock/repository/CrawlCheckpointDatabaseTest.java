package com.li.stock.repository;

import com.li.stock.crawl.CrawlCoveragePlanner;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.mapper.StockDailyMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式开启的真实 MySQL 原子性验收，默认离线测试不会建立任何连接。
 *
 * <p>地址、库名、账号均固定为专用验收环境；只有密码从环境注入且从不打印。
 * 不启动 SpringBoot，不执行 DDL、不使用生产账号。每个用例先排除碰撞，再创建负任务号
 * 和1901年虚构ETF行情，finally阶段只删除自己拥有的精确键并另行检查无残留。</p>
 */
@EnabledIfEnvironmentVariable(named = "STOCK_CHECKPOINT_DB_TEST", matches = "true")
class CrawlCheckpointDatabaseTest {
    private static final String DATABASE = "stock_platform_verify";
    private static final String USER = "stock_platform_verify";
    private static final String CODE = "588999";
    private static final String MISSING_TABLE = "stock_checkpoint_failure_table_must_not_exist";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private DataSourceTransactionManager manager;
    private StockDailyMapper mapper;
    private CrawlCheckpointRepository repository;
    private long taskId;
    private Range range;
    private boolean ownsFixture;

    @BeforeEach
    void connectOnlyToExplicitVerificationDatabase() throws Exception {
        assertEquals(USER, System.getenv("STOCK_VERIFY_DB_USER"), "只允许专用验收账号，拒绝root或生产账号");
        String password = System.getenv("STOCK_VERIFY_DB_PASSWORD");
        assertNotNull(password, "验收密码必须通过环境注入");
        assertFalse(password.isBlank(), "验收密码不能为空");
        source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl("jdbc:mysql://127.0.0.1:13306/" + DATABASE
                + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        source.setUsername(USER); source.setPassword(password);
        jdbc = new JdbcTemplate(source);
        assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith(USER + "@"),
                "数据库认证身份必须是专用验收账号");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                Integer.class, DATABASE, MISSING_TABLE));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? "
                        + "AND TABLE_NAME IN ('stock_daily_58','crawl_job_checkpoint','crawl_job_attempt','crawl_coverage','crawl_trade_calendar') "
                        + "AND ENGINE='InnoDB'", Integer.class, DATABASE), "必须显式预装事务表，测试不自动迁移");

        manager = new DataSourceTransactionManager(source);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/StockDailyMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(StockDailyMapper.class);
        repository = new CrawlCheckpointRepository(jdbc, manager, new StockDailyRepository(mapper), null);

        int sequence = SEQUENCE.incrementAndGet();
        taskId = -(System.currentTimeMillis() * 1000 + sequence);
        LocalDate first = LocalDate.of(1901, 1, 1).plusDays(sequence * 5L);
        range = new Range(first, first.plusDays(2));
        assertEquals(0, dailyCount(), "测试日期已有行情，拒绝覆盖或删除现存记录");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crawl_coverage WHERE code=? AND market='SH' "
                + "AND from_date<=? AND to_date>=?", Integer.class, CODE, range.to(), range.from()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crawl_job_checkpoint WHERE root_task_id=? OR latest_task_id=?",
                Integer.class, taskId, taskId));
        jdbc.update("INSERT INTO crawl_job_checkpoint (root_task_id,latest_task_id,status,config_json,observed_on) "
                + "VALUES (?,?,'running','{}',?)", taskId, taskId, LocalDate.now());
        ownsFixture = true;
    }

    @AfterEach
    void removeOnlyOwnedKeysAndVerifyNothingRemains() {
        if (!ownsFixture) return;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM crawl_coverage WHERE task_id=? AND code=? AND market='SH'", taskId, CODE);
            jdbc.update("DELETE FROM stock_daily_58 WHERE code=? AND market='SH' AND trade_date BETWEEN ? AND ?",
                    CODE, range.from(), range.to());
            jdbc.update("DELETE FROM crawl_job_attempt WHERE root_task_id=?", taskId);
            jdbc.update("DELETE FROM crawl_job_checkpoint WHERE root_task_id=? AND latest_task_id=?", taskId, taskId);
        });
        // DriverManagerDataSource没有连接池；事务结束后的这些读取使用新的连接核对真实提交结果。
        assertEquals(0, dailyCount(), "用例清理后仍有测试行情残留");
        assertEquals(0, coverageCount(), "用例清理后仍有测试覆盖残留");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crawl_job_checkpoint WHERE root_task_id=?", Integer.class, taskId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crawl_job_attempt WHERE root_task_id=?", Integer.class, taskId));
    }

    @Test
    void realMapperRowsAndCoverageCommitTogether() {
        assertEquals(1, save(repository, List.of(row(range.from()))));
        assertEquals(1, dailyCount());
        assertEquals(1, coverageCount());
        assertEquals(Set.of(range.from()), repository.coverage(CODE, "SH", 3, "db-test", range).getFirst().returnedDates());
        assertEquals(CODE, jdbc.queryForObject("SELECT last_code FROM crawl_job_checkpoint WHERE root_task_id=?", String.class, taskId));
    }

    @Test
    void realSqlCoverageFailureRollsBackTheAlreadyInsertedDailyRow() {
        JdbcTemplate failingCoverage = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO crawl_coverage")) {
                    // 故障发生于真实MySQL语句；不创建/更改表，不依赖数据库是否启用严格数值模式。
                    return super.update("INSERT INTO crawl_coverage (checkpoint_test_column_does_not_exist) VALUES (1)");
                }
                return super.update(sql, args);
            }
        };
        CrawlCheckpointRepository failing = new CrawlCheckpointRepository(failingCoverage, manager,
                new StockDailyRepository(mapper), null);
        assertThrows(RuntimeException.class, () -> save(failing, List.of(row(range.from()))));
        assertEquals(0, dailyCount()); assertEquals(0, coverageCount());
    }

    @Test
    void realMyBatisDailyFailureDoesNotCreateCoverage() {
        StockDailyRepository failingDaily = new StockDailyRepository(mapper) {
            @Override public int saveCrawlerBatch(List<StockDailyVO> rows) {
                // 使用实际StockDailyMapper执行缺失表SQL，验证行情失败时后续覆盖写入完全没有发生。
                return mapper.saveCrawlerBatch(MISSING_TABLE, rows);
            }
        };
        CrawlCheckpointRepository failing = new CrawlCheckpointRepository(jdbc, manager, failingDaily, null);
        assertThrows(RuntimeException.class, () -> save(failing, List.of(row(range.from()))));
        assertEquals(0, dailyCount()); assertEquals(0, coverageCount());
    }

    @Test
    void legitimateEmptyResponseIsCommittedAsZeroRowsWithEmptyManifest() {
        assertEquals(0, save(repository, List.of()));
        assertEquals(0, dailyCount()); assertEquals(1, coverageCount());
        assertTrue(repository.coverage(CODE, "SH", 3, "db-test", range).getFirst().returnedDates().isEmpty());
    }

    @Test
    void wrongTaskOwnershipRollsBackBothDailyAndCoverage() {
        jdbc.update("UPDATE crawl_job_checkpoint SET status='paused' WHERE root_task_id=?", taskId);
        assertThrows(IllegalStateException.class, () -> save(repository, List.of(row(range.from()))));
        assertEquals(0, dailyCount()); assertEquals(0, coverageCount());
    }

    @Test
    void exactLegacyDatesRevealInternalHoleDespiteMatchingMinimumAndMaximum() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                mapper.saveCrawlerBatch("stock_daily_58", List.of(row(range.from()), row(range.to()))));
        Set<LocalDate> actual = repository.existingDates(CODE, "SH", 3, range);
        assertEquals(Set.of(range.from(), range.to()), actual);
        LocalDate missing = range.from().plusDays(1);
        assertEquals(List.of(new Range(missing, missing)), CrawlCoveragePlanner.missing(range,
                Set.of(range.from(), missing, range.to()), actual, true, List.of()));
    }

    private int save(CrawlCheckpointRepository target, List<StockDailyVO> rows) {
        return target.saveCompleted(taskId, CODE, "SH", 3, "db-test", range, rows);
    }

    private int dailyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_daily_58 WHERE code=? AND market='SH' AND trade_date BETWEEN ? AND ?",
                Integer.class, CODE, range.from(), range.to());
    }

    private int coverageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crawl_coverage WHERE task_id=?", Integer.class, taskId);
    }

    private StockDailyVO row(LocalDate day) {
        return new StockDailyVO(CODE, "SH", day, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 100L, BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, 3, 1, null, null, null, null, null, "checkpoint-test");
    }
}
