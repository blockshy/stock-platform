package com.li.stock.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/**
 * BaoStock 原生 TCP 客户端。
 * Python 项目中的 baostock 包本质上也是通过 public-api.baostock.com:10030
 * 使用同一套消息协议；这里用 JDK Socket、CRC32 和 Jackson 直接实现登录、股票列表、
 * 历史日线查询及分页，页面触发时不再启动 Python 进程。
 *
 * <p>容错策略：BaoStock 服务端在限流或网络抖动时会中途断开连接，一旦某次响应
 * 读取超时/中断，Socket 里会残留该响应的后续字节，之后所有请求读到的“响应头”
 * 都是残留数据（表现为持续的“响应头格式错误”）。因此任何传输层异常都视为
 * 连接已损坏：立即关闭并重新登录，再重试当前请求（最多 {@link #MAX_ATTEMPTS} 次，
 * 有界指数退避并加入抖动）。分页查询按页重试，已成功的页不会重复请求。</p>
 */
public final class BaoStockClient implements AutoCloseable {
    private static final String VERSION = "00.9.40";
    private static final String SEP = "\1";
    private static final String HOST = "public-api.baostock.com";
    private static final String LOGIN_USER = "anonymous";
    private static final int PORT = 10030;
    private static final int HEADER_LENGTH = 21;
    private static final int PAGE_SIZE = 2000;
    /** 防止错误响应或压缩炸弹让客户端无限分配内存；全市场单日响应远小于此上限。 */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    /** 官方 0.9.4 明确列出的压缩响应；批量接口与旧版历史日线使用同一种 zlib 帧。 */
    private static final Set<String> COMPRESSED_TYPES = Set.of("96", "99", "9B", "9D");
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 2000;
    private static final long MAX_RETRY_BACKOFF_MILLIS = 8000;
    private static final byte[] COMPRESSED_END = "<![CDATA[]]>\n".getBytes(StandardCharsets.UTF_8);
    private static final String HISTORY_FIELDS =
            "date,open,high,low,close,preclose,volume,amount,adjustflag,turn,"
                    + "tradestatus,pctChg,isST,peTTM,pbMRQ,psTTM,pcfNcfTTM";
    /**
     * 58 ETF 只请求行情字段。BaoStock 返回的基金 isST 不能作为个股 ST 标记使用，
     * 市盈率等个股估值对 ETF 也不适用，因此在请求端即排除这些字段，入库端另做空值保护。
     */
    private static final String ETF_HISTORY_FIELDS =
            "date,open,high,low,close,preclose,volume,amount,adjustflag,turn,tradestatus,pctChg";

    private final ObjectMapper objectMapper;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final Supplier<Socket> socketFactory;
    private final RetrySleeper retrySleeper;
    private final LongUnaryOperator jitter;
    private Socket socket;
    private String userId = LOGIN_USER;

    public BaoStockClient(ObjectMapper objectMapper, int connectTimeoutMillis, int readTimeoutMillis) {
        this(objectMapper, connectTimeoutMillis, readTimeoutMillis, Socket::new);
    }

