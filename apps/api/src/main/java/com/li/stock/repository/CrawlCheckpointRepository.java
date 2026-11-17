package com.li.stock.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.crawl.CrawlCoveragePlanner.Coverage;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import com.li.stock.crawl.CrawlJobCheckpoint;
import com.li.stock.domain.model.CrawlTask;
import com.li.stock.domain.vo.StockDailyVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 持久断点仓储。行情和成功覆盖必须通过同一个 Spring 数据源事务提交。
 *
 * <p>覆盖写入失败时行情也回滚；行情写入失败时绝不留下成功覆盖。网络查询位于事务外，
 * 防止一分钟的上游等待长时间占用数据库事务。DDL 单独提供，由部署流程显式安装；
 * 此类既不创建表，也不连接其他数据源。</p>
 */
@Repository
public class CrawlCheckpointRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StockDailyRepository daily;
    private final CrawlTaskRepository tasks;
    private final ObjectMapper json = new ObjectMapper();

    public CrawlCheckpointRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                     StockDailyRepository daily, CrawlTaskRepository tasks) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.daily = daily;
        this.tasks = tasks;
    }

    /** 新任务与固定参数一起落库，提交失败时不会留下不可恢复的 running 日志。 */
    public long create(CrawlTask task, String configJson, LocalDate observedOn) {
        return transaction.execute(status -> {
            tasks.create(task);
            jdbc.update("INSERT INTO crawl_job_checkpoint "
                            + "(root_task_id,latest_task_id,status,config_json,observed_on) VALUES (?,?,'running',?,?)",
                    task.getId(), task.getId(), configJson, observedOn);
            jdbc.update("INSERT INTO crawl_job_attempt (task_id,root_task_id) VALUES (?,?)", task.getId(), task.getId());
            return task.getId();
        });
    }

    /** 按任意一轮任务编号读取逻辑任务，不依赖该进程是否仍持有内存对象。 */
    public CrawlJobCheckpoint find(long taskId) {
        List<CrawlJobCheckpoint> rows = jdbc.query("SELECT j.* FROM crawl_job_checkpoint j "
                + "JOIN crawl_job_attempt a ON a.root_task_id=j.root_task_id WHERE a.task_id=?", JOB_MAPPER, taskId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 新一轮使用原参数/原证券池；SQL 条件用于防止两个继续请求创建两条运行链。 */
    public long resume(CrawlJobCheckpoint previous, CrawlTask task, LocalDate observedOn) {
        return transaction.execute(status -> {
            tasks.create(task);
            int changed = jdbc.update("UPDATE crawl_job_checkpoint SET latest_task_id=?,status='running',"
                            + "pause_requested=FALSE,observed_on=?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE root_task_id=? AND latest_task_id=? AND status IN ('paused','failed','interrupted','partial')",
                    task.getId(), observedOn, previous.rootTaskId(), previous.latestTaskId());
            if (changed != 1) throw new IllegalStateException("该任务状态已变化，不能重复继续，请刷新任务记录");
            jdbc.update("INSERT INTO crawl_job_attempt (task_id,root_task_id) VALUES (?,?)", task.getId(), previous.rootTaskId());
            return task.getId();
        });
    }

    /** 只固定首次确认的证券清单；恢复时不重新按当天名单改变任务分母。 */
    public void saveSecurities(long taskId, String securitiesJson) {
        int changed = jdbc.update("UPDATE crawl_job_checkpoint SET securities_json=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE latest_task_id=? AND securities_json IS NULL", securitiesJson, taskId);
        if (changed == 0 && find(taskId) == null) throw new IllegalStateException("采集断点不存在");
    }

    /** 请求暂停只设置意图；完成当前区间事务后由工作线程真正切换为 paused。 */
    public boolean requestPause(long taskId) {
        return jdbc.update("UPDATE crawl_job_checkpoint SET pause_requested=TRUE,updated_at=CURRENT_TIMESTAMP "
                + "WHERE latest_task_id=? AND status='running'", taskId) == 1;
    }

    public boolean pauseRequested(long taskId) {
        Boolean requested = jdbc.queryForObject("SELECT pause_requested FROM crawl_job_checkpoint WHERE latest_task_id=?",
                Boolean.class, taskId);
        return Boolean.TRUE.equals(requested);
    }

    /** 任务日志与逻辑任务终态同步提交，避免界面显示已完成但继续按钮仍绑定运行态。 */
    public void finish(long taskId, String checkpointStatus, String logStatus, int success, int fail, String remark) {
        transaction.executeWithoutResult(status -> {
            tasks.finish(taskId, logStatus, success, fail, remark);
            jdbc.update("UPDATE crawl_job_checkpoint SET status=?,pause_requested=FALSE,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE latest_task_id=?", checkpointStatus, taskId);
        });
    }

    /** 重启只标记被打断，不把未经安全暂停的任务伪装成 paused；已提交覆盖始终保留。 */
    public void interruptRunningOnStartup() {
        jdbc.update("UPDATE crawl_job_checkpoint SET status='interrupted',pause_requested=FALSE,updated_at=CURRENT_TIMESTAMP "
                + "WHERE status='running'");
    }

    /** 精确日期按分表主键读取，包括内部洞；禁止从 MIN/MAX 推断区间完整。 */
    public Set<LocalDate> existingDates(String code, String market, int flag, Range range) {
        return new HashSet<>(jdbc.query("SELECT trade_date FROM " + table(code)
                        + " WHERE code=? AND market=? AND adjustflag=? AND trade_date BETWEEN ? AND ?",
                (rs, n) -> rs.getDate(1).toLocalDate(), code, market, flag, range.from(), range.to()));
    }

    /** 只取当前基准版本的相交覆盖；其它版本即使日期相同也不能用于跳过复权行情。 */
    public List<Coverage> coverage(String code, String market, int flag, String basis, Range range) {
        return jdbc.query("SELECT from_date,to_date,returned_dates_json FROM crawl_coverage "
                        + "WHERE code=? AND market=? AND adjustflag=? AND basis_key=? AND from_date<=? AND to_date>=?",
                (rs, n) -> new Coverage(new Range(rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate()),
                        datesFromJson(rs.getString(3))), code, market, flag, basis, range.to(), range.from());
    }

    /**
     * 一个成功响应对应一个原子提交单元，空 rows 也是合法覆盖。
     * 返回日期清单保留哪些交易日确实有数据，避免以后无法区分合法空段与行情被删的缺口。
     */
    public int saveCompleted(long taskId, String code, String market, int flag, String basis,
                             Range range, List<StockDailyVO> rows) {
        Set<LocalDate> returned = new HashSet<>();
        for (StockDailyVO row : rows) {
            if (!code.equals(row.code()) || !market.equals(row.market()) || !Integer.valueOf(flag).equals(row.adjustFlag())
                    || row.tradeDate() == null || row.tradeDate().isBefore(range.from()) || row.tradeDate().isAfter(range.to())) {
                throw new IllegalArgumentException("上游日线的证券、日期或实际复权口径与请求不一致，未记录覆盖");
            }
            if (!returned.add(row.tradeDate())) throw new IllegalArgumentException("上游响应包含重复交易日，未记录覆盖");
        }
        String returnedJson = datesToJson(returned);
        return transaction.execute(status -> {
            int saved = daily.saveCrawlerBatch(rows);
            jdbc.update("INSERT INTO crawl_coverage "
                            + "(code,market,adjustflag,basis_key,from_date,to_date,returned_dates_json,task_id,row_count) "
                            + "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                            + "returned_dates_json=VALUES(returned_dates_json),task_id=VALUES(task_id),row_count=VALUES(row_count),"
                            + "updated_at=CURRENT_TIMESTAMP",
                    code, market, flag, basis, range.from(), range.to(), returnedJson, taskId, rows.size());
            int updated = jdbc.update("UPDATE crawl_job_checkpoint SET last_code=?,last_adjust_flag=?,last_from=?,last_to=?,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE latest_task_id=? AND status='running'", code, flag, range.from(), range.to(), taskId);
            if (updated != 1) throw new IllegalStateException("采集任务已不再拥有运行断点，行情与覆盖一并回滚");
            return saved;
        });
    }

    /** 返回已核验的完整日历子集，缺键仍表示未知，而非非交易日。 */
    public Map<LocalDate, Boolean> calendar(Range range) {
        Map<LocalDate, Boolean> result = new LinkedHashMap<>();
        jdbc.query("SELECT calendar_date,is_trading_day FROM crawl_trade_calendar WHERE calendar_date BETWEEN ? AND ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getDate(1).toLocalDate(), rs.getBoolean(2)),
                range.from(), range.to());
        return result;
    }

    /** 每个已成功返回的日历区间一次提交；日历不提供行情，也不伪造证券可交易状态。 */
    public void saveCalendar(Range range, Map<LocalDate, Boolean> calendar) {
        List<Object[]> entries = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            Boolean isTrading = calendar.get(day);
            if (isTrading == null) throw new IllegalArgumentException("交易日历缺少明确的自然日状态，未持久化未知日");
            entries.add(new Object[]{day, isTrading});
        }
        if (calendar.size() != entries.size()) throw new IllegalArgumentException("交易日历含请求区间外日期，未保存覆盖");
        transaction.executeWithoutResult(status -> jdbc.batchUpdate("INSERT INTO crawl_trade_calendar "
                + "(calendar_date,is_trading_day) VALUES (?,?) ON DUPLICATE KEY UPDATE "
                + "is_trading_day=VALUES(is_trading_day),updated_at=CURRENT_TIMESTAMP", entries));
    }

    private Set<LocalDate> datesFromJson(String value) {
        try {
            Set<LocalDate> dates = new HashSet<>();
            JsonNode parsed = json.readTree(value);
            if (!parsed.isArray()) throw new IllegalArgumentException("成功覆盖日期清单不是数组");
            for (JsonNode item : parsed) dates.add(LocalDate.parse(item.asText()));
            return dates;
        } catch (Exception ex) {
            // 损坏断点不能静默当作空响应，否则可能把真实行情丢失误判为合法空结果。
            throw new IllegalStateException("采集覆盖日期清单损坏，请检查断点记录", ex);
        }
    }

    private String datesToJson(Set<LocalDate> dates) {
        try { return json.writeValueAsString(dates.stream().sorted().map(LocalDate::toString).toList()); }
        catch (Exception ex) { throw new IllegalStateException("不能序列化采集覆盖日期", ex); }
    }

    private static String table(String code) {
        if (code != null && code.matches("(?:00|30|60|58)[0-9]{4}")) return "stock_daily_" + code.substring(0, 2);
        throw new IllegalArgumentException("不支持的证券分表代码");
    }

    private static LocalDate date(ResultSet rs, String name) throws SQLException {
        var value = rs.getDate(name);
        return value == null ? null : value.toLocalDate();
    }

    private static final RowMapper<CrawlJobCheckpoint> JOB_MAPPER = (rs, n) -> new CrawlJobCheckpoint(
            rs.getLong("root_task_id"), rs.getLong("latest_task_id"), rs.getString("status"),
            rs.getBoolean("pause_requested"), rs.getString("config_json"), rs.getString("securities_json"),
            date(rs, "observed_on"), rs.getString("last_code"), rs.getObject("last_adjust_flag", Integer.class),
            date(rs, "last_from"), date(rs, "last_to"), rs.getTimestamp("updated_at").toLocalDateTime());
}
