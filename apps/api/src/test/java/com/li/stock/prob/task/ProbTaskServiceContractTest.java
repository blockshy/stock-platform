package com.li.stock.prob.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Java → Python 进程契约回归测试。
 * 不创建 Spring 上下文；JdbcTemplate、结果仓库、ProcessBuilder 和 Process 全部替换为 mock。
 * 构造器拦截在调用 execute 之前建立，真实 start() 永远不会执行，不连接任何数据库。
 */
class ProbTaskServiceContractTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProbRepository repository = mock(ProbRepository.class);
    private final ProbTaskService service = new ProbTaskService(jdbc, repository);

    @AfterEach
    void releaseUnusedExecutor() {
        // 测试直接调用同步执行边界，没有向线程池提交任务；仍关闭资源避免 JVM 残留。
        service.shutdown();
    }

    /** 通过反射只设置配置注入点，不修改生产任务实现或统计参数。 */
    private void configure(String pythonArgs) {
        ReflectionTestUtils.setField(service, "python", "D:/test engine/.venv/Scripts/python.exe");
        ReflectionTestUtils.setField(service, "pythonArgs", pythonArgs);
        ReflectionTestUtils.setField(service, "workdir", "D:/test engine");
        // execute 自身负责进入时加一、finally 减一；保留构造后的零值，不能预先重复计数。
        assertEquals(0, service.runningCount());
    }

    /**
     * 执行真实任务方法但拦截进程创建；记录实际构造参数与环境，验证带空格路径作为单一参数传递。
     * 测试的退出码与输出均为内存样本，不启动 Python，不依赖本机安装或网络状态。
     */
    private Captured execute(String command, Map<String, Object> options, int exitCode) throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(
                "{\"code\":0,\"data\":{}}\n".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(60, TimeUnit.MINUTES)).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
        List<String> arguments = new ArrayList<>();
        Map<String, String> environment = new HashMap<>();
        try (MockedConstruction<ProcessBuilder> builders = mockConstruction(ProcessBuilder.class,
                (builder, context) -> {
                    @SuppressWarnings("unchecked")
                    List<String> actual = (List<String>) context.arguments().getFirst();
                    arguments.addAll(actual);
                    when(builder.directory(any())).thenReturn(builder);
                    when(builder.redirectErrorStream(true)).thenReturn(builder);
                    when(builder.environment()).thenReturn(environment);
                    when(builder.start()).thenReturn(process);
                })) {
            ReflectionTestUtils.invokeMethod(service, "execute", 17L, command, options);
            assertEquals(1, builders.constructed().size());
            verify(builders.constructed().getFirst()).directory(new java.io.File("D:/test engine"));
            verify(builders.constructed().getFirst()).redirectErrorStream(true);
            verify(repository).invalidateOverview();
        }
        return new Captured(arguments, environment);
    }

    @Test
    void dailyPassesRunIdentityDateAndFlagsWithoutLimitingFullMarket() throws Exception {
        configure("");
        Captured result = execute("daily", Map.of("asof", "2026-11-16", "limitStocks", 0,
                "force", true, "noResume", true, "fast", true), 0);
        assertEquals(List.of("D:/test engine/.venv/Scripts/python.exe", "-m", "engine.cli", "daily",
                "--run-id", "17", "--asof", "2026-11-16", "--force", "--no-resume", "--fast"),
                result.arguments());
        assertFalse(result.arguments().contains("--limit-stocks"));
        assertEquals("utf-8", result.environment().get("PYTHONIOENCODING"));
        assertEquals(0, service.runningCount());
    }

    @Test
    void explicitStockLimitAndLauncherArgumentKeepExistingCliSemantics() throws Exception {
        configure("-3");
        Captured result = execute("predict", Map.of("asof", "latest", "limitStocks", 5), 0);
        assertEquals(List.of("D:/test engine/.venv/Scripts/python.exe", "-3", "-m", "engine.cli",
                "predict", "--run-id", "17", "--limit-stocks", "5"), result.arguments());
        assertFalse(result.arguments().contains("--asof"));
    }

    @Test
    void successfulProcessCannotOverwriteEngineFailedTerminalState() throws Exception {
        configure("");
        execute("daily", Map.of(), 0);
        // Python 可能正常退出但已写 FAILED。Java 成功兜底只能更新仍处于 RUNNING 的任务。
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(17L));
        assertTrue(sql.getValue().contains("status='RUNNING'"));
    }

    @Test
    void nonzeroProcessExitRecordsFailureForTheSameRun() throws Exception {
        configure("");
        execute("daily", Map.of(), 1);
        verify(jdbc).update(anyString(), eq("FAILED"), anyString(), eq(17L));
    }

    private record Captured(List<String> arguments, Map<String, String> environment) { }
}
