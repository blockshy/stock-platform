package com.li.stock.service;

import com.li.stock.integration.BaoStockClient;
import com.li.stock.repository.CrawlCheckpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 证券名单回溯边界回归：客户端全部使用内存替身，没有 Socket、登录、Spring 上下文或数据库。
 * 客户端自身的协议解析和三次预算由 integration 测试负责；这里验证服务层不会把该预算再乘以40。
 */
class CrawlTaskServiceStockListTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);
    private final CrawlCheckpointRepository checkpoints = mock(CrawlCheckpointRepository.class);
    private final CrawlTaskService service = new CrawlTaskService(null, null, null, null, checkpoints);

    @AfterEach
    void closeUnusedExecutor() {
        service.shutdown();
    }

    @Test
    void transportBudgetBusinessRateLimitAndMalformedResponseDoNotTriggerDateFallback() throws Exception {
        List<IllegalStateException> failures = List.of(
                new IllegalStateException("BaoStock 请求尝试 3 次仍失败", new IOException("模拟传输预算耗尽")),
                new IllegalStateException("BaoStock 返回错误：模拟限频业务码"),
                new IllegalStateException("BaoStock 证券名单响应缺少 record"));
        for (IllegalStateException failure : failures) {
            BaoStockClient client = mock(BaoStockClient.class);
            when(client.queryAllStockVerified(DAY)).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> latest(client)),
                    "必须保留原失败原因，而不是更换日期后包装成没有股票名单");
            verify(client).queryAllStockVerified(DAY);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    void onlyCertifiedEmptyListsMoveBackOneDateAndStopAtFirstAvailableUniverse() throws Exception {
        BaoStockClient client = mock(BaoStockClient.class);
        List<Map<String, String>> expected = List.of(Map.of("code", "sh.588000", "code_name", "测试ETF"));
        when(client.queryAllStockVerified(DAY)).thenReturn(List.of());
        when(client.queryAllStockVerified(DAY.minusDays(1))).thenReturn(List.of());
        when(client.queryAllStockVerified(DAY.minusDays(2))).thenReturn(expected);

        assertEquals(expected, latest(client));
        InOrder order = inOrder(client);
        order.verify(client).queryAllStockVerified(DAY);
        order.verify(client).queryAllStockVerified(DAY.minusDays(1));
        order.verify(client).queryAllStockVerified(DAY.minusDays(2));
        verifyNoMoreInteractions(client);
    }

    @Test
    void certifiedEmptyLookbackRemainsBoundedToFortyDates() throws Exception {
        BaoStockClient client = mock(BaoStockClient.class);
        when(client.queryAllStockVerified(any(LocalDate.class))).thenReturn(List.of());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> latest(client));
        assertEquals("最近 40 天均已确认 BaoStock 股票列表为空", failure.getMessage());
        verify(client, times(40)).queryAllStockVerified(any(LocalDate.class));
        verify(client).queryAllStockVerified(DAY.minusDays(39));
    }

    @Test
    void pauseBeforeUniverseLookupDoesNotStartAnySourceRequest() throws Exception {
        BaoStockClient client = mock(BaoStockClient.class);
        when(checkpoints.pauseRequested(anyLong())).thenReturn(true);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> latest(client));
        assertEquals("PauseRequested", failure.getClass().getSimpleName());
        verifyNoInteractions(client);
    }

    /** 直接调用纯控制路径，避免为了测试启动异步任务或扩展生产公共接口。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> latest(BaoStockClient client) throws Exception {
        Method method = CrawlTaskService.class.getDeclaredMethod("queryLatestStockList", BaoStockClient.class,
                LocalDate.class, long.class);
        method.setAccessible(true);
        try {
            return (List<Map<String, String>>) method.invoke(service, client, DAY, 1L);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }
}
