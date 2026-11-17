package com.li.stock.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新批量协议的纯离线测试：输入/输出采用真实报文头、CRC 与 zlib 压缩，只有 Socket 被替换。
 * connect/getInputStream/getOutputStream/close 全部在内存实现，不会访问公共/VIP 节点或现有数据库。
 * 测试重点是整批边界、会话恢复与错误隔离，不比较任何股票评分或模型算法。
 */
class BaoStockClientBatchTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEP = "\1";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);
    private static final List<String> BAR = List.of("date", "code", "open", "high", "low", "close",
            "preclose", "volume", "amount", "adjustflag", "turn", "tradestatus", "pctChg");
    private static final List<String> STOCK = List.of("date", "code", "open", "high", "low", "close",
            "preclose", "volume", "amount", "adjustflag", "turn", "tradestatus", "pctChg",
            "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM", "isST");
    private static final List<String> FACTOR = List.of("code", "dividOperateDate", "foreAdjustFactor",
            "backAdjustFactor", "adjustFactor");

    @Test
    void stockBatchMapsByFieldNameAndPreservesOriginalPrecisionAndMissingValues() {
        List<String> reordered = new ArrayList<>(STOCK);
        Collections.reverse(reordered);
        BatchSocket socket = new BatchSocket("session-a", query -> batch(query, reordered,
                List.of(bar("sz.000001"))));
        BaoStockClient client = client(List.of(socket), new ArrayList<>());

        List<Map<String, String>> rows = client.queryDailyAStock(DAY);

        assertEquals(List.of("query_daily_history_k_AStock", "session-a", DAY.toString()), socket.queries.getFirst().parts());
        assertEquals("98", socket.queries.getFirst().type());
        assertEquals("1.0000000000", rows.getFirst().get("open"));
        assertEquals("-58.658491", rows.getFirst().get("pcfNcfTTM"));
        assertEquals("", rows.getFirst().get("turn"), "缺失换手率不能被客户端改成 0");
        assertEquals("123400", rows.getFirst().get("volume"), "不得再次按手乘 100");
        assertEquals("135740.00", rows.getFirst().get("amount"));
        assertEquals("3", rows.getFirst().get("adjustflag"));
    }

    @Test
    void batchRowCountsNeverCausePaginationIncludingExactlyTwoThousand() {
        // 覆盖 Python 通用 next() 的已知危险边界，以及客户端允许的最大批量大小。
        for (int count : List.of(0, 2000, 2001, 20_000)) {
            List<Map<String, String>> source = new ArrayList<>(count);
            for (int i = 0; i < count; i++) source.add(bar("sz." + String.format("%06d", i)));
            BatchSocket socket = new BatchSocket("session-size", query -> batch(query, STOCK, source));
            assertEquals(count, client(List.of(socket), new ArrayList<>()).queryDailyAStock(DAY).size());
            assertEquals(1, socket.queries.size(), "批量请求没有页码，不能因行数恰好 2000 重发日期");
        }
    }

    @Test
    void etfAcceptsDocumentedOptionalStatusButNeverAddsStockValuations() {
        for (boolean includeStatus : List.of(false, true)) {
            List<String> fields = new ArrayList<>(BAR);
            if (includeStatus) fields.add("isST");
            Map<String, String> source = bar("sh.588000");
            source.put("isST", "1");
            BatchSocket socket = new BatchSocket("session-etf", query -> batch(query, fields, List.of(source)));
            Map<String, String> result = client(List.of(socket), new ArrayList<>()).queryDailyEtf(DAY).getFirst();
            assertEquals("9A", socket.queries.getFirst().type());
            assertEquals(includeStatus, result.containsKey("isST"));
            assertFalse(result.containsKey("peTTM"), "ETF 源不提供个股估值，不能由客户端造出字段");
        }
    }

    @Test
    void etfAlsoAcceptsTheObservedFullEighteenColumnShapeWithoutAssigningStockMeaning() {
        Map<String, String> source = bar("sh.588000");
        source.put("isST", "1");
        source.put("peTTM", "");
        source.put("pbMRQ", "0.000000");
        source.put("psTTM", "2.500000");
        source.put("pcfNcfTTM", "-58.658491");
        BatchSocket socket = new BatchSocket("observed-etf-columns", query -> batch(query, STOCK, List.of(source)));
        Map<String, String> result = client(List.of(socket), new ArrayList<>()).queryDailyEtf(DAY).getFirst();
        assertEquals(source, result, "完整保留18列源字符串供对照，生产ETF语义隔离仍由映射层负责");
        assertEquals("", result.get("peTTM"));
        assertEquals("1", result.get("isST"));
        assertEquals("-58.658491", result.get("pcfNcfTTM"));
    }

    @Test
    void etfRejectsUnknownOrPartialExtraColumnsAndNonNumericAuditValues() {
        List<String> partial = new ArrayList<>(BAR); partial.add("peTTM");
        List<String> unknown = new ArrayList<>(STOCK); unknown.add("unexpectedColumn");
        for (List<String> fields : List.of(partial, unknown)) {
            BatchSocket socket = new BatchSocket("invalid-etf-columns", query -> batch(query, fields,
                    List.of(bar("sh.588000"))));
            assertRejectedOnce(socket, client -> client.queryDailyEtf(DAY));
        }
        Map<String, String> source = bar("sh.588000"); source.put("peTTM", "Infinity");
        BatchSocket socket = new BatchSocket("invalid-etf-audit-value", query -> batch(query, STOCK, List.of(source)));
        assertRejectedOnce(socket, client -> client.queryDailyEtf(DAY));
    }

    @Test
    void factorBatchIsAnEventSetAndSuccessfulEmptyResultIsAllowed() {
        BatchSocket empty = new BatchSocket("empty-events", query -> batch(query, FACTOR, List.of()));
        assertTrue(client(List.of(empty), new ArrayList<>()).queryDailyAdjustFactors(DAY).isEmpty());
        BatchSocket one = new BatchSocket("one-event", query -> batch(query, FACTOR,
                List.of(factor("sh.600000", DAY))));
        Map<String, String> result = client(List.of(one), new ArrayList<>()).queryDailyAdjustFactors(DAY).getFirst();
        assertEquals("9C", one.queries.getFirst().type());
        assertEquals("0.663792", result.get("foreAdjustFactor"));
        assertEquals(DAY.toString(), result.get("dividOperateDate"));
    }

    @Test
    void malformedSchemasAndRowsFailTheWholeBatchWithoutRetrying() {
        List<UnaryOperator<List<String>>> fieldChanges = List.of(
                fields -> fields.subList(0, fields.size() - 1),
                fields -> { List<String> changed = new ArrayList<>(fields); changed.set(1, "date"); return changed; },
                fields -> { List<String> changed = new ArrayList<>(fields); changed.set(1, "unknownCode"); return changed; });
        for (UnaryOperator<List<String>> change : fieldChanges) {
            BatchSocket socket = new BatchSocket("bad-fields", query -> batch(query, change.apply(STOCK),
                    List.of(bar("sz.000001"))));
            assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
        }
        List<String> malformedJson = List.of("", "{", "{}", "{\"record\":null}", "{\"record\":{}}",
                "{\"record\":[[]]}", "{\"record\":[{}]}", "{\"record\":[]} {\"record\":[]}");
        for (String json : malformedJson) {
            BatchSocket socket = new BatchSocket("bad-json", query -> batchJson(query, STOCK, json));
            assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
        }
    }

    @Test
    void schemaMismatchReportsWhitelistedColumnsAndBoundedTableShapeWithoutPayloadDump() {
        List<String> fields = new ArrayList<>(BAR);
        fields.remove("turn");
        fields.add("unexpectedField");
        fields.add("PRIVATE-NON-IDENTIFIER-TEXT".repeat(50));
        Map<String, String> source = bar("sh.588000");
        source.put("unexpectedField", "PRIVATE-CELL-CONTENT");
        BatchSocket socket = new BatchSocket("PRIVATE-SESSION", query -> batch(query, fields, List.of(source)));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(List.of(socket), new ArrayList<>()).queryDailyEtf(DAY));
        String message = failure.getMessage();
        assertTrue(message.contains("missing=[turn]"));
        assertTrue(message.contains("unexpectedField"));
        assertTrue(message.contains("invalidIdentifier(chars="));
        assertTrue(message.contains("rows=1,firstRowColumns=14"));
        assertTrue(message.contains("firstCode=sh.588000"));
        assertTrue(message.contains("firstDate=" + DAY));
        assertFalse(message.contains("PRIVATE-NON-IDENTIFIER-TEXT"));
        assertFalse(message.contains("PRIVATE-CELL-CONTENT"));
        assertFalse(message.contains("PRIVATE-SESSION"));
        assertTrue(message.length() < 3000);
        assertEquals(1, socket.queries.size());
    }

    @Test
    void wrongDatesCodesFlagsAndNumbersCannotSilentlyEnterResults() {
        List<Map<String, String>> mutations = List.of(Map.of("date", DAY.minusDays(1).toString()),
                Map.of("date", "2026-02-30"), Map.of("code", "000001"), Map.of("adjustflag", "2"),
                Map.of("tradestatus", "9"), Map.of("isST", ""), Map.of("amount", "NaN"),
                Map.of("volume", "-1"), Map.of("open", ""), Map.of("pbMRQ", "Infinity"));
        for (Map<String, String> mutation : mutations) {
            Map<String, String> source = bar("sz.000001");
            source.putAll(mutation);
            BatchSocket socket = new BatchSocket("bad-value", query -> batch(query, STOCK, List.of(source)));
            assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
        }
        Map<String, String> badFactor = factor("sh.600000", DAY);
        badFactor.put("backAdjustFactor", "0");
        BatchSocket socket = new BatchSocket("bad-factor", query -> batch(query, FACTOR, List.of(badFactor)));
        assertRejectedOnce(socket, client -> client.queryDailyAdjustFactors(DAY));
    }

    @Test
    void invalidBatchAggregatesAllBadRowsWithBoundedPublicIdentityDiagnostics() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(bar("sz.000001"));
        String privateText = "PRIVATE-TEXT-THAT-MUST-NEVER-BE-LOGGED";
        for (int i = 0; i < 10; i++) {
            Map<String, String> row = bar("sz." + String.format("%06d", i + 2));
            row.put("volume", i == 1 ? privateText : i == 2 ? "NaN" : "");
            row.put("amount", "");
            row.put("tradestatus", "1");
            rows.add(row);
        }
        BatchSocket socket = new BatchSocket("diagnostic-private-session", query -> batch(query, STOCK, rows));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(List.of(socket), new ArrayList<>()).queryDailyAStock(DAY));
        String message = failure.getMessage();
        assertTrue(message.contains("总行数=11"));
        assertTrue(message.contains("失败行数=10"));
        assertTrue(message.contains("code=sz.000002"));
        assertTrue(message.contains("tradestatus=1"));
        assertTrue(message.contains("chars=0,empty=true,value=empty"));
        assertTrue(message.contains("value=NaN"));
        assertTrue(message.contains("volume:empty=8"));
        assertTrue(message.contains("volume:notNumeric=2"));
        assertTrue(message.contains("amount:empty=10"), "同时统计首错字段之后的其它数值异常");
        assertFalse(message.contains("code=sz.000005"), "仅显示前三条安全示例");
        assertFalse(message.contains(privateText));
        assertFalse(message.contains("diagnostic-private-session"));
        assertTrue(message.length() < 4000);
        assertEquals(1, socket.queries.size());
    }

    @Test
    void observedSuspendedVolumeAndAmountPairRemainMissingWithoutInventingZero() {
        Map<String, String> suspended = bar("sh.600185");
        suspended.put("tradestatus", "0");
        suspended.put("volume", "");
        suspended.put("amount", "");
        BatchSocket batchSocket = new BatchSocket("suspension-batch", query -> batch(query, STOCK, List.of(suspended)));
        Map<String, String> batchRow = client(List.of(batchSocket), new ArrayList<>()).queryDailyAStock(DAY).getFirst();
        assertEquals("", batchRow.get("volume"));
        assertEquals("", batchRow.get("amount"));
        assertEquals("0", batchRow.get("tradestatus"));
        assertEquals("1.0000000000", batchRow.get("open"));
        BatchSocket historySocket = new BatchSocket("suspension-history", query -> historyPage(query, List.of(suspended)));
        Map<String, String> historyRow = client(List.of(historySocket), new ArrayList<>())
                .queryHistoryVerified("sh.600185", DAY, DAY, "3").getFirst();
        assertEquals("", historyRow.get("volume"));
        assertEquals("", historyRow.get("amount"));
    }

    @Test
    void missingTradeValuesRequireSuspensionAndBothColumnsWhilePricesStayStrict() {
        List<Map<String, String>> cases = List.of(
                Map.of("tradestatus", "1", "volume", "", "amount", ""),
                Map.of("tradestatus", "0", "volume", ""),
                Map.of("tradestatus", "0", "amount", ""),
                Map.of("tradestatus", "0", "volume", "", "amount", "", "open", ""),
                Map.of("tradestatus", "0", "volume", " ", "amount", ""));
        for (Map<String, String> change : cases) {
            Map<String, String> source = bar("sh.600185"); source.putAll(change);
            BatchSocket socket = new BatchSocket("invalid-suspension", query -> batch(query, STOCK, List.of(source)));
            assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
        }
    }

    @Test
    void duplicatedSecurityAndDateIsNotSilentlyDeduplicated() {
        Map<String, String> source = bar("sz.000001");
        BatchSocket socket = new BatchSocket("duplicate", query -> batch(query, STOCK, List.of(source, source)));
        assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
    }

    @Test
    void oversizedBatchFailsInsteadOfReturningATruncatedPrefix() {
        List<Map<String, String>> source = Collections.nCopies(20_001, bar("sz.000001"));
        BatchSocket socket = new BatchSocket("too-many", query -> batch(query, STOCK, source));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(List.of(socket), new ArrayList<>()).queryDailyAStock(DAY));
        assertTrue(failure.getMessage().contains("安全上限"));
        assertEquals(1, socket.queries.size());
    }

    @Test
    void responseEnvelopeRejectsWrongMethodSessionTypeAndIncompleteLayout() {
        for (int defect = 0; defect < 4; defect++) {
            int current = defect;
            BatchSocket socket = new BatchSocket("expected", query -> {
                List<String> parts = new ArrayList<>(List.of("0", "success", query.parts().getFirst(),
                        query.parts().get(1), "{\"record\":[]}", String.join(",", STOCK)));
                if (current == 0) parts.set(2, "query_daily_history_k_ETF");
                if (current == 1) parts.set(3, "expired-session");
                if (current == 2) parts.removeLast();
                return frame(current == 3 ? "9B" : "99", String.join(SEP, parts), true);
            });
            assertRejectedOnce(socket, client -> client.queryDailyAStock(DAY));
        }
    }

    @Test
    void unknownResponseTailDiagnosticsAreBoundedAndNeverExposePayloadOrSession() {
        String secret = "PRIVATE-SESSION-OR-PAYLOAD";
        BatchSocket socket = new BatchSocket(secret, query -> frame("99", String.join(SEP,
                "0", "success", query.parts().getFirst(), query.parts().get(1), "{\"record\":[]}",
                String.join(",", STOCK), secret.repeat(1000)), true));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(List.of(socket), new ArrayList<>()).queryDailyAStock(DAY));
        assertTrue(failure.getMessage().contains("index=6"));
        assertTrue(failure.getMessage().contains("uint32=false"));
        assertTrue(failure.getMessage().length() < 700);
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.getMessage().contains("record"));
        assertEquals(1, socket.queries.size());
    }

    @Test
    void verifiedResponseCrcAllowsTheObservedCalendarTrailer() {
        BatchSocket socket = new BatchSocket("crc-private-session", query -> {
            String body = String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                    query.parts().get(2), query.parts().get(3), json(List.of("calendar_date", "is_trading_day"),
                    calendarRows(DAY, 1)), query.parts().get(4), query.parts().get(5), "calendar_date,is_trading_day");
            String header = "00.9.40" + SEP + "34" + SEP
                    + String.format("%010d", body.getBytes(StandardCharsets.UTF_8).length);
            CRC32 crc = new CRC32(); crc.update((header + body).getBytes(StandardCharsets.UTF_8));
            return new ByteArrayInputStream((header + body + SEP + crc.getValue() + "<![CDATA[]]>\n")
                    .getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(Map.of(DAY, false), client(List.of(socket), new ArrayList<>()).queryTradeCalendar(DAY, DAY));
        assertEquals(1, socket.queries.size());
    }

    @Test
    void wrongCrcChangedBodyOrAdditionalTailCannotBypassResponseValidation() {
        for (int defect = 0; defect < 6; defect++) {
            int current = defect;
            BatchSocket socket = new BatchSocket("crc-private-session", query -> {
                String body = String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                        query.parts().get(2), query.parts().get(3), json(List.of("calendar_date", "is_trading_day"),
                        calendarRows(DAY, 1)), query.parts().get(4), query.parts().get(5), "calendar_date,is_trading_day");
                String header = "00.9.40" + SEP + "34" + SEP
                        + String.format("%010d", body.getBytes(StandardCharsets.UTF_8).length);
                CRC32 crc = new CRC32(); crc.update((header + body).getBytes(StandardCharsets.UTF_8));
                String checksum = String.valueOf(crc.getValue());
                if (current == 0) checksum = String.valueOf(crc.getValue() ^ 1);
                if (current == 1) body = body.replace("\"0\"]", "\"1\"]");
                if (current == 2) checksum += SEP + "UNEXPECTED-SECOND-TAIL";
                if (current == 3) checksum = "-1";
                if (current == 4) checksum = "4294967296";
                if (current == 5) checksum = SEP + checksum;
                return new ByteArrayInputStream((header + body + SEP + checksum + "<![CDATA[]]>\n")
                        .getBytes(StandardCharsets.UTF_8));
            });
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client(List.of(socket), new ArrayList<>()).queryTradeCalendar(DAY, DAY));
            assertTrue(failure.getMessage().contains("未知尾段"));
            assertFalse(failure.getMessage().contains("crc-private-session"));
            assertFalse(failure.getMessage().contains("UNEXPECTED-SECOND-TAIL"));
            assertEquals(1, socket.queries.size(), "完整但 CRC 不匹配的业务响应不应偷偷重试");
        }
    }

    @Test
    void incompleteNetworkFrameReconnectsAndRewritesSessionWithoutChangingDate() {
        BatchSocket broken = new BatchSocket("session-old", query -> {
            byte[] complete = batch(query, STOCK, List.of(bar("sz.000001"))).readAllBytes();
            return new ByteArrayInputStream(Arrays.copyOf(complete, complete.length - 8));
        });
        BatchSocket recovered = new BatchSocket("session-new", query -> batch(query, STOCK,
                List.of(bar("sz.000001"))));
        List<Long> waits = new ArrayList<>();
        assertEquals(1, client(List.of(broken, recovered), waits).queryDailyAStock(DAY).size());
        assertTrue(broken.closed);
        assertEquals(List.of(1000L), waits);
        assertEquals("session-new", recovered.queries.getFirst().parts().get(1));
        assertEquals(broken.queries.getFirst().parts().get(2), recovered.queries.getFirst().parts().get(2));
    }

    @Test
    void notLoggedInBusinessCodeUsesTheSameRecoveryBudget() {
        BatchSocket expired = new BatchSocket("old", query -> frame("99", "10001001\1用户未登录", true));
        BatchSocket recovered = new BatchSocket("new", query -> batch(query, FACTOR, List.of()));
        List<Long> waits = new ArrayList<>();
        assertTrue(client(List.of(expired, recovered), waits).queryDailyAdjustFactors(DAY).isEmpty());
        assertEquals(1, waits.size());
        assertEquals("new", recovered.queries.getFirst().parts().get(1));
    }

    @Test
    void permissionAndDateErrorsKeepTheExactCodeAndDoNotRetry() {
        for (String code : List.of("10001006", "10004013", "10001011")) {
            BatchSocket socket = new BatchSocket("denied", query -> frame("99", code + SEP + "源拒绝请求", true));
            List<Long> waits = new ArrayList<>();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client(List.of(socket), waits).queryDailyAStock(DAY));
            assertTrue(failure.getMessage().contains(code));
            assertEquals(1, socket.queries.size());
            assertTrue(waits.isEmpty());
        }
    }

    @Test
    void repeatedTruncatedResponsesStopAfterThreeAttempts() {
        List<BatchSocket> sockets = List.of(new BatchSocket("a", query -> InputStream.nullInputStream()),
                new BatchSocket("b", query -> InputStream.nullInputStream()),
                new BatchSocket("c", query -> InputStream.nullInputStream()));
        List<Long> waits = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> client(sockets, waits).queryDailyEtf(DAY));
        assertEquals(List.of(1000L, 2000L), waits);
        assertTrue(sockets.stream().allMatch(socket -> socket.closed && socket.queries.size() == 1));
    }

    @Test
    void malformedHeaderAndInvalidCompressedPayloadAreBoundedTransportFailures() {
        for (boolean badHeader : List.of(true, false)) {
            List<BatchSocket> sockets = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                sockets.add(new BatchSocket("broken-" + i, query -> {
                    // 声明长度不是数字，或响应类型说 zlib 而实际正文不是压缩流；都不能继续复用连接。
                    String header = "00.9.40\1" + "99\1" + (badHeader ? "xxxxxxxxxx" : "0000000003");
                    return new ByteArrayInputStream((header + "bad<![CDATA[]]>\n").getBytes(StandardCharsets.UTF_8));
                }));
            }
            List<Long> waits = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> client(sockets, waits).queryDailyAStock(DAY));
            assertEquals(List.of(1000L, 2000L), waits);
            assertTrue(sockets.stream().allMatch(socket -> socket.closed));
        }
    }

    @Test
    void securityFactorQueryUsesNormalPagingAndRetriesOnlyTheFailedPage() {
        LocalDate from = LocalDate.of(1990, 1, 1);
        LocalDate to = from.plusDays(2000);
        List<Map<String, String>> firstPage = new ArrayList<>();
        for (int i = 0; i < 2000; i++) firstPage.add(factor("sh.600000", from.plusDays(i)));
        BatchSocket first = new BatchSocket("first-page", query -> query.parts().get(2).equals("1")
                ? factorPage(query, firstPage) : InputStream.nullInputStream());
        BatchSocket recovered = new BatchSocket("recovered-page", query -> factorPage(query,
                List.of(factor("sh.600000", to))));
        List<Map<String, String>> result = client(List.of(first, recovered), new ArrayList<>())
                .queryAdjustFactors("SH.600000", from, to);
        assertEquals(2001, result.size());
        assertEquals(List.of("1", "2"), first.queries.stream().map(query -> query.parts().get(2)).toList());
        assertEquals("2", recovered.queries.getFirst().parts().get(2));
        assertEquals("recovered-page", recovered.queries.getFirst().parts().get(1));
        assertEquals("15", recovered.queries.getFirst().type());
    }

    @Test
    void securityFactorQueryRejectsWrongMetadataAndCrossPageDuplicates() {
        LocalDate from = LocalDate.of(1990, 1, 1);
        LocalDate to = from.plusDays(2000);
        BatchSocket metadata = new BatchSocket("wrong-metadata", query -> {
            List<String> changed = new ArrayList<>(query.parts());
            changed.set(4, "sh.600001");
            return factorPage(new Query(query.type(), changed), List.of());
        });
        assertRejectedOnce(metadata, client -> client.queryAdjustFactors("sh.600000", from, to));
        List<Map<String, String>> firstPage = new ArrayList<>();
        for (int i = 0; i < 2000; i++) firstPage.add(factor("sh.600000", from.plusDays(i)));
        BatchSocket repeated = new BatchSocket("repeated-page", query -> factorPage(query,
                query.parts().get(2).equals("1") ? firstPage : List.of(firstPage.getFirst())));
        assertThrows(IllegalStateException.class,
                () -> client(List.of(repeated), new ArrayList<>()).queryAdjustFactors("sh.600000", from, to));
        assertEquals(2, repeated.queries.size());
    }

    @Test
    void invalidInputFailsBeforeCreatingAnySocket() {
        BaoStockClient client = new BaoStockClient(MAPPER, 100, 100,
                () -> { throw new AssertionError("无效参数不应创建 Socket"); }, millis -> {}, bound -> 0);
        assertThrows(NullPointerException.class, () -> client.queryDailyAStock(null));
        assertThrows(IllegalArgumentException.class, () -> client.queryAdjustFactors("600000", DAY, DAY));
        assertThrows(IllegalArgumentException.class, () -> client.queryAdjustFactors("sh.600000", DAY, DAY.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> client.queryTradeCalendar(DAY, DAY.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> client.queryHistoryVerified("sh.600000", DAY, DAY, "4"));
    }

    @Test
    void calendarRequiresEveryNaturalDayAndPaginatesAtExactlyTwoThousand() {
        for (int count : List.of(1, 2000, 2001)) {
            LocalDate from = DAY.minusDays(count - 1L);
            List<Map<String, String>> rows = calendarRows(from, count);
            BatchSocket socket = new BatchSocket("calendar", query -> {
                int offset = (Integer.parseInt(query.parts().get(2)) - 1) * 2000;
                // 列序可变、同一页的行序可变，最终输出仍必须按日期排序。
                List<Map<String, String>> page = new ArrayList<>(rows.subList(offset, Math.min(offset + 2000, count)));
                Collections.reverse(page);
                return calendarPage(query, List.of("is_trading_day", "calendar_date"), page);
            });
            Map<LocalDate, Boolean> result = client(List.of(socket), new ArrayList<>()).queryTradeCalendar(from, DAY);
            assertEquals(count, result.size());
            assertEquals(from, result.keySet().iterator().next());
            assertEquals(count / 2000 + 1, socket.queries.size());
            assertEquals(false, result.get(from));
            if (count > 1) assertEquals(true, result.get(from.plusDays(1)));
            assertThrows(UnsupportedOperationException.class, () -> result.put(DAY.plusDays(1), false));
        }
    }

    @Test
    void calendarNeverInfersMissingRecordsAreHolidays() {
        List<Map<String, String>> complete = calendarRows(DAY.minusDays(2), 3);
        for (List<Map<String, String>> missing : List.of(List.<Map<String, String>>of(),
                complete.subList(1, 3), List.of(complete.getFirst(), complete.getLast()), complete.subList(0, 2))) {
            BatchSocket socket = new BatchSocket("missing-days", query -> calendarPage(query,
                    List.of("calendar_date", "is_trading_day"), missing));
            assertRejectedOnce(socket, client -> client.queryTradeCalendar(DAY.minusDays(2), DAY));
        }
        BatchSocket invalidFields = new BatchSocket("missing-fields", query -> calendarPage(query,
                List.of("calendar_date", "calendar_date"), complete));
        assertRejectedOnce(invalidFields, client -> client.queryTradeCalendar(DAY.minusDays(2), DAY));
    }

    @Test
    void calendarRejectsBadFlagsDatesMetadataAndMalformedEmptyBodies() {
        for (Map<String, String> bad : List.of(Map.of("calendar_date", DAY.toString(), "is_trading_day", ""),
                Map.of("calendar_date", DAY.toString(), "is_trading_day", "2"),
                Map.of("calendar_date", DAY.toString(), "is_trading_day", "false"),
                Map.of("calendar_date", "2026-02-30", "is_trading_day", "1"),
                Map.of("calendar_date", DAY.minusDays(1).toString(), "is_trading_day", "1"))) {
            BatchSocket socket = new BatchSocket("bad-calendar", query -> calendarPage(query,
                    List.of("calendar_date", "is_trading_day"), List.of(bad)));
            assertRejectedOnce(socket, client -> client.queryTradeCalendar(DAY, DAY));
        }
        for (String json : List.of("", "{}", "{\"record\":null}", "{\"record\":[[null,1]]}",
                "{\"record\":[]} {\"record\":[]}")) {
            BatchSocket socket = new BatchSocket("bad-calendar-json", query -> frame("34", String.join(SEP,
                    "0", "success", query.parts().getFirst(), query.parts().get(1), query.parts().get(2),
                    query.parts().get(3), json, query.parts().get(4), query.parts().get(5),
                    "calendar_date,is_trading_day"), false));
            assertRejectedOnce(socket, client -> client.queryTradeCalendar(DAY, DAY));
        }
        BatchSocket metadata = new BatchSocket("wrong-calendar-metadata", query -> {
            List<String> changed = new ArrayList<>(query.parts());
            changed.set(2, "2");
            return calendarPage(new Query(query.type(), changed), List.of("calendar_date", "is_trading_day"),
                    calendarRows(DAY, 1));
        });
        assertRejectedOnce(metadata, client -> client.queryTradeCalendar(DAY, DAY));
    }

    @Test
    void calendarCrossPageDuplicatesFailAndTransportRetriesOnlyTheFailedPage() {
        LocalDate from = DAY.minusDays(2000);
        List<Map<String, String>> rows = calendarRows(from, 2001);
        BatchSocket duplicate = new BatchSocket("duplicate-calendar", query -> calendarPage(query,
                List.of("calendar_date", "is_trading_day"), query.parts().get(2).equals("1")
                        ? rows.subList(0, 2000) : List.of(rows.getFirst())));
        assertThrows(IllegalStateException.class,
                () -> client(List.of(duplicate), new ArrayList<>()).queryTradeCalendar(from, DAY));
        assertEquals(2, duplicate.queries.size());
        BatchSocket first = new BatchSocket("calendar-old", query -> query.parts().get(2).equals("1")
                ? calendarPage(query, List.of("calendar_date", "is_trading_day"), rows.subList(0, 2000))
                : InputStream.nullInputStream());
        BatchSocket recovered = new BatchSocket("calendar-new", query -> calendarPage(query,
                List.of("calendar_date", "is_trading_day"), rows.subList(2000, 2001)));
        assertEquals(2001, client(List.of(first, recovered), new ArrayList<>()).queryTradeCalendar(from, DAY).size());
        assertEquals("2", recovered.queries.getFirst().parts().get(2));
        assertEquals("calendar-new", recovered.queries.getFirst().parts().get(1));
    }

    @Test
    void legacyTradeDateListDelegatesToVerifiedCalendarAndReturnsOnlyTradingDates() {
        BatchSocket socket = new BatchSocket("legacy-calendar", query -> calendarPage(query,
                List.of("calendar_date", "is_trading_day"), calendarRows(DAY.minusDays(2), 3)));
        assertEquals(List.of(DAY.minusDays(1)),
                client(List.of(socket), new ArrayList<>()).queryTradeDates(DAY.minusDays(2), DAY));
    }

    @Test
    void verifiedHistoryPreservesRequestedAdjustmentAndOnlyAcceptsExplicitEmptyTables() {
        for (String code : List.of("sh.600000", "sh.588000")) {
            for (String flag : code.contains("588") ? List.of("3") : List.of("1", "2", "3")) {
                Map<String, String> source = bar(code);
                source.put("adjustflag", flag);
                BatchSocket socket = new BatchSocket("verified-history", query -> historyPage(query, List.of(source)));
                Map<String, String> result = client(List.of(socket), new ArrayList<>())
                        .queryHistoryVerified(code, DAY, DAY, flag).getFirst();
                assertEquals(flag, result.get("adjustflag"));
                assertFalse(result.containsKey("code"), "历史请求不含 code 列，验证时不能改变返回字段");
                assertEquals(!code.contains("588"), result.containsKey("peTTM"));
            }
        }
        BatchSocket empty = new BatchSocket("verified-empty", query -> historyPage(query, List.of()));
        assertTrue(client(List.of(empty), new ArrayList<>()).queryHistoryVerified("sh.600000", DAY, DAY, "2").isEmpty());
        for (String json : List.of("", "{}", "{\"record\":null}", "{\"record\":[[]]}")) {
            BatchSocket broken = new BatchSocket("verified-broken", query -> historyPageJson(query,
                    query.parts().get(5), json));
            assertRejectedOnce(broken, client -> client.queryHistoryVerified("sh.600000", DAY, DAY, "2"));
        }
    }

    @Test
    void verifiedHistoryRejectsFalseCoverageFromBadFieldsNumbersDatesAndAdjustment() {
        for (Map<String, String> change : List.of(Map.of("adjustflag", "3"), Map.of("date", DAY.minusDays(1).toString()),
                Map.of("close", "NaN"), Map.of("tradestatus", ""), Map.of("volume", "-1"))) {
            Map<String, String> row = bar("sh.600000");
            row.put("adjustflag", "2"); row.putAll(change);
            BatchSocket socket = new BatchSocket("invalid-history", query -> historyPage(query, List.of(row)));
            assertRejectedOnce(socket, client -> client.queryHistoryVerified("sh.600000", DAY, DAY, "2"));
        }
        BatchSocket missingFields = new BatchSocket("history-no-fields", query -> historyPageJson(query,
                "date,close", "{\"record\":[]}"));
        assertRejectedOnce(missingFields, client -> client.queryHistoryVerified("sh.600000", DAY, DAY, "2"));
        BatchSocket wrongMetadata = new BatchSocket("history-wrong-code", query -> {
            List<String> changed = new ArrayList<>(query.parts()); changed.set(4, "sh.600001");
            return historyPage(new Query(query.type(), changed), List.of());
        });
        assertRejectedOnce(wrongMetadata, client -> client.queryHistoryVerified("sh.600000", DAY, DAY, "2"));
    }

    @Test
    void verifiedStockListValidatesEmptyAndNonemptyRecordsBeforeTheyCanBeCached() {
        Map<String, String> row = Map.of("code", "sh.600000", "tradeStatus", "1", "code_name", "浦发银行");
        BatchSocket valid = new BatchSocket("verified-list", query -> stockListPage(query, List.of(row)));
        assertEquals(row, client(List.of(valid), new ArrayList<>()).queryAllStockVerified(DAY).getFirst());
        BatchSocket empty = new BatchSocket("empty-list", query -> stockListPage(query, List.of()));
        assertTrue(client(List.of(empty), new ArrayList<>()).queryAllStockVerified(DAY).isEmpty());
        for (String json : List.of("", "{}", "{\"record\":null}", "{\"record\":[[]]}")) {
            BatchSocket broken = new BatchSocket("bad-list", query -> frame("36", String.join(SEP,
                    "0", "success", query.parts().getFirst(), query.parts().get(1), query.parts().get(2),
                    query.parts().get(3), json, query.parts().get(4), "code,tradeStatus,code_name"), false));
            assertRejectedOnce(broken, client -> client.queryAllStockVerified(DAY));
        }
        for (Map<String, String> bad : List.of(Map.of("code", "600000", "tradeStatus", "1", "code_name", "浦发银行"),
                Map.of("code", "sh.600000", "tradeStatus", "", "code_name", "浦发银行"))) {
            BatchSocket broken = new BatchSocket("bad-list-value", query -> stockListPage(query, List.of(bad)));
            assertRejectedOnce(broken, client -> client.queryAllStockVerified(DAY));
        }
    }

    @Test
    void stockListDiagnosticsAggregateReasonsWithoutDisclosingNamesOrInvalidText() {
        List<Map<String, String>> rows = List.of(
                Map.of("code", "sh.600000", "tradeStatus", "1", "code_name", ""),
                Map.of("code", "sh.688001", "tradeStatus", "", "code_name", "PRIVATE-NAME"),
                Map.of("code", "PRIVATE-CODE", "tradeStatus", "PRIVATE-STATUS", "code_name", ""),
                Map.of("code", "sh.600000", "tradeStatus", "1", "code_name", "PRIVATE-NAME"));
        BatchSocket socket = new BatchSocket("PRIVATE-SESSION", query -> stockListPage(query, rows));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(List.of(socket), new ArrayList<>()).queryAllStockVerified(DAY));
        String message = failure.getMessage();
        assertTrue(message.contains("pageRows=4"));
        assertTrue(message.contains("invalidRows=4"));
        assertTrue(message.contains("supportedInvalidRows=2"));
        assertTrue(message.contains("emptyName=2"));
        assertTrue(message.contains("invalidTradeStatus=2"));
        assertTrue(message.contains("invalidCode=1"));
        assertTrue(message.contains("duplicateCode=1"));
        assertTrue(message.contains("code=sh.600000"));
        assertTrue(message.contains("nameEmpty=true,nameLength=0"));
        assertFalse(message.contains("PRIVATE-"));
        assertTrue(message.length() < 3000);
        assertEquals(1, socket.queries.size());
    }

    @Test
    void stockListSnapshotKeepsMissingDisplayNameWhileProductionMetadataRejectsIt() {
        Map<String, String> nameless = Map.of("code", "sh.512280", "tradeStatus", "1", "code_name", "");
        BatchSocket audit = new BatchSocket("historical-list", query -> stockListPage(query, List.of(nameless)));
        Map<String, String> result = client(List.of(audit), new ArrayList<>()).queryAllStockSnapshot(DAY).getFirst();
        assertEquals(nameless, result, "审计只保留原始缺名，不用代码或别日名称伪造历史显示名");
        BatchSocket production = new BatchSocket("production-list", query -> stockListPage(query, List.of(nameless)));
        assertRejectedOnce(production, client -> client.queryAllStockVerified(DAY));
    }

    @Test
    void stockListSnapshotNeverRelaxesIdentityStatusDuplicatesOrRequiredColumns() {
        for (boolean audit : List.of(false, true)) {
            for (Map<String, String> bad : List.of(
                    Map.of("code", "512280", "tradeStatus", "1", "code_name", ""),
                    Map.of("code", "sh.512280", "tradeStatus", "", "code_name", ""))) {
                BatchSocket socket = new BatchSocket("bad-list-mode", query -> stockListPage(query, List.of(bad)));
                assertRejectedOnce(socket, client -> {
                    if (audit) client.queryAllStockSnapshot(DAY); else client.queryAllStockVerified(DAY);
                });
            }
        }
        Map<String, String> nameless = Map.of("code", "sh.512280", "tradeStatus", "1", "code_name", "");
        BatchSocket duplicate = new BatchSocket("duplicate-audit-list", query -> stockListPage(query, List.of(nameless, nameless)));
        assertRejectedOnce(duplicate, client -> client.queryAllStockSnapshot(DAY));
        BatchSocket missingColumn = new BatchSocket("incomplete-audit-list", query -> frame("36", String.join(SEP,
                "0", "success", query.parts().getFirst(), query.parts().get(1), query.parts().get(2),
                query.parts().get(3), "{\"record\":[]}", query.parts().get(4), "code,tradeStatus"), false));
        assertRejectedOnce(missingColumn, client -> client.queryAllStockSnapshot(DAY));
    }

    /** 固定 Socket 预算并禁用真实休眠；任何额外连接都会立即使测试失败。 */
    private BaoStockClient client(List<BatchSocket> sockets, List<Long> waits) {
        AtomicInteger index = new AtomicInteger();
        return new BaoStockClient(MAPPER, 100, 100, () -> {
            int current = index.getAndIncrement();
            if (current >= sockets.size()) throw new AssertionError("超出预期的连接/重试");
            return sockets.get(current);
        }, waits::add, bound -> 0);
    }

    private void assertRejectedOnce(BatchSocket socket, java.util.function.Consumer<BaoStockClient> operation) {
        List<Long> waits = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> operation.accept(client(List.of(socket), waits)));
        assertEquals(1, socket.queries.size());
        assertTrue(waits.isEmpty(), "完整但不合法的数据不能用网络重试掩盖");
    }

    /** 构造具有完整原始口径的 A 股样本；同一基础样本可按 ETF 字段子集序列化。 */
    private static Map<String, String> bar(String code) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String field : STOCK) row.put(field, "1");
        row.put("date", DAY.toString()); row.put("code", code); row.put("open", "1.0000000000");
        row.put("volume", "123400"); row.put("amount", "135740.00"); row.put("adjustflag", "3");
        row.put("turn", ""); row.put("isST", "0"); row.put("pcfNcfTTM", "-58.658491");
        return row;
    }

    private static Map<String, String> factor(String code, LocalDate date) {
        return new LinkedHashMap<>(Map.of("code", code, "dividOperateDate", date.toString(),
                "foreAdjustFactor", "0.663792", "backAdjustFactor", "6.295967", "adjustFactor", "6.295967"));
    }

    private static InputStream batch(Query query, List<String> fields, List<Map<String, String>> rows) throws IOException {
        return batchJson(query, fields, json(fields, rows));
    }

    private static InputStream batchJson(Query query, List<String> fields, String json) throws IOException {
        String responseType = switch (query.type()) { case "98" -> "99"; case "9A" -> "9B"; case "9C" -> "9D";
            default -> throw new AssertionError("未预期的请求类型 " + query.type()); };
        return frame(responseType, String.join(SEP, "0", "success", query.parts().getFirst(),
                query.parts().get(1), json, String.join(",", fields)), true);
    }

    private static InputStream factorPage(Query query, List<Map<String, String>> rows) throws IOException {
        return frame("16", String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                query.parts().get(2), query.parts().get(3), json(FACTOR, rows), query.parts().get(4),
                query.parts().get(5), query.parts().get(6), String.join(",", FACTOR)), false);
    }

    /** 日历包含自然日而非仅交易日；交替 0/1 是固定测试输入，不代表真实市场开闭市日期。 */
    private static List<Map<String, String>> calendarRows(LocalDate from, int count) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(Map.of("calendar_date", from.plusDays(i).toString(),
                "is_trading_day", i % 2 == 0 ? "0" : "1"));
        return rows;
    }

    private static InputStream calendarPage(Query query, List<String> fields,
                                            List<Map<String, String>> rows) throws IOException {
        return frame("34", String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                query.parts().get(2), query.parts().get(3), json(fields, rows), query.parts().get(4),
                query.parts().get(5), String.join(",", fields)), false);
    }

    private static InputStream historyPage(Query query, List<Map<String, String>> rows) throws IOException {
        return historyPageJson(query, query.parts().get(5), json(List.of(query.parts().get(5).split(",")), rows));
    }

    private static InputStream historyPageJson(Query query, String fields, String json) throws IOException {
        return frame("96", String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                query.parts().get(2), query.parts().get(3), json, query.parts().get(4), fields,
                query.parts().get(6), query.parts().get(7), query.parts().get(8), query.parts().get(9)), true);
    }

    private static InputStream stockListPage(Query query, List<Map<String, String>> rows) throws IOException {
        List<String> fields = List.of("code", "tradeStatus", "code_name");
        return frame("36", String.join(SEP, "0", "success", query.parts().getFirst(), query.parts().get(1),
                query.parts().get(2), query.parts().get(3), json(fields, rows), query.parts().get(4),
                String.join(",", fields)), false);
    }

    private static String json(List<String> fields, List<Map<String, String>> rows) throws IOException {
        return MAPPER.writeValueAsString(Map.of("record", rows.stream()
                .map(row -> fields.stream().map(field -> row.getOrDefault(field, "")).toList()).toList()));
    }

    /** 完整响应帧使用真实压缩体长度，刻意与字符串长度区分，覆盖包含中文错误的情形。 */
    private static InputStream frame(String type, String body, boolean compressed) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (compressed) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) { zip.write(payload); }
            payload = out.toByteArray();
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(("00.9.40" + SEP + type + SEP + String.format("%010d", payload.length)).getBytes(StandardCharsets.UTF_8));
        frame.write(payload);
        frame.write("<![CDATA[]]>\n".getBytes(StandardCharsets.UTF_8));
        return new ByteArrayInputStream(frame.toByteArray());
    }

    private record Query(String type, List<String> parts) { }

    @FunctionalInterface
    private interface Script { InputStream reply(Query query) throws IOException; }

    /** 每个连接拥有不同会话；登录和查询均验证真实请求 CRC，但不建立任何网络连接。 */
    private static final class BatchSocket extends Socket {
        private final String token;
        private final Script script;
        private final List<Query> queries = new ArrayList<>();
        private InputStream response = InputStream.nullInputStream();
        private boolean closed;

        private BatchSocket(String token, Script script) { this.token = token; this.script = script; }
        @Override public void connect(SocketAddress endpoint, int timeout) { }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public InputStream getInputStream() { return response; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { throw new UnsupportedOperationException(); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    String packet = new String(bytes, offset, length, StandardCharsets.UTF_8);
                    int crcSeparator = packet.lastIndexOf(SEP);
                    CRC32 crc = new CRC32();
                    crc.update(packet.substring(0, crcSeparator).getBytes(StandardCharsets.UTF_8));
                    assertEquals(Long.toString(crc.getValue()), packet.substring(crcSeparator + 1).strip());
                    Query query = new Query(packet.substring(8, 10),
                            List.of(packet.substring(21, crcSeparator).split(SEP, -1)));
                    if (query.parts().getFirst().equals("login")) {
                        assertEquals("anonymous", query.parts().get(1));
                        response = frame("01", String.join(SEP, "0", "success", "login", token), false);
                    } else {
                        queries.add(query);
                        response = script.reply(query);
                    }
                }
            };
        }
    }
}
