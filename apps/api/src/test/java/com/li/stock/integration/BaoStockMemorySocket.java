package com.li.stock.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * 测试用纯内存 BaoStock 传输层：保留真实客户端的请求编码、响应解码与字段映射，
 * 仅替换 Socket 的收发字节，保证测试不会登录外部服务或影响正在执行的采集任务。
 */
public final class BaoStockMemorySocket extends Socket {
    private static final String SEP = "\1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Query> queries = new ArrayList<>();
    private InputStream response = InputStream.nullInputStream();

    /** 把内存 Socket 装入真实客户端，后续查询不经过 connect/login。 */
    public BaoStockClient client() throws ReflectiveOperationException {
        BaoStockClient client = new BaoStockClient(mapper, 100, 100);
        Field socketField = BaoStockClient.class.getDeclaredField("socket");
        socketField.setAccessible(true);
        socketField.set(client, this);
        return client;
    }

    public List<Query> queries() {
        return List.copyOf(queries);
    }

    @Override
    public InputStream getInputStream() {
        return response;
    }

    @Override
    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int value) {
                throw new UnsupportedOperationException("客户端应一次写入完整请求帧");
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                String packet = new String(bytes, offset, length, StandardCharsets.UTF_8);
                String[] parts = packet.substring(21).split(SEP, -1);
                if (!parts[0].equals("query_history_k_data_plus")) {
                    throw new IOException("测试不允许股票列表、登录或其它外部动作：" + parts[0]);
                }
                Query query = new Query(parts[4], List.of(parts[5].split(",")),
                        LocalDate.parse(parts[6]), LocalDate.parse(parts[7]), Integer.parseInt(parts[9]));
                queries.add(query);
                Map<String, String> values = sourceValues(query);
                List<String> row = query.fields().stream().map(name -> values.getOrDefault(name, "")).toList();
                String json = mapper.writeValueAsString(Map.of("record", List.of(row)));
                // 使用官方历史响应布局和 96/zlib 类型，使严格缓存校验与旧字段映射走同一真实协议。
                // 样本行情仍保持原值；这里只补齐方法、会话、分页、代码、日期、周期与复权参数回显。
                String body = String.join(SEP, "0", "success", parts[0], parts[1], parts[2], parts[3],
                        json, parts[4], parts[5], parts[6], parts[7], parts[8], parts[9]);
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) {
                    zip.write(body.getBytes(StandardCharsets.UTF_8));
                }
                byte[] payload = compressed.toByteArray();
                String header = "00.9.40" + SEP + "96" + SEP + String.format("%010d", payload.length);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(header.getBytes(StandardCharsets.UTF_8));
                frame.write(payload);
                frame.write("<![CDATA[]]>\n".getBytes(StandardCharsets.UTF_8));
                response = new ByteArrayInputStream(frame.toByteArray());
            }
        };
    }

    /**
     * ETF 样本采用实际探针确认的语义：即使调用方请求 flag=2，源数据仍返回 flag=3。
     * 故意保留异常 isST=1 与估值字段，验证生产代码不会把这些个股字段带入 ETF。
     */
    public static Map<String, String> sourceValues(Query query) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("date", query.from().toString());
        values.put("open", "1.0000");
        values.put("high", "1.2000");
        values.put("low", "0.9000");
        values.put("close", "1.1000");
        values.put("preclose", "1.0000");
        values.put("volume", "123400");
        values.put("amount", "135740.00");
        values.put("adjustflag", query.secid().startsWith("sh.58") ? "3" : String.valueOf(query.adjustFlag()));
        values.put("turn", "2.5000");
        values.put("tradestatus", "1");
        values.put("pctChg", "10.0000");
        values.put("isST", "1");
        values.put("peTTM", "25.0");
        values.put("pbMRQ", "2.0");
        values.put("psTTM", "3.0");
        values.put("pcfNcfTTM", "4.0");
        return values;
    }

    /** 解析后的真实请求参数，用于验证复权口径、证券类型和日期边界。 */
    public record Query(String secid, List<String> fields, LocalDate from, LocalDate to, int adjustFlag) {}
}
