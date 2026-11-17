package com.li.stock.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立文件核验会话互斥，不接触默认运行锁，也不连接任何行情服务。 */
class BaoStockAccessGateTest {
    @TempDir Path directory;

    @Test void rejectsOverlappingSessionAndReleasesAfterClose() {
        Path path = directory.resolve("session.lock");
        try (var first = BaoStockAccessGate.acquire(path)) {
            assertThrows(IllegalStateException.class, () -> BaoStockAccessGate.acquire(path));
        }
        assertDoesNotThrow(() -> { try (var second = BaoStockAccessGate.acquire(path)) { /* 可重新取得 */ } });
    }

    @Test void honorsExternalFileLockWithoutDeletingIt() throws Exception {
        Path path = directory.resolve("external.lock");
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var external = channel.lock()) {
            assertThrows(IllegalStateException.class, () -> BaoStockAccessGate.acquire(path));
            assertTrue(external.isValid());
        }
    }

    @Test void closeIsIdempotentAndDoesNotReleaseAnotherLease() {
        Path path = directory.resolve("repeat.lock");
        var first = BaoStockAccessGate.acquire(path);
        first.close();
        try (var second = BaoStockAccessGate.acquire(path)) {
            first.close();
            assertThrows(IllegalStateException.class, () -> BaoStockAccessGate.acquire(path));
        }
    }
}
