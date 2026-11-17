package com.li.stock.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 计划边界离线测试；入口没有显式在线开关时，必须在登录/写快照之前失败。 */
class BaoStockBatchProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String VALID = """
            {"from":"2023-11-17","to":"2026-11-17","dates":["2023-11-17","2026-11-17"],
             "codes":["sz.000001","sh.588000"],"requestDelayMillis":1500}
            """;

    @Test void requiresExplicitOnlineEntry() {
        assertThrows(IllegalStateException.class, () -> BaoStockBatchProbe.main(new String[0]));
    }

    @Test void rejectsDuplicateDatesUnsupportedMarketsAndAggressiveIntervals() throws Exception {
        assertDoesNotThrow(() -> BaoStockBatchProbe.validatePlan(json.readTree(VALID)));
        assertThrows(IllegalArgumentException.class, () -> BaoStockBatchProbe.validatePlan(json.readTree(VALID.replace("2023-11-17\",\"2026-11-17", "2026-11-17\",\"2026-11-17"))));
        assertThrows(IllegalArgumentException.class, () -> BaoStockBatchProbe.validatePlan(json.readTree(VALID.replace("sz.000001", "sz.588000"))));
        assertThrows(IllegalArgumentException.class, () -> BaoStockBatchProbe.validatePlan(json.readTree(VALID.replace("1500", "0"))));
    }

    /** 非交易日必须有明确日历证据；缺行、重复或未知状态不能绕过源数据完整性检查。 */
    @Test void skipsOnlyExplicitNonTradingDates() {
        LocalDate day = LocalDate.of(2026, 11, 15);
        Map<String, String> closed = Map.of("date", day.toString(), "is_trading_day", "0");
        assertFalse(BaoStockBatchProbe.isTradingDate(List.of(closed), day));
        assertTrue(BaoStockBatchProbe.isTradingDate(List.of(Map.of("date", day.toString(), "is_trading_day", "1")), day));
        assertThrows(IllegalStateException.class, () -> BaoStockBatchProbe.isTradingDate(List.of(), day));
        assertThrows(IllegalStateException.class, () -> BaoStockBatchProbe.isTradingDate(List.of(closed, closed), day));
        assertThrows(IllegalStateException.class, () -> BaoStockBatchProbe.isTradingDate(List.of(Map.of("date", day.toString())), day));
    }
}
