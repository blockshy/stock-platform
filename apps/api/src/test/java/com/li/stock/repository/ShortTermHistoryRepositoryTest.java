package com.li.stock.repository;

import com.li.stock.domain.model.ForecastPrediction;
import com.li.stock.domain.model.ForecastRun;
import com.li.stock.mapper.ShortTermHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 全市场逐日预测的写入边界测试，不启动 Spring Boot，也不连接数据库。
 * Mapper 替身记录实际收到的每一批，事务测试使用真实 Spring 事务拦截器验证提交/回滚路径。
 */
class ShortTermHistoryRepositoryTest {

    @Test
    void predictionsRespectBatchBoundariesAndPreserveAffectedRowCounts() {
        for (int size : new int[]{0, 1, 499, 500, 501, 1000, 1001}) {
            RecordingMapper recording = new RecordingMapper();
            // MySQL 更新已有行时可能返回每行 2；仓储必须求和，不能改成输入行数。
            recording.affectedRowMultiplier = 2;
            List<ForecastPrediction> input = predictions(size);

            int saved = new ShortTermHistoryRepository(recording.mapper()).savePredictions(input);

            assertEquals(size * 2, saved, "受影响行数应保持原语义，输入行数=" + size);
            assertBatches(input, recording.predictionBatches);
        }
    }

    @Test
    void evaluationsPreserveBusinessRowCountAcrossBatches() {
        for (int size : new int[]{0, 500, 501, 1000, 1001}) {
            RecordingMapper recording = new RecordingMapper();
            recording.affectedRowMultiplier = 2;
            List<ForecastPrediction> input = predictions(size);

            int updated = new ShortTermHistoryRepository(recording.mapper()).updateEvaluations(input);

            // 回填接口原本报告处理条数，不能把 MySQL 的更新倍数带入业务返回值。
            assertEquals(size, updated);
            assertBatches(input, recording.evaluationBatches);
        }
    }

    @Test
    void emptyPredictionsDoNotCreateRunHeader() {
        RecordingMapper recording = new RecordingMapper();
        ForecastRun run = new ForecastRun();

        assertEquals(0, new ShortTermHistoryRepository(recording.mapper()).saveRunPredictions(run, List.of()));

        assertNull(run.getId());
        assertEquals(0, recording.headersCreated);
        assertEquals(0, recording.predictionBatches.size());
    }

    @Test
    void runHeaderAndAllPredictionBatchesCommitInOneTransaction() {
        RecordingMapper recording = new RecordingMapper();
        RecordingTransactionManager manager = new RecordingTransactionManager();
        ForecastRun run = new ForecastRun();
        List<ForecastPrediction> input = predictions(1001);

        int saved = transactionalRepository(recording.mapper(), manager).saveRunPredictions(run, input);

        assertEquals(1001, saved);
        assertEquals(42L, run.getId());
        assertBatches(input, recording.predictionBatches);
        input.forEach(row -> assertEquals(42L, row.getRunId()));
        assertEquals(1, manager.begins);
        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks);
    }

    @Test
    void failedMiddleBatchRollsBackRunAndStopsRemainingWrites() {
        RecordingMapper recording = new RecordingMapper();
        recording.failingPredictionBatch = 2;
        RecordingTransactionManager manager = new RecordingTransactionManager();

        assertThrows(DataAccessResourceFailureException.class,
                () -> transactionalRepository(recording.mapper(), manager)
                        .saveRunPredictions(new ForecastRun(), predictions(1001)));

        assertEquals(1, recording.headersCreated);
        assertEquals(2, recording.predictionBatches.size());
        assertEquals(1, manager.rollbacks);
        assertEquals(0, manager.commits);
    }

    /** 使用生产代码的 @Transactional 元数据，避免仅靠检查注解存在来断言事务有效。 */
    private ShortTermHistoryRepository transactionalRepository(ShortTermHistoryMapper mapper,
                                                               PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(new ShortTermHistoryRepository(mapper));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (ShortTermHistoryRepository) factory.getProxy();
    }

    private List<ForecastPrediction> predictions(int size) {
        return IntStream.range(0, size).mapToObj(index -> {
            ForecastPrediction row = new ForecastPrediction();
            row.setCode("测试行" + index);
            return row;
        }).toList();
    }

    /** 同时验证整批、余数批、空输入无调用，以及每行引用和顺序都没有丢失或重复。 */
    private void assertBatches(List<ForecastPrediction> input, List<List<ForecastPrediction>> calls) {
        assertEquals((input.size() + 499) / 500, calls.size());
        int index = 0;
        for (List<ForecastPrediction> batch : calls) {
            assertEquals(Math.min(500, input.size() - index), batch.size());
            for (ForecastPrediction row : batch) assertSame(input.get(index++), row);
        }
        assertEquals(input.size(), index);
    }

    /**
     * 使用 JDK 接口代理记录 Mapper 调用，无须动态附加 Java Agent，适用于受限 Windows 环境。
     * 本替身只提供测试实际使用的方法；意外调用其它数据库方法时立即失败。
     */
    private static final class RecordingMapper implements InvocationHandler {
        private final List<List<ForecastPrediction>> predictionBatches = new ArrayList<>();
        private final List<List<ForecastPrediction>> evaluationBatches = new ArrayList<>();
        private int headersCreated;
        private int affectedRowMultiplier = 1;
        private int failingPredictionBatch = -1;

        private ShortTermHistoryMapper mapper() {
            return (ShortTermHistoryMapper) Proxy.newProxyInstance(
                    ShortTermHistoryMapper.class.getClassLoader(), new Class<?>[]{ShortTermHistoryMapper.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("createRun")) {
                headersCreated++;
                ((ForecastRun) arguments[0]).setId(42L);
                return 1;
            }
            if (method.getName().equals("savePredictions") || method.getName().equals("saveEvaluations")) {
                List<ForecastPrediction> batch = (List<ForecastPrediction>) arguments[0];
                boolean predicting = method.getName().equals("savePredictions");
                (predicting ? predictionBatches : evaluationBatches).add(List.copyOf(batch));
                if (predicting && predictionBatches.size() == failingPredictionBatch) {
                    throw new DataAccessResourceFailureException("模拟第二批写入失败");
                }
                return batch.size() * affectedRowMultiplier;
            }
            throw new UnsupportedOperationException("测试未定义 Mapper 方法：" + method.getName());
        }
    }

    /** 记录真实 Spring 拦截器最终发起的事务动作，不模拟或绕过事务注解解析。 */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final TransactionStatus status = new SimpleTransactionStatus();
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begins++;
            return status;
        }

        @Override
        public void commit(TransactionStatus transaction) {
            assertSame(status, transaction);
            commits++;
        }

        @Override
        public void rollback(TransactionStatus transaction) {
            assertSame(status, transaction);
            rollbacks++;
        }
    }
}