    /** 包内测试可注入纯内存 Socket；生产构造器始终使用真实 Socket，不暴露可配置远程地址。 */
    BaoStockClient(ObjectMapper objectMapper, int connectTimeoutMillis, int readTimeoutMillis,
                   Supplier<Socket> socketFactory) {
        this(objectMapper, connectTimeoutMillis, readTimeoutMillis, socketFactory,
                Thread::sleep, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    /**
     * 包内注入等待与随机源，使离线测试能验证退避预算而不真实休眠。
     * jitter 接收正数排他上界，必须返回 [0, bound)；生产固定采用线程本地随机源。
     */
    BaoStockClient(ObjectMapper objectMapper, int connectTimeoutMillis, int readTimeoutMillis,
                   Supplier<Socket> socketFactory, RetrySleeper retrySleeper, LongUnaryOperator jitter) {
        this.objectMapper = objectMapper;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.socketFactory = socketFactory;
        this.retrySleeper = retrySleeper;
        this.jitter = jitter;
    }

    /**
     * 登录（带重试）。BaoStock 服务端高峰期登录响应也会超时，单次超时不应
     * 直接让整个抓取任务失败，这里与查询一样按指数退避重试。
     */
    public void login() {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            requireNotInterrupted();
            try {
                loginOnce();
                return;
            } catch (TransportException ex) {
                last = ex;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        throw new IllegalStateException("BaoStock 登录尝试 " + MAX_ATTEMPTS + " 次仍失败："
                + (last == null ? "unknown" : rootMessage(last)), last);
    }

    /** 单次登录尝试，失败即抛 TransportException，由调用方决定是否重试。 */
    private void loginOnce() {
        closeSilently();
        try {
            Socket fresh = socketFactory.get();
            // 在 connect/setSoTimeout 之前接管资源；二者失败时 finally 风格的清理也能关闭新 Socket。
            // 原实现成功连接后才赋值，会遗漏连接超时或参数设置异常的半初始化连接。
            socket = fresh;
            fresh.connect(new InetSocketAddress(HOST, PORT), connectTimeoutMillis);
            fresh.setSoTimeout(readTimeoutMillis);
            // 登录账号与登录返回的会话标识分离；重连不能把已失效的会话标识当作账号再次登录。
            Response response = rawRequest("00", "login" + SEP + LOGIN_USER + SEP + "123456" + SEP + "0");
            response.requireSuccess();
            userId = LOGIN_USER;
            if (response.parts().size() > 3 && !response.parts().get(3).isBlank()) {
                userId = response.parts().get(3);
            }
        } catch (IOException ex) {
            closeSilently();
            throw new TransportException("BaoStock 连接失败：" + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            closeSilently();
            throw ex;
        }
    }

    /** 查询指定日期最近一次可用的沪深 A 股列表。 */
    public List<Map<String, String>> queryAllStock(LocalDate date) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Response response = request("35", String.join(SEP,
                    "query_all_stock", userId, String.valueOf(page), String.valueOf(PAGE_SIZE),
                    date.toString()));
            response.requireSuccess();
            List<Map<String, String>> pageRows = response.rows(6, 8);
            rows.addAll(pageRows);
            if (pageRows.size() < PAGE_SIZE) return rows;
        }
    }

    /**
     * 供覆盖缓存和验证探针使用的严格证券名单查询。既有普通接口保留兼容，缓存路径必须使用本方法。
     * 每页同时检查外层请求回显与内层字段/记录，不允许缺 record、缺列或重复代码伪装成合法空名单。
     */
    public List<Map<String, String>> queryAllStockVerified(LocalDate date) {
        return queryAllStockVerified(date, false);
    }

    /**
     * 仅用于独立源验证的历史名单快照：保留缺失显示名，但身份、交易状态、字段和分页仍全部严格。
     * 2023-11-17 的公开历史名单实测存在非项目范围基金 code_name=""，不代表证券身份无效。
     * 此结果不能用来更新生产 stock_info.name；生产元数据查询必须继续调用 queryAllStockVerified。
     */
    public List<Map<String, String>> queryAllStockSnapshot(LocalDate date) {
        return queryAllStockVerified(date, true);
    }

    /** 两种用途共用完整协议与分页实现，唯一差异是审计快照是否允许缺显示名称。 */
    private List<Map<String, String>> queryAllStockVerified(LocalDate date, boolean allowMissingNames) {
        Objects.requireNonNull(date, "证券名单日期不能为空");
        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            Response response = request("35", String.join(SEP, "query_all_stock", userId,
                    String.valueOf(page), String.valueOf(PAGE_SIZE), date.toString()));
            response.requireEnvelope("36", "query_all_stock", userId, 8);
            if (!String.valueOf(page).equals(response.parts().get(4))
                    || !String.valueOf(PAGE_SIZE).equals(response.parts().get(5))
                    || !date.toString().equals(response.parts().get(7))) {
                throw new IllegalStateException("BaoStock 证券名单页码或日期与请求不一致");
            }
            List<Map<String, String>> pageRows = BaoStockBatchValidator.decodeStockList(objectMapper,
                    response.parts().get(6), response.parts().get(8), PAGE_SIZE, allowMissingNames);
            for (Map<String, String> row : pageRows) {
                if (!codes.add(row.get("code"))) throw new IllegalStateException("BaoStock 证券名单跨页代码重复");
            }
            rows.addAll(pageRows);
            requireNotInterrupted();
            if (pageRows.size() < PAGE_SIZE) return List.copyOf(rows);
        }
        throw new IllegalStateException("BaoStock 证券名单分页超过安全上限，结果未返回");
    }

    /**
     * 兼容原有交易日列表调用方；先确认完整自然日日历，再筛选交易日并保持升序。
     * 缺失的日历行不能解释为休市，因此这里不再使用宽松的通用 rows() 解析器。
     */
    public List<LocalDate> queryTradeDates(LocalDate from, LocalDate to) {
        return queryTradeCalendar(from, to).entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    /**
     * 获取可安全持久化的完整自然日日历：每一天都必须由上游明确返回 0/1，缺一天即整批失败。
     * 官方区间日历采用 2000 条分页，必须与按日批量接口区别处理；恰好满页时继续查终止页。
     * 返回按日期升序的只读映射。上层只能在整个方法成功后保存，禁止边翻页边补造休市日。
     */
    public Map<LocalDate, Boolean> queryTradeCalendar(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "日历开始日期不能为空");
        Objects.requireNonNull(to, "日历结束日期不能为空");
        long expectedDays = ChronoUnit.DAYS.between(from, to) + 1;
        // 限制调用错误引起的超大分配和无限分页；20 万天已远超当前证券市场可查询历史。
        if (expectedDays <= 0 || expectedDays > 200_000) {
            throw new IllegalArgumentException("BaoStock 日历日期范围不正确或超过安全上限");
        }
        Map<LocalDate, Boolean> calendar = new LinkedHashMap<>();
        long maxPages = expectedDays / PAGE_SIZE + 1;
        for (int page = 1; page <= maxPages; page++) {
            Response response = request("33", String.join(SEP, "query_trade_dates", userId,
                    String.valueOf(page), String.valueOf(PAGE_SIZE), from.toString(), to.toString()));
            response.requireEnvelope("34", "query_trade_dates", userId, 9);
            if (!String.valueOf(page).equals(response.parts().get(4))
                    || !String.valueOf(PAGE_SIZE).equals(response.parts().get(5))
                    || !from.toString().equals(response.parts().get(7))
                    || !to.toString().equals(response.parts().get(8))) {
                throw new IllegalStateException("BaoStock 日历响应页码或日期范围与请求不一致");
            }
            Map<LocalDate, Boolean> pageRows = BaoStockTradeCalendarValidator.decode(objectMapper,
                    response.parts().get(6), response.parts().get(9), from, to, PAGE_SIZE);
            for (Map.Entry<LocalDate, Boolean> entry : pageRows.entrySet()) {
                if (calendar.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalStateException("BaoStock 日历跨页重复日期，拒绝保存不可靠日历");
                }
            }
            requireNotInterrupted();
            if (pageRows.size() < PAGE_SIZE) {
                if (calendar.size() != expectedDays) {
                    throw new IllegalStateException("BaoStock 日历未覆盖请求的每个自然日：期望 "
                            + expectedDays + " 天，实际 " + calendar.size() + " 天");
                }
                Map<LocalDate, Boolean> ordered = new LinkedHashMap<>();
                for (long offset = 0; offset < expectedDays; offset++) {
                    LocalDate date = from.plusDays(offset);
                    Boolean trading = calendar.get(date);
                    if (trading == null) throw new IllegalStateException("BaoStock 日历缺少日期 " + date);
                    ordered.put(date, trading);
                }
                return Collections.unmodifiableMap(ordered);
            }
        }
        throw new IllegalStateException("BaoStock 日历分页超过请求范围，结果未返回");
    }

    /** 查询一只证券一段日期的日线；58 ETF 使用独立字段集合，返回源数据的实际复权标记。 */
    public List<Map<String, String>> queryHistory(String secid, LocalDate from, LocalDate to,
                                                   String adjustFlag) {
        List<Map<String, String>> rows = new ArrayList<>();
        String fields = secid.matches("(?i)sh\\.58[0-9]{4}") ? ETF_HISTORY_FIELDS : HISTORY_FIELDS;
        for (int page = 1; ; page++) {
            Response response = request("95", String.join(SEP,
                    "query_history_k_data_plus", userId, String.valueOf(page), String.valueOf(PAGE_SIZE),
                    secid, fields, from.toString(), to.toString(), "d", adjustFlag));
            response.requireSuccess();
            List<Map<String, String>> pageRows = response.rows(6, 8);
            rows.addAll(pageRows);
            if (pageRows.size() < PAGE_SIZE) return rows;
        }
    }

    /**
     * 严格历史查询：只有完整且口径与请求一致的整段结果才能用于断点覆盖。
     * 空 JSON、缺列、越界日期、非有限数、重复日线和复权标记不符均使整段失败；不返回已读部分页。
     * 本方法不变更行情值或算法，ETF 仍只请求原有行情字段，个股仍保留估值和 ST 字段。
     */
    public List<Map<String, String>> queryHistoryVerified(String secid, LocalDate from, LocalDate to,
                                                         String adjustFlag) {
        Objects.requireNonNull(secid, "历史证券代码不能为空");
        Objects.requireNonNull(from, "历史开始日期不能为空");
        Objects.requireNonNull(to, "历史结束日期不能为空");
        String code = secid.toLowerCase(Locale.ROOT);
        if (!code.matches("(?:sh|sz|bj)\\.[0-9]{6}") || from.isAfter(to)
                || adjustFlag == null || !Set.of("1", "2", "3").contains(adjustFlag)) {
            throw new IllegalArgumentException("BaoStock 历史代码、日期或复权标记不正确");
        }
        boolean etf = code.matches("sh\\.58[0-9]{4}");
        String fields = etf ? ETF_HISTORY_FIELDS : HISTORY_FIELDS;
        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> dates = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            Response response = request("95", String.join(SEP, "query_history_k_data_plus", userId,
                    String.valueOf(page), String.valueOf(PAGE_SIZE), code, fields, from.toString(),
                    to.toString(), "d", adjustFlag));
            response.requireEnvelope("96", "query_history_k_data_plus", userId, 12);
            if (!String.valueOf(page).equals(response.parts().get(4))
                    || !String.valueOf(PAGE_SIZE).equals(response.parts().get(5))
                    || !code.equals(response.parts().get(7))
                    || !from.toString().equals(response.parts().get(9))
                    || !to.toString().equals(response.parts().get(10))
                    || !"d".equals(response.parts().get(11)) || !adjustFlag.equals(response.parts().get(12))) {
                throw new IllegalStateException("BaoStock 历史响应页码、证券、日期或复权口径与请求不一致");
            }
            List<Map<String, String>> pageRows = BaoStockBatchValidator.decodeHistory(objectMapper,
                    response.parts().get(6), response.parts().get(8), fields,
                    etf ? BaoStockBatchValidator.Kind.ETF : BaoStockBatchValidator.Kind.A_STOCK,
                    code, from, to, adjustFlag, PAGE_SIZE);
            for (Map<String, String> row : pageRows) {
                if (!dates.add(row.get("date"))) throw new IllegalStateException("BaoStock 历史跨页日期重复");
            }
            rows.addAll(pageRows);
            requireNotInterrupted();
            if (pageRows.size() < PAGE_SIZE) return List.copyOf(rows);
        }
        throw new IllegalStateException("BaoStock 历史分页超过安全上限，结果未返回");
    }

