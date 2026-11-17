package com.li.stock.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.stock.integration.BaoStockAccessGate;
import com.li.stock.integration.BaoStockClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 方案 A 的独立取样程序；通过 PropertiesLauncher 调用，不启动 Spring 或连接数据库。
 *
 * <p>只有部署脚本确认所有旧采集进程空闲且服务停止后，才设置 online JVM 开关。
 * 本程序只把源响应写到 .local 内的隔离快照，不落生产行情，不切换任何采集通道。
 * 每条成功请求独立原子保存并校验摘要，重新运行同一计划时复用已完成的响应；失败
 * 响应没有成功标记，不能当成空数据跳过。前/后复权和因子每轮重新获取，即使同日也不
 * 复用上一轮缓存；观察日只用于审计与跨午夜停止，不冒充可信的上游复权版本。</p>
 */
public final class BaoStockBatchProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> ROWS = new TypeReference<>() {};
    private final Path directory;
    private final LocalDate observedOn = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final String adjustedCaptureId = java.util.UUID.randomUUID().toString();
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final int delayMillis;
    private long lastRequestFinished;

    private BaoStockBatchProbe(Path directory, int delayMillis) {
        this.directory = directory;
        this.delayMillis = delayMillis;
    }

    /** 参数依次为计划 JSON、项目根目录、隔离输出目录；无开关时在创建会话之前拒绝运行。 */
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("stock.batch.validation.online")) {
            throw new IllegalStateException("在线验证未启用；先用部署入口核验现有采集完全空闲");
        }
        if (args.length != 3) throw new IllegalArgumentException("需要计划文件、项目根目录和 .local 输出目录");
        Path root = Path.of(args[1]).toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("apps/api")) || !output.startsWith(root.resolve(".local"))) {
            throw new IllegalArgumentException("验证输出必须位于项目 .local 目录内");
        }
        // 创建目录之前先检查最近存在的祖先，防止通过 .local 下的目录联接提前写到项目外。
        Path ancestor = output;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().equals(ancestor)) throw new IllegalArgumentException("输出祖先含目录联接或符号链接，请改用真实 .local 目录");
        Files.createDirectories(output);
        // 解析后再校验一次真实路径，不能通过目录联接把行情快照写到公开文件树。
        output = output.toRealPath();
        if (!output.startsWith(root.resolve(".local"))) throw new IllegalArgumentException("输出目录越界");
        JsonNode plan = JSON.readTree(Path.of(args[0]).toFile());
        validatePlan(plan);
        new BaoStockBatchProbe(output, plan.path("requestDelayMillis").asInt(1500)).run(plan);
    }

    /** 限定支持市场、过去日期及有界请求计划；拒绝今天的未完成行情和无意扩大的全历史请求。 */
    static void validatePlan(JsonNode plan) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate from = LocalDate.parse(plan.path("from").asText());
        LocalDate to = LocalDate.parse(plan.path("to").asText());
        if (from.isAfter(to) || !to.isBefore(today) || from.plusDays(3650).isBefore(to)) {
            throw new IllegalArgumentException("取样范围必须是过去的闭区间，且不超过 3650 个自然日");
        }
        JsonNode dates = plan.path("dates"), codes = plan.path("codes");
        if (!dates.isArray() || dates.isEmpty() || dates.size() > 3651
                || !codes.isArray() || codes.isEmpty() || codes.size() > 50) {
            throw new IllegalArgumentException("dates 必须为 1～3651 个日期，codes 必须为 1～50 只代表证券");
        }
        var dateSet = new java.util.HashSet<String>();
        for (JsonNode value : dates) {
            LocalDate date = LocalDate.parse(value.asText());
            if (date.isBefore(from) || date.isAfter(to) || !dateSet.add(date.toString())) {
                throw new IllegalArgumentException("取样日期越界或重复");
            }
        }
        var codeSet = new java.util.HashSet<String>();
        for (JsonNode value : codes) {
            String code = value.asText();
            if (!code.matches("(?:sz\\.(?:00|30)|sh\\.(?:60|58))[0-9]{4}") || !codeSet.add(code)) {
                throw new IllegalArgumentException("代表证券仅允许明确交易所的 00/30/60 股票及 58 ETF，且不能重复");
            }
        }
        int delay = plan.path("requestDelayMillis").asInt(1500);
        if (delay < 1500 || delay > 10000) throw new IllegalArgumentException("验证请求间隔须为 1500～10000 毫秒");
    }

    private void run(JsonNode plan) throws Exception {
        LocalDate from = LocalDate.parse(plan.path("from").asText());
        LocalDate to = LocalDate.parse(plan.path("to").asText());
        List<String> codes = new ArrayList<>();
        plan.path("codes").forEach(node -> codes.add(node.asText()));
        boolean includeEtf = codes.stream().anyMatch(code -> code.startsWith("sh.58"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("plan", plan);
        report.put("observedOn", observedOn.toString());
        report.put("adjustedCaptureId", adjustedCaptureId);
        report.put("scope", "SOURCE_SNAPSHOTS_ONLY");
        report.put("cutoverAllowed", false);
        report.put("modelParity", "NOT_RUN");
        report.put("requests", requests);
        List<Map<String, String>> skippedDates = new ArrayList<>();
        report.put("skippedDates", skippedDates);
        try (BaoStockAccessGate.Lease lease = BaoStockAccessGate.acquire();
             BaoStockClient client = new BaoStockClient(JSON, 10000, 60000)) {
            // 登录一次，全部调用顺序执行；仍可能有客户端有界重连，不能把方法调用数冒充协议帧总数。
            client.login();
            List<Map<String, String>> calendar = snapshot("calendar", Map.of("from", from.toString(), "to", to.toString()), false,
                    () -> client.queryTradeCalendar(from, to).entrySet().stream()
                            .map(entry -> Map.of("date", entry.getKey().toString(), "is_trading_day", entry.getValue() ? "1" : "0")).toList());
            // 批量源出现数据质量问题时，可先保留逐股基准供定位；只改变读取顺序，不跳过任何验收。
            // 该开关不绕过严格字段校验，也不会把只取得基准的执行报告标记为批量采集成功。
            boolean historyFirst = plan.path("historyFirst").asBoolean(false);
            if (historyFirst) captureHistories(client, codes, from, to);
            for (JsonNode value : plan.path("dates")) {
                LocalDate day = LocalDate.parse(value.asText());
                if (!isTradingDate(calendar, day)) {
                    // 完整官方日历明确休市时不发送日线/名单请求，避免空字段响应与数据截断混淆。
                    // 只记录跳过原因，不生成“成功空行情”快照；离线工具仍须独立核对该日历证据。
                    skippedDates.add(Map.of("date", day.toString(), "reason", "CALENDAR_NON_TRADING"));
                    continue;
                }
                Map<String, String> key = Map.of("date", day.toString());
                // 历史基金可能已无显示名称，审计保留空名但严格核对代码和状态；此结果不会更新生产证券名称。
                snapshot("stock-list", key, false, () -> client.queryAllStockSnapshot(day));
                snapshot("daily-stock", key, false, () -> client.queryDailyAStock(day));
                if (includeEtf) snapshot("daily-etf", key, false, () -> client.queryDailyEtf(day));
                snapshot("daily-factors", key, true, () -> client.queryDailyAdjustFactors(day));
            }
            if (!historyFirst) captureHistories(client, codes, from, to);
            report.put("status", "CAPTURED_NOT_ACCEPTED");
        } catch (Exception ex) {
            report.put("status", "FAILED");
            report.put("failure", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        } finally {
            // 无论取样是否中断都保存真实阶段，不会把只取样成功写成完整性/预测验收成功。
            atomicJson(directory.resolve("capture-report.json"), report);
        }
        System.out.println("源响应快照已保存；复权及评分预测尚待独立比较，生产切换仍禁止。");
    }

    /** 请求前按已验证日历判断；不存在或状态未知均失败，不能以周末推断或默认为休市。 */
    static boolean isTradingDate(List<Map<String, String>> calendar, LocalDate day) {
        List<Map<String, String>> matches = calendar.stream().filter(row -> day.toString().equals(row.get("date"))).toList();
        String state = matches.size() == 1 ? matches.getFirst().get("is_trading_day") : null;
        if (!"0".equals(state) && !"1".equals(state)) {
            throw new IllegalStateException("取样日期缺少唯一且明确的交易日历状态");
        }
        return "1".equals(state);
    }

    /** 单连接读取完整逐股基准与历史因子；前/后复权每轮取新快照，不能复用同日旧版本。 */
    private void captureHistories(BaoStockClient client, List<String> codes, LocalDate from, LocalDate to) throws Exception {
        for (String code : codes) {
            boolean etf = code.startsWith("sh.58");
            for (String flag : etf ? List.of("3") : List.of("3", "2", "1")) {
                Map<String, String> key = Map.of("code", code, "from", from.toString(), "to", to.toString(), "adjustflag", flag);
                snapshot("history", key, !"3".equals(flag), () -> client.queryHistoryVerified(code, from, to, flag));
            }
            if (!etf) {
                // 因子从市场起点取得，保留窗口之前最后一次事件。空结果仍需结合证券历史单独审查。
                LocalDate factorFrom = LocalDate.of(1990, 12, 19);
                snapshot("factors", Map.of("code", code, "from", factorFrom.toString(), "to", observedOn.toString()), true,
                        () -> client.queryAdjustFactors(code, factorFrom, observedOn));
            }
        }
    }

    private List<Map<String, String>> snapshot(String operation, Map<String, String> parameters,
                                               boolean adjusted, Supplier<List<Map<String, String>>> query) throws Exception {
        requireObservationDate();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("contract", "baostock-batch-v2");
        identity.put("operation", operation);
        identity.put("parameters", new java.util.TreeMap<>(parameters));
        if (adjusted) {
            identity.put("observedOn", observedOn.toString());
            identity.put("adjustedCaptureId", adjustedCaptureId);
        }
        String key = sha(JSON.writeValueAsBytes(identity));
        Path destination = directory.resolve(key + ".json");
        if (Files.exists(destination)) {
            JsonNode cached = JSON.readTree(destination.toFile());
            List<Map<String, String>> rows = JSON.convertValue(cached.path("rows"), ROWS);
            Map<String, Object> cachedIdentity = new LinkedHashMap<>();
            cachedIdentity.put("contract", cached.path("contract").asText());
            cachedIdentity.put("operation", cached.path("operation").asText());
            cachedIdentity.put("parameters", JSON.convertValue(cached.path("parameters"), new TypeReference<java.util.TreeMap<String, String>>() {}));
            if (adjusted) {
                cachedIdentity.put("observedOn", cached.path("observedOn").asText());
                cachedIdentity.put("adjustedCaptureId", cached.path("adjustedCaptureId").asText());
            }
            if (!cached.path("complete").asBoolean() || !key.equals(cached.path("key").asText())
                    || !key.equals(sha(JSON.writeValueAsBytes(cachedIdentity)))
                    || !sha(JSON.writeValueAsBytes(rows)).equals(cached.path("rowsSha256").asText())) {
                throw new IllegalStateException("已有快照不完整或摘要不符；保留文件等待检查，未默默覆盖");
            }
            requests.add(Map.of("operation", operation, "parameters", parameters, "file", destination.getFileName().toString(),
                    "cached", true, "rows", rows.size(), "originalElapsedMillis", cached.path("elapsedMillis").asLong()));
            return rows;
        }
        if (lastRequestFinished > 0) {
            long remaining = delayMillis - (System.nanoTime() - lastRequestFinished) / 1_000_000;
            if (remaining > 0) Thread.sleep(remaining);
        }
        long started = System.nanoTime();
        List<Map<String, String>> rows;
        try { rows = query.get(); } finally { lastRequestFinished = System.nanoTime(); }
        // 若上游等待跨越午夜，本次响应不能归入旧观察日；原始数据也暂停，防止同一报告混两个基准日。
        requireObservationDate();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> saved = new LinkedHashMap<>(identity);
        saved.put("key", key);
        saved.put("complete", true);
        saved.put("capturedOn", observedOn.toString());
        saved.put("elapsedMillis", elapsed);
        saved.put("rowsSha256", sha(JSON.writeValueAsBytes(rows)));
        saved.put("rows", rows);
        atomicJson(destination, saved);
        requests.add(Map.of("operation", operation, "parameters", parameters, "file", destination.getFileName().toString(),
                "cached", false, "rows", rows.size(), "elapsedMillis", elapsed));
        return rows;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void requireObservationDate() {
        if (!observedOn.equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw new IllegalStateException("验证跨越观察日，停止保存本次响应；请在新观察日续跑并重新核对复权快照");
        }
    }

    private static void atomicJson(Path destination, Object value) throws Exception {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
