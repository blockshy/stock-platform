package com.li.stock.service;

import com.li.stock.domain.model.ScoreBar;
import com.li.stock.repository.ScoreCalculationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 横截面分位计算的语义回归测试。
 * 以改动前的排序、线性计数公式作为独立参照，验证优化只减少工作量而不改变业务结果。
 * 全部使用内存数据，不连接数据库或启动 Spring 上下文。
 */
class ScoreCalculationServicePercentileTest {

    /** 空总体继续返回空值，由上层评分逻辑跳过这一项，不能按零分参与加权。 */
    @Test
    void emptyPopulationRemainsMissingInBothDirections() {
        for (boolean higherIsBetter : List.of(false, true)) {
            assertNull(ScoreCalculationService.percentile(null, 1.0, higherIsBetter));
            assertNull(ScoreCalculationService.percentile(List.of(), 1.0, higherIsBetter));
        }
    }

    /** 重复值、单样本、区间外查询以及正负零均与原公式逐位一致。 */
    @Test
    void boundariesAndSignedZeroMatchOriginalFormula() {
        List<List<Double>> populations = List.of(
                List.of(7.0),
                List.of(3.0, 3.0, 3.0, 3.0),
                List.of(9.0, -2.0, 4.0, 4.0, 1.0),
                List.of(-0.0, +0.0, -0.0, +0.0, -1.0, 1.0),
                List.of(-Double.MAX_VALUE, -Double.MIN_VALUE, Double.MIN_VALUE, Double.MAX_VALUE));
        List<Double> queries = List.of(
                Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -2.0, -Double.MIN_VALUE,
                -0.0, +0.0, Double.MIN_VALUE, 1.0, 3.0, 4.0, 7.0, 9.0,
                Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN);
        for (List<Double> population : populations) {
            for (double query : queries) {
                assertMatchesOriginal(population, query);
            }
        }
    }

    /** 固定随机种子混合密集重复值与连续数值，覆盖不存在于总体中的二分边界。 */
    @Test
    void randomFinitePopulationsMatchOriginalFormulaExactly() {
        Random random = new Random(20261117L);
        for (int round = 0; round < 100; round++) {
            List<Double> population = new ArrayList<>();
            int count = 1 + random.nextInt(300);
            for (int i = 0; i < count; i++) {
                population.add(i % 2 == 0 ? (double) (random.nextInt(21) - 10) : random.nextGaussian());
            }
            population.add(-0.0);
            population.add(+0.0);
            for (double present : population) assertMatchesOriginal(population, present);
            for (int i = 0; i < 20; i++) assertMatchesOriginal(population, random.nextGaussian() * 10);
        }
    }

    /**
     * 从未排序的真实评分输入走完 rebuild，防止只优化查询却遗漏生产路径的预排序。
     * 每只股票只给一天行情，使其他因子保持共同取值，重点核对重复估值样本的反向排名。
     */
    @Test
    void rebuildPreparesEachPopulationBeforeScoringStocks() {
        LocalDate asOf = LocalDate.of(2026, 11, 16);
        List<Double> valuations = List.of(30.0, 10.0, 10.0, 20.0);
        List<ScoreBar> bars = new ArrayList<>();
        for (int i = 0; i < valuations.size(); i++) {
            BigDecimal valuation = BigDecimal.valueOf(valuations.get(i));
            bars.add(new ScoreBar("00000" + i, "SZ", asOf, BigDecimal.TEN,
                    BigDecimal.valueOf(1000), BigDecimal.ONE, 1, 0,
                    valuation, valuation, valuation, valuation));
        }
        List<ScoreCalculationService.ScoreResult> saved = new ArrayList<>();
        ScoreCalculationRepository repository = new ScoreCalculationRepository(null) {
            @Override
            public List<ScoreBar> loadBars(LocalDate date, int adjustFlag, int calendarDays) {
                return bars;
            }

            @Override
            public int save(List<ScoreCalculationService.ScoreResult> results) {
                saved.addAll(results);
                return results.size();
            }
        };

        new ScoreCalculationService(repository).rebuild(asOf, 2);

        assertEquals(valuations.size(), saved.size());
        Map<String, ScoreCalculationService.ScoreResult> byCode = saved.stream()
                .collect(Collectors.toMap(ScoreCalculationService.ScoreResult::code, result -> result));
        for (int i = 0; i < valuations.size(); i++) {
            double expectedValue = originalPercentile(valuations, valuations.get(i), false) * 100;
            assertEquals(expectedValue, byCode.get("00000" + i).valueScore().doubleValue());
        }
    }

    /** 同一组输入同时验证正向与反向；精确比较避免浮点容差掩盖公式变化。 */
    private static void assertMatchesOriginal(List<Double> population, double value) {
        List<Double> sorted = population.stream().sorted().toList();
        for (boolean higherIsBetter : List.of(false, true)) {
            assertEquals(originalPercentile(population, value, higherIsBetter),
                    ScoreCalculationService.percentile(sorted, value, higherIsBetter),
                    () -> "查询值=" + value + "，正向=" + higherIsBetter + "，总体=" + population);
        }
    }

    /** 保留改动前的独立计算方式，作为测试参照；生产代码不再执行重复排序和线性扫描。 */
    private static Double originalPercentile(List<Double> values, double value, boolean higherIsBetter) {
        if (values == null || values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        long lower = sorted.stream().filter(candidate -> candidate < value).count();
        long equal = sorted.stream().filter(candidate -> Double.compare(candidate, value) == 0).count();
        double score = (lower + 0.5 * equal) / sorted.size();
        return higherIsBetter ? score : 1 - score;
    }
}
