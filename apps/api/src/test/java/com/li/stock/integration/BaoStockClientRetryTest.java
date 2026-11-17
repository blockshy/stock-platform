package com.li.stock.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议级重试回归：Socket 的 connect、收发、超时与关闭均被纯内存替身接管。
 * 等待与随机源也注入，验证失败预算和恢复行为时不休眠、不连接真实行情服务。
 */
class BaoStockClientRetryTest {
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 5);

    @Test
    void connectionFailuresCloseEveryFreshSocketAndStopWithinThreeAttempts() {
        List<ScriptedSocket> sockets = List.of(new ScriptedSocket(), new ScriptedSocket(), new ScriptedSocket());
        sockets.forEach(socket -> socket.failConnect = true);
        List<Long> waits = new ArrayList<>();
        BaoStockClient client = client(sockets, waits::add, bound -> 0);

        assertThrows(IllegalStateException.class, client::login);
        assertTrue(sockets.stream().allMatch(socket -> socket.closed), "connect 失败的新连接也必须释放");
        assertEquals(List.of(1000L, 2000L), waits, "仅两次等待，取各退避窗口的下界");
        assertEquals(3, sockets.stream().mapToInt(socket -> socket.connects).sum());
    }

    @Test
    void timeoutConfigurationFailuresAlsoReleaseTheHalfInitializedSocket() {
        List<ScriptedSocket> sockets = List.of(new ScriptedSocket(), new ScriptedSocket(), new ScriptedSocket());
        sockets.forEach(socket -> socket.failTimeout = true);
        List<Long> waits = new ArrayList<>();
        BaoStockClient client = client(sockets, waits::add, bound -> bound - 1);

        assertThrows(IllegalStateException.class, client::login);
        assertTrue(sockets.stream().allMatch(socket -> socket.closed));
        assertEquals(List.of(2000L, 4000L), waits, "抖动上界仍保持总等待最多六秒");
    }

    @Test
    void aFailedSecondPageDoesNotReplayTheAlreadySuccessfulFirstPage() {
        ScriptedSocket first = new ScriptedSocket();
        first.pages = Map.of(1, Reply.success(2000), 2, Reply.transport());
        ScriptedSocket recovered = new ScriptedSocket();
        recovered.pages = Map.of(2, Reply.success(1));
        List<Long> waits = new ArrayList<>();
        BaoStockClient client = client(List.of(first, recovered), waits::add, bound -> 0);
        client.login();

        List<Map<String, String>> rows = client.queryHistory("sz.000001", FROM, TO, "2");
        assertEquals(2001, rows.size());
        assertEquals(List.of(1, 2), first.requestedPages);
        assertEquals(List.of(2), recovered.requestedPages, "恢复只重发当前页，不能重发整只证券");
        assertEquals(1, recovered.logins);
        assertTrue(first.closed);
        assertFalse(recovered.closed);
    }

    @Test
    void sessionExpiryAndTransportFailureShareOneBudget() {
        ScriptedSocket first = new ScriptedSocket();
        first.pages = Map.of(1, Reply.business("10001001"));
        ScriptedSocket second = new ScriptedSocket();
        second.failLoginTransport = true;
        ScriptedSocket third = new ScriptedSocket();
        third.pages = Map.of(1, Reply.business("10001001"));
        List<Long> waits = new ArrayList<>();
        BaoStockClient client = client(List.of(first, second, third), waits::add, bound -> 0);
        client.login();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.queryHistory("sz.000001", FROM, TO, "2"));
        assertTrue(failure.getMessage().contains("10001001"));
        assertEquals(2, waits.size(), "不能为登录失败另开三次内层重试");
        assertTrue(List.of(first, second, third).stream().allMatch(socket -> socket.closed));
    }

    @Test
    void loginBusinessFailureIsNotRetried() {
        ScriptedSocket socket = new ScriptedSocket();
        socket.loginCode = "10002001";
        List<Long> waits = new ArrayList<>();
        BaoStockClient client = client(List.of(socket), waits::add, bound -> 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class, client::login);
        assertTrue(failure.getMessage().contains("10002001"));
        assertTrue(waits.isEmpty());
        assertTrue(socket.closed);
    }

    @Test
    void interruptedBackoffStopsBeforeAnotherConnectionAndKeepsInterruptFlag() {
        ScriptedSocket socket = new ScriptedSocket();
        socket.failConnect = true;
        BaoStockClient client = client(List.of(socket), millis -> { throw new InterruptedException("test interruption"); }, bound -> 0);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, client::login);
            assertTrue(failure.getMessage().contains("中断"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(socket.closed);
        } finally {
            Thread.interrupted(); // 清理测试线程标记，不能影响随后其它 JUnit 测试。
        }
    }

    @Test
    void anAlreadyInterruptedQueryDoesNotWriteOrReconnect() {
        ScriptedSocket socket = new ScriptedSocket();
        BaoStockClient client = client(List.of(socket), millis -> {}, bound -> 0);
        client.login();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> client.queryHistory("sz.000001", FROM, TO, "2"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(socket.requestedPages.isEmpty());
            assertTrue(socket.closed, "中断后应释放原会话连接");
        } finally {
            Thread.interrupted();
        }
    }

    /** Socket 列表用完即失败，任何意外增加的尝试都不能静默通过。 */
    private BaoStockClient client(List<ScriptedSocket> sockets, BaoStockClient.RetrySleeper sleeper,
                                  LongUnaryOperator jitter) {
        AtomicInteger next = new AtomicInteger();
        return new BaoStockClient(new ObjectMapper(), 100, 100, () -> {
            int index = next.getAndIncrement();
            if (index >= sockets.size()) throw new AssertionError("发生额外连接，超过预期预算");
            return sockets.get(index);
        }, sleeper, jitter);
    }

    private record Reply(String code, int rowCount, boolean broken) {
        static Reply success(int rows) { return new Reply("0", rows, false); }
        static Reply business(String code) { return new Reply(code, 0, false); }
        static Reply transport() { return new Reply("", 0, true); }
    }

    /** 在完整请求编码后按命令与页码提供响应；传输故障用提前 EOF 模拟。 */
    private static final class ScriptedSocket extends Socket {
        private InputStream response = InputStream.nullInputStream();
        private Map<Integer, Reply> pages = Map.of(1, Reply.success(0));
        private final List<Integer> requestedPages = new ArrayList<>();
        private String loginCode = "0";
        private boolean failConnect, failTimeout, failLoginTransport, closed;
        private int connects, logins;

        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connects++;
            if (failConnect) throw new IOException("simulated connect failure");
        }
        @Override public void setSoTimeout(int timeout) throws SocketException {
            if (failTimeout) throw new SocketException("simulated timeout configuration failure");
        }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public InputStream getInputStream() { return response; }

        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { throw new UnsupportedOperationException(); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    String packet = new String(bytes, offset, length, StandardCharsets.UTF_8);
                    List<String> body = Arrays.asList(packet.substring(21).split("\1", -1));
                    if (body.getFirst().equals("login")) {
                        logins++;
                        response = failLoginTransport ? InputStream.nullInputStream()
                                : frame("00", String.join("\1", loginCode, "test login", "", "session-" + logins));
                        return;
                    }
                    if (!body.getFirst().equals("query_history_k_data_plus")) throw new AssertionError("不支持的测试命令");
                    int page = Integer.parseInt(body.get(2));
                    requestedPages.add(page);
                    Reply reply = pages.get(page);
                    if (reply == null) throw new AssertionError("重发了不应请求的页：" + page);
                    if (reply.broken()) { response = InputStream.nullInputStream(); return; }
                    List<List<String>> rows = new ArrayList<>();
                    for (int index = 0; index < reply.rowCount(); index++) rows.add(List.of("2024-01-02", "1.23"));
                    String json = new ObjectMapper().writeValueAsString(Map.of("record", rows));
                    response = frame("95", String.join("\1", reply.code(), "test query", "", "", "", "", json, "", "date,close"));
                }
            };
        }

        private InputStream frame(String type, String body) {
            String header = "00.9.40\1" + type + "\1" + String.format("%010d", body.getBytes(StandardCharsets.UTF_8).length);
            return new ByteArrayInputStream((header + body + "<![CDATA[]]>\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
