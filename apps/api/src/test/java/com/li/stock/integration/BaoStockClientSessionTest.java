package com.li.stock.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用协议级内存响应验证会话失效恢复；任何测试都不会连接真实 BaoStock 服务。 */
class BaoStockClientSessionTest {
    private static final LocalDate FROM = LocalDate.of(2023, 11, 17);
    private static final LocalDate TO = LocalDate.of(2026, 11, 16);

    @Test
    void expiredSessionRelogsInAndRewritesOnlyTheSessionField() {
        List<SessionSocket> sockets = new ArrayList<>();
        BaoStockClient client = client(sockets, List.of("10001001", "0"));
        client.login();
        assertTrue(client.queryHistory("sh.588000", FROM, TO, "3").isEmpty());

        assertEquals(2, sockets.size());
        assertEquals("anonymous", sockets.get(0).loginUser);
        assertEquals("anonymous", sockets.get(1).loginUser, "会话 token 不能被用作下一次登录账号");
        assertEquals("session-0", sockets.get(0).history.get(1));
        assertEquals("session-1", sockets.get(1).history.get(1), "恢复后必须替换已组装消息中的旧 token");
        assertEquals(sockets.get(0).history.subList(2, 10), sockets.get(1).history.subList(2, 10),
                "重试同一页不能改变页码、字段、日期或复权口径");
        assertTrue(sockets.getFirst().isClosed());
    }

    @Test
    void repeatedNotLoggedInStopsAfterThreeRequestsAndKeepsTheExactCode() {
        List<SessionSocket> sockets = new ArrayList<>();
        BaoStockClient client = client(sockets, List.of("10001001", "10001001", "10001001"));
        client.login();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.queryHistory("sh.588000", FROM, TO, "3"));

        assertEquals(3, sockets.size(), "认证恢复必须共用请求的三次总上限");
        assertTrue(sockets.stream().allMatch(socket -> socket.history != null));
        assertTrue(failure.getMessage().contains("10001001"));
        assertTrue(failure.getMessage().contains("用户未登录"));
        assertTrue(sockets.getLast().isClosed());
    }

    @Test
    void otherBusinessErrorsNeverTriggerAReconnection() {
        List<SessionSocket> sockets = new ArrayList<>();
        BaoStockClient client = client(sockets, List.of("10002001"));
        client.login();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.queryHistory("sh.588000", FROM, TO, "3"));

        assertEquals(1, sockets.size());
        assertTrue(failure.getMessage().contains("10002001"));
    }

    /** 每次重连生成一个独立 Socket/token；超过预期次数立即失败，防止测试掩盖无限重试。 */
    private BaoStockClient client(List<SessionSocket> sockets, List<String> historyCodes) {
        return new BaoStockClient(new ObjectMapper(), 100, 100, () -> {
            int index = sockets.size();
            if (index >= historyCodes.size()) throw new AssertionError("发生了超出上限的额外连接");
            SessionSocket socket = new SessionSocket("session-" + index, historyCodes.get(index));
            sockets.add(socket);
            return socket;
        });
    }

    /** 实现实际登录/历史响应帧，但 connect、超时设置及 close 都只操作内存状态。 */
    private static final class SessionSocket extends Socket {
        private final String token;
        private final String historyCode;
        private InputStream response = InputStream.nullInputStream();
        private boolean closed;
        private String loginUser;
        private List<String> history;

        private SessionSocket(String token, String historyCode) {
            this.token = token;
            this.historyCode = historyCode;
        }

        @Override public void connect(SocketAddress endpoint, int timeout) { }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        @Override public InputStream getInputStream() { return response; }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { throw new UnsupportedOperationException(); }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    String packet = new String(bytes, offset, length, StandardCharsets.UTF_8);
                    List<String> body = Arrays.asList(packet.substring(21).split("\1", -1));
                    String reply;
                    if (body.getFirst().equals("login")) {
                        loginUser = body.get(1);
                        reply = String.join("\1", "0", "success", "", token);
                    } else {
                        history = List.copyOf(body);
                        reply = String.join("\1", historyCode,
                                historyCode.equals("10001001") ? "用户未登录" : "测试业务响应",
                                "", "", "", "", "{\"record\":[]}", "", body.get(5));
                    }
                    String header = "00.9.40\1" + (body.getFirst().equals("login") ? "00" : "95") + "\1"
                            + String.format("%010d", reply.getBytes(StandardCharsets.UTF_8).length);
                    response = new ByteArrayInputStream((header + reply + "<![CDATA[]]>\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
            };
        }
    }
}
