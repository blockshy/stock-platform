package com.li.stock.repository;

import com.li.stock.domain.model.ForecastResult;
import com.li.stock.domain.vo.ShortTermForecastVO;
import com.li.stock.mapper.ShortTermForecastMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class ShortTermForecastRepository {

    /** 限制单条多值 INSERT 的参数量，避免全市场四个预测期限一起生成超大 SQL。 */
    private static final int WRITE_BATCH_SIZE = 500;

    private final ShortTermForecastMapper mapper;

    public ShortTermForecastRepository(ShortTermForecastMapper mapper) {
        this.mapper = mapper;
    }

    public long count(LocalDate tradeDate, Integer adjustFlag, String keyword, BigDecimal minProbability) {
        return mapper.count(tradeDate, adjustFlag, keyword, minProbability);
    }

    public List<ShortTermForecastVO> page(LocalDate tradeDate, Integer adjustFlag, String keyword,
                                          BigDecimal minProbability, int offset, int size) {
        return mapper.page(tradeDate, adjustFlag, keyword, minProbability, offset, size);
    }

    public List<LocalDate> availableDates(Integer adjustFlag, int limit) {
        return mapper.availableDates(adjustFlag, limit);
    }

    public LocalDate latestDate(int adjustFlag) {
        return mapper.latestDate(adjustFlag);
    }

    /**
     * 以原顺序分批持久化，保留 MySQL 受影响行数之和作为返回值。
     * 现有结果表为 InnoDB，整个结果集放在同一事务中，批次失败时不会留下部分新结果。
     */
    @Transactional
    public int save(List<ForecastResult> results) {
        int savedRows = 0;
        for (int from = 0; from < results.size(); from += WRITE_BATCH_SIZE) {
            int to = Math.min(from + WRITE_BATCH_SIZE, results.size());
            savedRows += mapper.save(results.subList(from, to));
        }
        return savedRows;
    }
}
