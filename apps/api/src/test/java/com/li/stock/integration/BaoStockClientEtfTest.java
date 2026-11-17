package com.li.stock.integration;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 ETF 字段隔离及源复权标记，不通过简化桩替代真实协议解析。 */
class BaoStockClientEtfTest {
    @Test
    void etfUsesMarketFieldsAndKeepsTheActualRawFlag() throws Exception {
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        BaoStockClient client = socket.client();
        List<Map<String, String>> rows = client.queryHistory("sh.588000",
                LocalDate.of(2023, 11, 17), LocalDate.of(2026, 11, 16), "2");

        assertEquals("3", rows.getFirst().get("adjustflag"), "不能把源不复权行情标成请求的前复权");
        assertEquals("1.1000", rows.getFirst().get("close"));
        assertEquals("123400", rows.getFirst().get("volume"));
        assertFalse(rows.getFirst().containsKey("isST"));
        assertFalse(socket.queries().getFirst().fields().contains("peTTM"));
        assertFalse(socket.queries().getFirst().fields().contains("pcfNcfTTM"));
    }

    @Test
    void stockRequestsKeepTheirExistingValuationAndStFields() throws Exception {
        BaoStockMemorySocket socket = new BaoStockMemorySocket();
        List<Map<String, String>> rows = socket.client().queryHistory("sz.000001",
                LocalDate.of(2023, 11, 17), LocalDate.of(2026, 11, 16), "2");

        assertEquals("2", rows.getFirst().get("adjustflag"));
        assertTrue(socket.queries().getFirst().fields().containsAll(List.of("isST", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM")));
        assertEquals("25.0", rows.getFirst().get("peTTM"));
    }
}
