package com.li.stock.repository;

import com.li.stock.domain.model.ForecastResult;
import com.li.stock.mapper.ShortTermForecastMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证四个预测期限的快照写入边界，确保分批不会改变结果顺序及 savedRows 语义。 */
class ShortTermForecastRepositoryTest {

    @Test
    void forecastSnapshotsPreserveEveryRowAtBatchBoundaries() {
        for (int size : new int[]{0, 1, 499, 500, 501, 1000, 1001}) {
            List<List<ForecastResult>> calls = new ArrayList<>();
            // JDK 接口代理无需 Java Agent；只替代实际 SQL 执行，仓储分批逻辑完整运行。
            ShortTermForecastMapper mapper = (ShortTermForecastMapper) Proxy.newProxyInstance(
                    ShortTermForecastMapper.class.getClassLoader(), new Class<?>[]{ShortTermForecastMapper.class},
                    (proxy, method, arguments) -> {
                        if (!method.getName().equals("save")) {
                            throw new UnsupportedOperationException("测试未定义 Mapper 方法：" + method.getName());
                        }
                        @SuppressWarnings("unchecked")
                        List<ForecastResult> batch = (List<ForecastResult>) arguments[0];
                        calls.add(List.copyOf(batch));
                        // 模拟 ON DUPLICATE KEY UPDATE 的双倍受影响行数，不等同于业务输入行数。
                        return batch.size() * 2;
                    });
            List<ForecastResult> input = IntStream.range(0, size).mapToObj(index ->
                    new ForecastResult("测试行" + index, "SZ", LocalDate.of(2026, 11, 17), 2, 20,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, 30)).toList();

            int saved = new ShortTermForecastRepository(mapper).save(input);

            assertEquals(size * 2, saved, "分批后必须累计原有 SQL 计数");
            assertEquals((size + 499) / 500, calls.size());
            int index = 0;
            for (List<ForecastResult> batch : calls) {
                assertEquals(Math.min(500, size - index), batch.size());
                for (ForecastResult row : batch) assertSame(input.get(index++), row);
            }
            assertEquals(size, index, "全部行必须恰好写入一次");
        }
    }
}