    /**
     * 获取指定交易日全市场 A 股原始日线，包含估值、ST 与停牌状态。
     * 官方批量接口只有日期参数，返回的原始行情必须为 adjustflag=3；本方法不伪造前复权。
     * 调用方应在拿到完整结果后再按项目支持的 00/30/60 前缀过滤，不能将本地过滤条数当作源覆盖数。
     */
    public List<Map<String, String>> queryDailyAStock(LocalDate date) {
        return queryDaily("98", "99", "query_daily_history_k_AStock", date,
                BaoStockBatchValidator.Kind.A_STOCK);
    }

    /**
     * 获取指定日期全市场 ETF 原始日线。兼容文档中的 13/14 列与公共节点实测的 18 列版本。
     * 18 列包含估值与 isST 原始值，仅保留供源审计；调用方必须继续按 ETF 语义隔离这些个股字段。
     */
    public List<Map<String, String>> queryDailyEtf(LocalDate date) {
        return queryDaily("9A", "9B", "query_daily_history_k_ETF", date,
                BaoStockBatchValidator.Kind.ETF);
    }

    /**
     * 获取当日发生除权除息的证券因子，而非全市场每日因子快照。
     * 成功空表表示没有事件；调用方不能据此把所有证券的因子设为 1，也不能删除已有因子。
     */
    public List<Map<String, String>> queryDailyAdjustFactors(LocalDate date) {
        return queryDaily("9C", "9D", "query_daily_adjust_factor", date,
                BaoStockBatchValidator.Kind.FACTOR);
    }

