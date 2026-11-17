package com.li.stock.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 交易日历单页严格解码器。只接受上游明确给出的日期和交易标记，不推算周末或节假日。
 * 本层验证单页格式；调用方负责跨页唯一性和整个请求区间的逐日完整性，全部通过后才可持久化。
 */
final class BaoStockTradeCalendarValidator {
    private static final Set<String> FIELDS = Set.of("calendar_date", "is_trading_day");

    private BaoStockTradeCalendarValidator() { }

    /** 字段允许调换顺序，禁止缺列、重复列、未知列；空表只可作为分页结束标记而非休市证据。 */
    static Map<LocalDate, Boolean> decode(ObjectMapper mapper, String json, String fieldNames,
                                           LocalDate from, LocalDate to, int pageSize) {
        if (json == null || json.isBlank() || fieldNames == null || fieldNames.isBlank()) {
            throw invalid("JSON 或字段列表缺失");
        }
        String[] fields = Arrays.stream(fieldNames.strip().split(",", -1)).map(String::strip).toArray(String[]::new);
        if (fields.length != 2 || !Set.copyOf(Arrays.asList(fields)).equals(FIELDS)) {
            throw invalid("字段集合不是 calendar_date/is_trading_day");
        }
        JsonNode records;
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null || !root.isObject() || !root.path("record").isArray()) {
                throw invalid("响应必须包含完整 record 数组");
            }
            records = root.get("record");
        } catch (IOException ex) {
            throw new IllegalStateException("BaoStock 日历 JSON 不完整或无法解析", ex);
        }
        if (records.size() > pageSize) throw invalid("单页记录数超过请求条数");
        Map<LocalDate, Boolean> result = new LinkedHashMap<>();
        int dateIndex = fields[0].equals("calendar_date") ? 0 : 1;
        int flagIndex = 1 - dateIndex;
        for (JsonNode row : records) {
            if (!row.isArray() || row.size() != 2 || !row.get(dateIndex).isTextual()) {
                throw invalid("记录列数或日期类型不正确");
            }
            String dateText = row.get(dateIndex).asText();
            LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException ex) {
                throw invalid("日期格式错误");
            }
            if (!date.toString().equals(dateText) || date.isBefore(from) || date.isAfter(to)) {
                throw invalid("日期不在请求范围内");
            }
            JsonNode cell = row.get(flagIndex);
            String flag = cell.asText();
            if (!(cell.isTextual() || cell.isIntegralNumber()) || !("0".equals(flag) || "1".equals(flag))) {
                throw invalid("is_trading_day 必须明确为 0 或 1");
            }
            if (result.putIfAbsent(date, "1".equals(flag)) != null) {
                throw invalid("同页日期重复");
            }
        }
        return result;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("BaoStock 日历数据契约校验失败：" + detail);
    }
}
