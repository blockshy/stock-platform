package com.li.stock.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BaoStock 新批量接口的数据契约校验器，仅解码与验证，不做填零、复权计算或字段单位转换。
 * 完整成功空表与残缺响应必须区分：任何字段缺失、列数不齐或日期串台都使整批失败，
 * 由上层决定重试/隔离，禁止将“解析到的一部分数据”误当作完整日更写入数据库。
 */
final class BaoStockBatchValidator {
    /** 对应官方客户端一次批量设置的容纳上限；这是本客户端保护线，不宣称服务端无限量。 */
    private static final int MAX_BATCH_ROWS = 20_000;
    private static final Set<String> BAR_FIELDS = Set.of("date", "code", "open", "high", "low", "close",
            "preclose", "volume", "amount", "adjustflag", "turn", "tradestatus", "pctChg");
    private static final Set<String> VALUE_FIELDS = Set.of("peTTM", "pbMRQ", "psTTM", "pcfNcfTTM", "isST");
    private static final Set<String> FACTOR_FIELDS = Set.of("code", "dividOperateDate", "foreAdjustFactor",
            "backAdjustFactor", "adjustFactor");

    /** A 股与 ETF 的业务语义不同；因子又是事件表，不能共用“每日必须有行”的假设。 */
    enum Kind { A_STOCK, ETF, FACTOR }

    private BaoStockBatchValidator() { }

    /**
     * 按服务端字段名映射，而非依赖列顺序；严格检查集合，避免改版新增/漏列悄悄进入生产。
     * ETF 仅兼容文档中的 13/14 列，以及 2026-11-18 公共节点实测的完整 18 列版本；不任意放行增列。
     * 18 列额外估值字段仅保留用于源对照，不代表 ETF 具备个股估值语义。输入/输出都保留原数值字符串，
     * 空值保持空字符串，绝不擅自将缺失估值或停牌信息改成 0。
     */
    static List<Map<String, String>> decode(ObjectMapper mapper, String json, String fieldNames,
                                            Kind kind, LocalDate from, LocalDate to, String expectedCode) {
        Set<String> expected = new HashSet<>(kind == Kind.FACTOR ? FACTOR_FIELDS : BAR_FIELDS);
        if (kind == Kind.A_STOCK) expected.addAll(VALUE_FIELDS);
        if (kind == Kind.ETF && fieldNames != null) {
            Set<String> advertised = new HashSet<>(Arrays.stream(fieldNames.split(",", -1)).map(String::strip).toList());
            if (advertised.contains("isST")) expected.add("isST");
            // 实际 18 列必须整组出现；部分估值列或任意其它未知列仍由下方字段集合检查拒绝。
            if (advertised.containsAll(VALUE_FIELDS)) expected.addAll(VALUE_FIELDS);
        }
        List<Map<String, String>> rows = decodeTable(mapper, json, fieldNames, expected, MAX_BATCH_ROWS);
        Set<String> identities = new HashSet<>();
        int invalidRows = 0;
        Map<String, Integer> categories = new LinkedHashMap<>();
        Map<String, Integer> fieldProblems = new LinkedHashMap<>();
        List<String> examples = new ArrayList<>();
        for (Map<String, String> item : rows) {
            try {
                validateRow(item, kind, from, to, expectedCode, "3");
                String dateField = kind == Kind.FACTOR ? "dividOperateDate" : "date";
                if (!identities.add(item.get("code") + "/" + item.get(dateField))) {
                    throw invalid("同一证券日期重复，不能判断哪条才是有效数据");
                }
            } catch (IllegalStateException ex) {
                // 完整批次在内存中汇总全部错误行，仅留最多三个安全示例；绝不返回“验证通过的部分行”。
                invalidRows++;
                String category = ex.getMessage().split(" \\[|；row", 2)[0];
                if (categories.size() >= 16 && !categories.containsKey(category)) category = "其它校验错误";
                categories.merge(category, 1, Integer::sum);
                summarizeNumericProblems(item, kind, fieldProblems);
                if (examples.size() < 3) examples.add(ex.getMessage());
            }
        }
        if (invalidRows > 0) throw invalid("整批拒绝；总行数=" + rows.size() + "，失败行数=" + invalidRows
                + "，类别=" + categories + "，数值字段问题=" + fieldProblems + "，首批示例=" + examples);
        return rows;
    }

