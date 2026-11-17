package com.li.stock.repository;

import com.li.stock.domain.model.ForecastPrediction;
import com.li.stock.domain.model.ForecastRun;
import com.li.stock.domain.vo.ShortTermDailyForecastVO;
import com.li.stock.domain.vo.ShortTermRunSummaryVO;
import com.li.stock.domain.vo.ShortTermRunVO;
import com.li.stock.mapper.ShortTermHistoryMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 短期预测历史记录仓储。 */
@Repository
public class ShortTermHistoryRepository {
    /**
     * 单条 MyBatis 多值 INSERT 的行数上限。
     * 全市场逐日预测每只股票有 20 行；分批可避免一次构造数十万至上百万个参数映射。
     */
    private static final int WRITE_BATCH_SIZE = 500;

    private final ShortTermHistoryMapper mapper;

    public ShortTermHistoryRepository(ShortTermHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public ForecastRun createRun(ForecastRun run) {
        mapper.createRun(run);
        return run;
    }

    /**
     * 原子保存新批次及其全部明细，事务只覆盖写入阶段，不包含前面的行情加载与预测计算。
     *
     * <p>现有 stock_forecast_run 和 stock_forecast_prediction 均为 InnoDB 表。
     * 任一批明细写入失败都会回滚批次头和已经写入的明细，避免出现只有 stock_count 的空批次。
     * 服务层通过 Spring 管理的仓储代理调用本方法，确保事务拦截器实际生效。</p>
     *
     * @return 各批 SQL 的受影响行数之和，保持原接口的 savedRows 计数语义
     */
    @Transactional
    public int saveRunPredictions(ForecastRun run, List<ForecastPrediction> list) {
        if (list.isEmpty()) return 0;
        createRun(run);
        if (run.getId() == null) throw new IllegalStateException("预测批次主键未生成，已取消明细写入");
        list.forEach(item -> item.setRunId(run.getId()));
        return savePredictions(list);
    }

    /** 分批写入保持输入顺序；独立调用时同样保证所有批次一起提交或一起回滚。 */
    @Transactional
    public int savePredictions(List<ForecastPrediction> list) {
        int savedRows = 0;
        for (int from = 0; from < list.size(); from += WRITE_BATCH_SIZE) {
            int to = Math.min(from + WRITE_BATCH_SIZE, list.size());
            savedRows += mapper.savePredictions(list.subList(from, to));
        }
        return savedRows;
    }

    public List<ShortTermRunVO> listRuns(Integer adjustFlag, int limit) {
        return mapper.listRuns(adjustFlag, limit);
    }

    public List<LocalDate> sourceDates(Integer adjustFlag, int limit) {
        return mapper.sourceDates(adjustFlag, limit);
    }

    public ShortTermRunVO findRun(long runId) {
        return mapper.findRun(runId);
    }

    public long summaryCount(long runId, String keyword, BigDecimal minProbability) {
        return mapper.summaryCount(runId, keyword, minProbability);
    }

    public List<ShortTermRunSummaryVO> summaryPage(long runId, String keyword, BigDecimal minProbability,
                                                   String sortBy, String sortDirection, int offset, int size) {
        return mapper.summaryPage(runId, keyword, minProbability, sortBy, sortDirection, offset, size);
    }

    public List<ShortTermDailyForecastVO> daily(long runId, String code) {
        return mapper.daily(runId, code);
    }

    public List<ForecastPrediction> predictionRows(long runId) {
        return mapper.predictionRows(runId);
    }

    /** 复盘回填也有每股 20 行，使用相同分批边界，并避免失败后只更新部分目标交易日。 */
    @Transactional
    public int updateEvaluations(List<ForecastPrediction> list) {
        for (int from = 0; from < list.size(); from += WRITE_BATCH_SIZE) {
            int to = Math.min(from + WRITE_BATCH_SIZE, list.size());
            mapper.saveEvaluations(list.subList(from, to));
        }
        // MySQL 对 ON DUPLICATE KEY UPDATE 可能按 2 计数，业务上返回实际处理的预测条数。
        return list.size();
    }
}