    /**
     * 获取单只证券指定区间的除权事件因子，供历史初始化或受影响证券的复权基准刷新使用。
     * 与按日批量不同，该接口采用官方普通分页布局；每页重试仍共享现有的三次有界预算。
     * 不在本层计算复权价格或修改模型输入，所有因子保留源字符串及精度。
     */
    public List<Map<String, String>> queryAdjustFactors(String secid, LocalDate from, LocalDate to) {
        Objects.requireNonNull(secid, "证券代码不能为空");
        Objects.requireNonNull(from, "开始日期不能为空");
        Objects.requireNonNull(to, "结束日期不能为空");
        String code = secid.toLowerCase(Locale.ROOT);
        if (!code.matches("(?:sh|sz|bj)\\.[0-9]{6}") || from.isAfter(to)) {
            throw new IllegalArgumentException("BaoStock 复权因子查询代码或日期范围不正确");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> dates = new HashSet<>();
        // 一只证券一天至多一个聚合除权事件；额外设页数上限，防止服务端重复满页造成无限请求。
        for (int page = 1; page <= 100; page++) {
            Response response = request("15", String.join(SEP, "query_adjust_factor", userId,
                    String.valueOf(page), String.valueOf(PAGE_SIZE), code, from.toString(), to.toString()));
            response.requireEnvelope("16", "query_adjust_factor", userId, 10);
            if (!String.valueOf(page).equals(response.parts().get(4))
                    || !String.valueOf(PAGE_SIZE).equals(response.parts().get(5))
                    || !code.equals(response.parts().get(7))
                    || !from.toString().equals(response.parts().get(8))
                    || !to.toString().equals(response.parts().get(9))) {
                throw new IllegalStateException("BaoStock 复权因子响应页码、证券或日期范围与请求不一致");
            }
            List<Map<String, String>> pageRows = BaoStockBatchValidator.decode(objectMapper,
                    response.parts().get(6), response.parts().get(10), BaoStockBatchValidator.Kind.FACTOR,
                    from, to, code);
            if (pageRows.size() > PAGE_SIZE) {
                throw new IllegalStateException("BaoStock 复权因子分页响应超出请求条数");
            }
            for (Map<String, String> row : pageRows) {
                if (!dates.add(row.get("dividOperateDate"))) {
                    throw new IllegalStateException("BaoStock 复权因子跨页出现重复日期，停止以避免重复覆盖");
                }
            }
            rows.addAll(pageRows);
            requireNotInterrupted();
            if (pageRows.size() < PAGE_SIZE) return List.copyOf(rows);
        }
        throw new IllegalStateException("BaoStock 复权因子分页超过安全上限，结果未返回");
    }

    /**
     * 0.9.4 批量响应专用布局：错误码、说明、方法、会话、JSON、字段列表。
     * 三个请求没有页码参数，不能沿用 Python 通用 next() 的“恰好 2000 条再翻页”判断；
     * 本方法只发送一个请求并验证整帧，2000 条与超过 2000 条都一次返回。
     */
    private List<Map<String, String>> queryDaily(String requestType, String responseType, String method,
                                                LocalDate date, BaoStockBatchValidator.Kind kind) {
        Objects.requireNonNull(date, "查询日期不能为空");
        Response response = request(requestType, String.join(SEP, method, userId, date.toString()));
        response.requireEnvelope(responseType, method, userId, 5);
        List<Map<String, String>> rows = BaoStockBatchValidator.decode(objectMapper,
                response.parts().get(4), response.parts().get(5), kind, date, date, null);
        requireNotInterrupted();
        return rows;
    }

    /**
     * 带重试的请求：连接断开时自动重新登录，传输层异常按指数退避重试。
     * 仅对精确业务码 10001001（用户未登录）恢复会话；其它业务错误不重试。
     * 认证恢复与传输恢复共用 MAX_ATTEMPTS 总上限，不嵌套调用带重试的 login()。
     */
    private Response request(String messageType, String body) {
        TransportException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            requireNotInterrupted();
            try {
                if (socket == null || socket.isClosed()) {
                    // 用单次登录，重试节奏由本方法统一控制，避免与 login() 嵌套放大。
                    loginOnce();
                }
                // 调用方在进入本方法前已组装消息体，因此重连后必须替换旧 userId 再发送当前页。
                Response response = rawRequest(messageType, bodyForCurrentSession(body));
                if ("10001001".equals(response.errorCode())) {
                    closeSilently();
                    if (attempt < MAX_ATTEMPTS) {
                        backoff(attempt);
                        continue;
                    }
                    // 达到上限后保留上游的精确业务码和说明，避免被包装成 unknown 或无限重新登录。
                    response.requireSuccess();
                }
                return response;
            } catch (TransportException ex) {
                last = ex;
                closeSilently();
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        throw new IllegalStateException("BaoStock 请求尝试 " + MAX_ATTEMPTS + " 次仍失败："
                + (last == null ? "unknown" : rootMessage(last)), last);
    }

    /** 所有已支持查询的前两段均为“命令名 + userId”，只替换会话段，原页码、日期和字段保持不变。 */
    private String bodyForCurrentSession(String body) {
        int first = body.indexOf(SEP);
        int second = body.indexOf(SEP, first + 1);
        if (first < 0 || second < 0) throw new IllegalArgumentException("BaoStock 查询消息缺少会话字段");
        return body.substring(0, first + 1) + userId + body.substring(second);
    }

    /** 单次请求，不做重试；任何 IO 异常都代表连接已不可复用。 */
    private Response rawRequest(String messageType, String body) {
        requireNotInterrupted();
        if (socket == null || socket.isClosed()) {
            throw new TransportException("BaoStock 尚未登录", null);
        }
        try {
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String header = VERSION + SEP + messageType + SEP + String.format("%010d", bodyBytes.length);
            byte[] headBody = (header + body).getBytes(StandardCharsets.UTF_8);
            CRC32 crc32 = new CRC32();
            crc32.update(headBody);
            byte[] request = (header + body + SEP + crc32.getValue() + "\n").getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(request);
            output.flush();

            InputStream input = socket.getInputStream();
            byte[] responseHeader = readFully(input, HEADER_LENGTH);
            String headerText = new String(responseHeader, StandardCharsets.UTF_8);
            int firstSeparator = headerText.indexOf('\1');
            int secondSeparator = headerText.indexOf('\1', firstSeparator + 1);
            if (firstSeparator != 7 || secondSeparator != 10
                    || !headerText.substring(11).matches("[0-9]{10}")) {
                throw new IOException("BaoStock 响应头格式错误");
            }
            String responseType = headerText.substring(firstSeparator + 1, secondSeparator);
            long declaredLength = Long.parseLong(headerText.substring(11));
            if (!responseType.matches("[0-9A-F]{2}") || declaredLength <= 0
                    || declaredLength > MAX_RESPONSE_BYTES) {
                throw new IOException("BaoStock 响应类型或声明长度不合法");
            }
            /*
             * BaoStock Python 客户端不是按 header 中的长度读取响应，而是一直读到
             * <![CDATA[]]>\n。原因是部分非压缩响应的长度按字符数计算，中文股票名称
             * 转成 UTF-8 后字节数会更大；按字节数读取会截断 JSON，并把剩余内容当成
             * 下一次响应头。压缩响应也统一使用这个结束标记，因此这里按官方客户端
             * 的方式读取完整帧，再对压缩正文解压。
             */
            byte[] framedBody = readUntil(input, COMPRESSED_END);
            if (framedBody.length < COMPRESSED_END.length) {
                throw new IOException("BaoStock 响应缺少结束标记");
            }
            byte[] bodyBytesResponse = Arrays.copyOf(framedBody,
                    framedBody.length - COMPRESSED_END.length);
            boolean compressed = COMPRESSED_TYPES.contains(responseType);
            if (compressed) {
                bodyBytesResponse = inflate(bodyBytesResponse);
            }
            String responseBody = new String(bodyBytesResponse, StandardCharsets.UTF_8);
            List<String> parts = List.of(responseBody.split(SEP, -1));
            requireNotInterrupted();
            return new Response(responseType, parts, objectMapper, headerText);
        } catch (IOException | NumberFormatException ex) {
            // 传输层异常后流的位置不可知，连接必须丢弃，由上层重连。
            closeSilently();
            throw new TransportException("BaoStock 查询失败：" + ex.getMessage(), ex);
        }
    }

    private byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                requireNotInterrupted();
                if (output.size() > MAX_RESPONSE_BYTES - count) {
                    throw new IOException("BaoStock 解压响应超过安全大小上限");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private byte[] readFully(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            requireNotInterrupted();
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new IOException("BaoStock 连接提前关闭");
            offset += count;
        }
        return data;
    }

    private byte[] readUntil(InputStream input, byte[] marker) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int matched = 0;
        while (true) {
            requireNotInterrupted();
            int count = input.read(buffer);
            if (count < 0) throw new IOException("BaoStock 连接提前关闭");
            for (int index = 0; index < count; index++) {
                if (output.size() >= MAX_RESPONSE_BYTES) {
                    throw new IOException("BaoStock 响应超过安全大小上限或缺少结束标记");
                }
                byte current = buffer[index];
                output.write(current);
                if (current == marker[matched]) {
                    matched++;
                    if (matched == marker.length) return output.toByteArray();
                } else {
                    matched = current == marker[0] ? 1 : 0;
                }
            }
        }
    }

    /**
     * 等抖动退避：上界按 2、4、8 秒增长并封顶，每次在上界的一半到上界之间等待。
     * 当前三次总尝试只产生两次等待（1～2 秒、2～4 秒），不会叠加登录重试预算。
     * 中断不是网络瞬时故障：立即停止、保留中断标记，不继续登录或发送下一页。
     */
    private void backoff(int failedAttempt) {
        requireNotInterrupted();
        long ceiling = Math.min(MAX_RETRY_BACKOFF_MILLIS,
                RETRY_BACKOFF_MILLIS << Math.min(Math.max(failedAttempt - 1, 0), 20));
        long half = ceiling / 2;
        long millis = half + jitter.applyAsLong(half + 1);
        try {
            retrySleeper.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closeSilently();
            throw new IllegalStateException("BaoStock 重试等待被中断", ex);
        }
    }

    /** 阻塞 Socket 读取仍由原读取超时兜底；在每次可检查边界停止后续工作。 */
    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            closeSilently();
            throw new IllegalStateException("BaoStock 操作已中断，停止后续请求");
        }
    }

    @FunctionalInterface
    interface RetrySleeper {
        /** 等待单位为毫秒；测试替身可记录预算或模拟中断，不发起真实网络动作。 */
        void sleep(long millis) throws InterruptedException;
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        if (socket == null || socket.isClosed()) return;
        try {
            rawRequest("02", "logout" + SEP + userId + SEP
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        } catch (Exception ignored) {
            // 网络异常时仍然关闭本地 Socket。
        } finally {
            closeSilently();
        }
    }

    private void closeSilently() {
        Socket previous = socket;
        socket = null;
        userId = LOGIN_USER;
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 传输层异常：连接断开、读写超时、响应错位等；触发重连与重试。 */
    private static final class TransportException extends RuntimeException {
        private TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Response(String messageType, List<String> parts, ObjectMapper objectMapper, String wireHeader) {
        /** 保留服务器的原始错误码；响应不完整时不猜测为可恢复的认证错误。 */
        private String errorCode() {
            return parts.isEmpty() ? "" : parts.getFirst();
        }

        private void requireSuccess() {
            if (parts.size() < 2 || !"0".equals(parts.get(0))) {
                String message = parts.size() > 1 ? parts.get(1) : "未知错误";
                throw new IllegalStateException("BaoStock 返回错误："
                        + (parts.isEmpty() ? "unknown" : parts.get(0)) + " " + message);
            }
        }

        /**
         * 新接口在解析任何行情前先验证专用信封，完整业务错误仍保留上游精确错误码。
         * 命令或会话不符时拒绝结果，防止连接错位的旧响应被误入库；不将其当作成功空表。
         */
        private void requireEnvelope(String expectedType, String expectedMethod, String expectedUser,
                                     int lastRequiredIndex) {
            requireSuccess();
            if (!expectedType.equals(messageType) || parts.size() <= lastRequiredIndex
                    || !expectedMethod.equals(parts.get(2)) || !expectedUser.equals(parts.get(3))) {
                throw new IllegalStateException("BaoStock 批量/因子响应类型、方法、会话或字段布局不匹配");
            }
            // 2026-11-18 独立只读探针确认：非压缩日历尾段为 CRC32(原始响应头 + 必需正文)。
            // 只接受紧邻最后业务字段的单个无符号 CRC，且必须实际计算匹配；其它非空尾段仍失败。
            // 旧版无 CRC 响应可保留空白/换行尾段。不能把“看起来是数字”误当作已校验的 CRC。
            boolean crcAccepted = hasVerifiedResponseCrc(lastRequiredIndex);
            for (int i = lastRequiredIndex + 1; i < parts.size(); i++) {
                if (crcAccepted && i == lastRequiredIndex + 1) continue;
                if (!parts.get(i).isBlank()) {
                    throw new IllegalStateException("BaoStock 响应包含未知尾段：" + tailShape(lastRequiredIndex));
                }
            }
        }

        /** 完整正文的 CRC 可区分合法协议尾段与串帧/正文损坏；不对源数值或字段做任何归一化。 */
        private boolean hasVerifiedResponseCrc(int lastRequiredIndex) {
            int crcIndex = lastRequiredIndex + 1;
            if (crcIndex >= parts.size()) return false;
            String value = parts.get(crcIndex).strip();
            if (!isUnsignedCrc(value)) return false;
            String body = String.join(SEP, parts.subList(0, crcIndex));
            return Long.parseLong(value) == crc32(wireHeader + body);
        }

        /**
         * 仅报告最多三个尾段的索引、长度、类型和 CRC 候选匹配结果，不返回任何原文/哈希/会话。
         * 仅 wireHeader+正文 已获在线证据并成为放行条件；其它候选只辅助核对压缩版本差异，仍不放行。
         */
        private String tailShape(int lastRequiredIndex) {
            String body = String.join(SEP, parts.subList(0, lastRequiredIndex + 1));
            List<String> descriptions = new ArrayList<>();
            int first = lastRequiredIndex + 1;
            for (int i = first; i < parts.size() && i < first + 3; i++) {
                String raw = parts.get(i);
                String trimmed = raw.strip();
                boolean unsigned32 = isUnsignedCrc(trimmed);
                String description = "index=" + i + ",chars=" + raw.length() + ",blank=" + raw.isBlank()
                        + ",uint32=" + unsigned32;
                if (unsigned32) {
                    long value = Long.parseLong(trimmed);
                    description += ",zero=" + (value == 0)
                            + ",crcHeaderBody=" + (value == crc32(wireHeader + body))
                            + ",crcBody=" + (value == crc32(body))
                            + ",crcHeaderBodySep=" + (value == crc32(wireHeader + body + SEP))
                            + ",crcBodySep=" + (value == crc32(body + SEP))
                            + ",crcPlainBytesHeader=" + (value == crc32(logicalHeader(body.getBytes(StandardCharsets.UTF_8).length) + body))
                            + ",crcPlainCharsHeader=" + (value == crc32(logicalHeader(body.length()) + body));
                }
                descriptions.add("[" + description + "]");
            }
            return "type=" + messageType + ",extraParts=" + (parts.size() - first) + "," + descriptions;
        }

        /** 仅诊断压缩前声明长度的可能取法；不会据此接受尚未经真实响应验证的 CRC 规则。 */
        private String logicalHeader(int bodyLength) {
            return wireHeader.substring(0, 11) + String.format("%010d", bodyLength);
        }

        private static boolean isUnsignedCrc(String value) {
            return value.matches("(?:0|[1-9][0-9]{0,9})") && Long.parseLong(value) <= 0xffff_ffffL;
        }

        private static long crc32(String text) {
            CRC32 crc = new CRC32();
            crc.update(text.getBytes(StandardCharsets.UTF_8));
            return crc.getValue();
        }

        private List<Map<String, String>> rows(int dataIndex, int fieldsIndex) {
            requireSuccess();
            if (parts.size() <= fieldsIndex || parts.get(dataIndex).isBlank()) return List.of();
            String[] fields = parts.get(fieldsIndex).split(",", -1);
            try {
                JsonNode records = objectMapper.readTree(parts.get(dataIndex)).path("record");
                List<Map<String, String>> result = new ArrayList<>();
                for (JsonNode row : records) {
                    Map<String, String> item = new LinkedHashMap<>();
                    for (int i = 0; i < fields.length && i < row.size(); i++) {
                        item.put(fields[i].trim(), row.get(i).asText());
                    }
                    result.add(item);
                }
                return result;
            } catch (IOException ex) {
                throw new IllegalStateException("BaoStock 返回数据解析失败：" + ex.getMessage(), ex);
            }
        }
    }
}