    /**
     * 单证券历史不请求 code 列，身份由响应外层严格核对；校验时临时补入身份，返回时不新增字段。
     * 复权标记必须与请求完全一致，不能把未复权数据当作已完成的前复权覆盖缓存。
     */
    static List<Map<String, String>> decodeHistory(ObjectMapper mapper, String json, String fieldNames,
            String requestedFields, Kind kind, String code, LocalDate from, LocalDate to, String adjustFlag,
            int pageSize) {
        List<Map<String, String>> rows = decodeTable(mapper, json, fieldNames,
                Set.copyOf(Arrays.asList(requestedFields.split(","))), pageSize);
        Set<String> dates = new HashSet<>();
        for (Map<String, String> row : rows) {
            Map<String, String> withIdentity = new LinkedHashMap<>(row);
            withIdentity.put("code", code);
            validateRow(withIdentity, kind, from, to, code, adjustFlag);
            if (!dates.add(row.get("date"))) throw invalid("单证券历史存在重复日期");
        }
        return rows;
    }

    /** 名单也必须有完整字段/记录结构；名称与交易标记保留原样，不能用残缺名单判定证券不存在。 */
    static List<Map<String, String>> decodeStockList(ObjectMapper mapper, String json, String fieldNames,
                                                     int pageSize) {
        return decodeStockList(mapper, json, fieldNames, pageSize, false);
    }

    /** 审计快照可保留空显示名；生产模式仍拒绝，防止历史缺名覆盖现有证券名称。 */
    static List<Map<String, String>> decodeStockList(ObjectMapper mapper, String json, String fieldNames,
                                                     int pageSize, boolean allowMissingNames) {
        List<Map<String, String>> rows = decodeTable(mapper, json, fieldNames,
                Set.of("code", "tradeStatus", "code_name"), pageSize);
        Set<String> codes = new HashSet<>();
        int invalidRows = 0;
        int supportedInvalidRows = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String code = row.get("code");
            boolean validCode = code.matches("(?:sh|sz|bj)\\.[0-9]{6}");
            boolean supported = code.matches("(?:sz\\.(?:00|30)|sh\\.(?:60|58))[0-9]{4}");
            List<String> rowReasons = new ArrayList<>();
            if (!validCode) rowReasons.add("invalidCode");
            if (!Set.of("0", "1").contains(row.get("tradeStatus"))) rowReasons.add("invalidTradeStatus");
            if (!allowMissingNames && row.get("code_name").isBlank()) rowReasons.add("emptyName");
            if (validCode && !codes.add(code)) rowReasons.add("duplicateCode");
            if (!rowReasons.isEmpty()) {
                invalidRows++;
                if (supported) supportedInvalidRows++;
                String example = "code=" + (validCode ? code : "invalid") + ",supported=" + supported
                        + ",tradeStatus={" + valueShape(row.get("tradeStatus")) + "},nameEmpty="
                        + row.get("code_name").isBlank() + ",nameLength=" + row.get("code_name").length();
                for (String reason : rowReasons) {
                    reasons.merge(reason, 1, Integer::sum);
                    List<String> samples = examples.computeIfAbsent(reason, unused -> new ArrayList<>());
                    // 四类固定原因各留最多两例，避免几千条同类异常刷屏或泄露完整证券名单。
                    if (samples.size() < 2) samples.add(example);
                }
            }
        }
        if (invalidRows > 0) throw invalid("证券名单整页拒绝；pageRows=" + rows.size() + "，invalidRows="
                + invalidRows + "，supportedInvalidRows=" + supportedInvalidRows + "，outsideOrInvalidCodeRows="
                + (invalidRows - supportedInvalidRows) + "，reasons=" + reasons + "，examples=" + examples);
        return rows;
    }

    /** 共用表结构检查：合法空表必须明确为 record:[] 并携带完整字段，绝不将缺失数据退化为空。 */
    private static List<Map<String, String>> decodeTable(ObjectMapper mapper, String json, String fieldNames,
                                                        Set<String> expected, int maxRows) {
        if (json == null || json.isBlank() || fieldNames == null || fieldNames.isBlank()) {
            throw invalid("JSON 或字段列表缺失，不能视为成功空表");
        }
        String[] fields = fieldNames.strip().split(",", -1);
        Set<String> seenFields = new HashSet<>();
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].strip();
            if (fields[i].isEmpty() || !seenFields.add(fields[i])) throw invalid("存在空字段或重复字段");
        }
        if (!seenFields.equals(expected)) {
            Set<String> missing = new HashSet<>(expected); missing.removeAll(seenFields);
            Set<String> unexpected = new HashSet<>(seenFields); unexpected.removeAll(expected);
            throw invalid("字段集合与请求契约不一致；fieldCount=" + fields.length
                    + "，expected=" + safeFieldNames(expected) + "，actual=" + safeFieldNames(seenFields)
                    + "，missing=" + safeFieldNames(missing) + "，unexpected=" + safeFieldNames(unexpected)
                    + "，" + safeTableShape(mapper, json, fields));
        }

        final JsonNode records;
        try {
            // 不能仅解析第一个 JSON 对象后忽略尾部垃圾，否则截断/串帧可能被误判为完整响应。
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (root == null || !root.isObject() || !root.path("record").isArray()) {
                throw invalid("响应必须包含 record 数组");
            }
            records = root.get("record");
        } catch (IOException ex) {
            throw new IllegalStateException("BaoStock 批量 JSON 不完整或无法解析", ex);
        }
        if (records.size() > maxRows) throw invalid("记录数超过批量安全上限");
        List<Map<String, String>> result = new ArrayList<>(records.size());
        for (JsonNode row : records) {
            if (!row.isArray() || row.size() != fields.length) throw invalid("记录列数与字段列表不一致");
            Map<String, String> item = new LinkedHashMap<>();
            for (int i = 0; i < fields.length; i++) {
                JsonNode cell = row.get(i);
                if (!(cell.isTextual() || cell.isNumber() || cell.isNull())) {
                    throw invalid("单元格不是文本、数字或空值");
                }
                item.put(fields[i], cell.isNull() ? "" : cell.asText());
            }
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    /** 身份、日期、状态及数值合法性检查；估值允许负数和空值，符合原数据口径。 */
    private static void validateRow(Map<String, String> row, Kind kind, LocalDate from, LocalDate to,
                                    String expectedCode, String adjustFlag) {
        try {
            validateRowValues(row, kind, from, to, expectedCode, adjustFlag);
        } catch (IllegalStateException ex) {
            // 只暴露已通过格式白名单的公开证券身份与状态；不包含整行、JSON、名称、会话或任意源文本。
            String code = row.get("code");
            String date = row.get(kind == Kind.FACTOR ? "dividOperateDate" : "date");
            String status = row.get("tradestatus");
            throw new IllegalStateException(ex.getMessage() + "；row{code="
                    + (code != null && code.matches("(?:sh|sz|bj)\\.[0-9]{6}") ? code : "invalid")
                    + ",date=" + (date != null && date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? date : "invalid")
                    + ",tradestatus=" + ("0".equals(status) || "1".equals(status) ? status : "missing/invalid")
                    + ",volume=" + valueShape(row.get("volume")) + ",amount=" + valueShape(row.get("amount")) + "}");
        }
    }

    /** 校验核心保持严格；实际源返回的停牌成交量/额双空只保留缺失，不推算或填零。 */
    private static void validateRowValues(Map<String, String> row, Kind kind, LocalDate from, LocalDate to,
                                          String expectedCode, String adjustFlag) {
        String code = row.get("code");
        if (code == null || !code.matches("(?:sh|sz|bj)\\.[0-9]{6}")
                || (expectedCode != null && !expectedCode.equals(code))) {
            throw invalid("证券代码格式错误或与请求不符");
        }
        String dateField = kind == Kind.FACTOR ? "dividOperateDate" : "date";
        String dateText = row.get(dateField);
        try {
            LocalDate date = LocalDate.parse(dateText);
            if (!date.toString().equals(dateText) || date.isBefore(from) || date.isAfter(to)) {
                throw invalid("记录日期不在请求范围内");
            }
        } catch (DateTimeParseException | NullPointerException ex) {
            throw invalid("记录日期格式不正确");
        }
        if (kind == Kind.FACTOR) {
            for (String field : List.of("foreAdjustFactor", "backAdjustFactor", "adjustFactor")) {
                if (number(row.get(field), field, false).signum() <= 0) throw invalid("复权因子必须为正数");
            }
            return;
        }
        if (!adjustFlag.equals(row.get("adjustflag"))) throw invalid("日线复权标记与请求不一致，拒绝错误覆盖");
        if (!Set.of("0", "1").contains(row.get("tradestatus"))) throw invalid("交易状态不是 0/1");
        if (kind == Kind.A_STOCK && !Set.of("0", "1").contains(row.get("isST"))) {
            throw invalid("A 股 ST 状态不是 0/1");
        }
        if (kind == Kind.ETF && row.containsKey("isST") && !row.get("isST").isEmpty()
                && !Set.of("0", "1").contains(row.get("isST"))) throw invalid("ETF 源状态字段格式不正确");
        /*
         * 2026-11-18 的只读取样确认：2026-11-16 sh.600185 在批量与逐股 flag=3 接口均返回
         * tradestatus=0、volume=""、amount=""。这是真实观测到的源缺失例外，不能说成官方保证：
         * 官方文档仍写停牌成交量/额为 0。仅允许停牌且这两列同时为空，其它价格/状态仍严格检查。
         * 空字符串原样向上传递；绝不据停牌状态填成 0，以便后续逐字段对照与入库空值策略显式处理。
         */
        boolean suspendedMissingTrades = allowsMissingTrades(row);
        for (String field : List.of("open", "high", "low", "close", "preclose", "volume", "amount")) {
            boolean allowedMissing = suspendedMissingTrades && ("volume".equals(field) || "amount".equals(field));
            BigDecimal value = number(row.get(field), field, allowedMissing);
            if (value != null && value.signum() < 0) throw invalid("原始行情数值不应为负");
        }
        for (String field : List.of("turn", "pctChg", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM")) {
            if (row.containsKey(field)) number(row.get(field), field, true);
        }
    }

    /** BigDecimal 只用于验证，不转换返回值；NaN/Infinity/空白污染不能流入金融计算。 */
    private static BigDecimal number(String value, String field, boolean allowEmpty) {
        if (allowEmpty && "".equals(value)) return null;
        try {
            if (value == null || value.isBlank() || !value.equals(value.strip())) throw new NumberFormatException();
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw invalid("字段 " + field + " 不是有效数值 [" + valueShape(value) + "]");
        }
    }

    /** 有界数值形状诊断：仅白名单短数值/缺失标识可显示，任意源文本只报告长度和类别。 */
    private static String valueShape(String value) {
        if (value == null) return "null";
        String shown = "other";
        if (value.isEmpty()) shown = "empty";
        else if (value.isBlank()) shown = "whitespace";
        else if (value.matches("[0-9eE+\\-.]{1,32}")
                || Set.of("None", "null", "NaN", "Infinity", "-Infinity", "N/A", "--").contains(value)) {
            shown = value;
        }
        return "chars=" + value.length() + ",empty=" + value.isEmpty() + ",value=" + shown;
    }

    /** 校验与诊断复用同一观测例外，避免其它字段失败时又把已允许的停牌双空误报为数值错误。 */
    private static boolean allowsMissingTrades(Map<String, String> row) {
        return "0".equals(row.get("tradestatus"))
                && "".equals(row.get("volume")) && "".equals(row.get("amount"));
    }

    /** 字段诊断仅输出最多 24 个、最长 32 字符的 ASCII 标识；异常字段文字不直接写入日志。 */
    private static String safeFieldNames(Set<String> fields) {
        List<String> shown = fields.stream().sorted().limit(24)
                .map(name -> name.matches("[A-Za-z_][A-Za-z0-9_]{0,31}")
                        ? name : "invalidIdentifier(chars=" + name.length() + ")").toList();
        return shown + (fields.size() > 24 ? ",omitted=" + (fields.size() - 24) : "");
    }

    /**
     * 字段不一致时仅展示表结构与首行公开身份，帮助识别协议改版还是列错位。
     * 不输出首行其它单元格、JSON 原文或解析器异常文本；空表/坏 JSON 也有明确且有界的形状提示。
     */
    private static String safeTableShape(ObjectMapper mapper, String json, String[] fields) {
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            JsonNode records = root == null ? null : root.get("record");
            if (records == null || !records.isArray()) return "recordArray=false";
            if (records.isEmpty()) return "rows=0,firstRow=none";
            JsonNode first = records.get(0);
            if (!first.isArray()) return "rows=" + records.size() + ",firstRowArray=false";
            String code = "missing/invalid";
            String date = "missing/invalid";
            for (int i = 0; i < fields.length && i < first.size(); i++) {
                if ("code".equals(fields[i]) && first.get(i).isTextual()
                        && first.get(i).asText().matches("(?:sh|sz|bj)\\.[0-9]{6}")) code = first.get(i).asText();
                if ("date".equals(fields[i]) && first.get(i).isTextual()
                        && first.get(i).asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) date = first.get(i).asText();
            }
            return "rows=" + records.size() + ",firstRowColumns=" + first.size() + ",firstCode=" + code
                    + ",firstDate=" + date;
        } catch (IOException | RuntimeException ex) {
            return "jsonShape=unavailable";
        }
    }

    /**
     * 每个失败行同时检查所有数值列，用“字段:问题类型 -> 次数”汇总，避免修复第一列后反复下载整批。
     * 这是诊断统计而非放行逻辑：首个错误仍决定行失败，任何缺失/不合法数据都不写入或返回。
     */
    private static void summarizeNumericProblems(Map<String, String> row, Kind kind, Map<String, Integer> counts) {
        List<String> required = kind == Kind.FACTOR
                ? List.of("foreAdjustFactor", "backAdjustFactor", "adjustFactor")
                : List.of("open", "high", "low", "close", "preclose", "volume", "amount");
        List<String> fields = new ArrayList<>(required);
        if (kind != Kind.FACTOR) fields.addAll(List.of("turn", "pctChg", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM"));
        for (String field : fields) {
            if (!row.containsKey(field)) continue;
            String value = row.get(field);
            String issue = null;
            if (value == null || value.isEmpty()) {
                boolean allowedMissingTrade = kind != Kind.FACTOR && allowsMissingTrades(row)
                        && ("volume".equals(field) || "amount".equals(field));
                if (required.contains(field) && !allowedMissingTrade) issue = "empty";
            } else {
                try {
                    if (!value.equals(value.strip()) || value.isBlank()) throw new NumberFormatException();
                    BigDecimal number = new BigDecimal(value);
                    if (required.contains(field) && (number.signum() < 0 || (kind == Kind.FACTOR && number.signum() == 0))) {
                        issue = kind == Kind.FACTOR ? "nonpositive" : "negative";
                    }
                } catch (NumberFormatException ex) {
                    issue = "notNumeric";
                }
            }
            if (issue != null) counts.merge(field + ":" + issue, 1, Integer::sum);
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("BaoStock 批量数据契约校验失败：" + detail);
    }
}
