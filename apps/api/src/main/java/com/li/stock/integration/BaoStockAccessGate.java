package com.li.stock.integration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本平台 BaoStock 会话的跨线程、跨进程互斥入口。
 *
 * <p>手动采集、定时采集及独立验证工具必须在登录前取得同一文件锁，并在客户端
 * 登出之后释放。锁不阻塞等待：发生竞争时明确拒绝新任务，避免第二次登录使正在
 * 使用的匿名会话失效。默认使用当前 Windows 用户共享的临时目录，与工作目录无关。
 * 本锁不能约束旧版本程序、第三方 SDK 或另一台电脑；在线验证仍须核验旧任务空闲。</p>
 */
public final class BaoStockAccessGate {
    private BaoStockAccessGate() {}

    /** 获取会话租约。可通过 JVM 属性指定统一锁文件，但所有参与进程必须使用同一路径。 */
    public static Lease acquire() {
        String configured = System.getProperty("stock.baostock.lock-file", "").trim();
        Path path = configured.isEmpty()
                ? Path.of(System.getProperty("java.io.tmpdir"), "stock-platform-baostock-session.lock")
                : Path.of(configured);
        if (!path.isAbsolute()) throw new IllegalArgumentException("BaoStock 共享锁必须使用绝对路径，不能因工作目录不同产生两把锁");
        return acquire(path);
    }

    /** 包内测试使用独立临时文件，不占用真实运行锁。 */
    static Lease acquire(Path path) {
        FileChannel channel = null;
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            channel = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("BaoStock 已有采集或验证会话，请等待其结束后重试");
            return new Lease(channel, lock);
        } catch (IOException | IllegalStateException ex) {
            if (channel != null) {
                try { channel.close(); } catch (IOException ignored) { /* 获取失败仍必须释放本地句柄。 */ }
            }
            throw new IllegalStateException("无法独占 BaoStock 会话，未启动新连接", ex);
        }
    }

    /** 持有至客户端关闭；不删除锁文件，防止另一进程打开旧文件后产生两个不同的锁对象。 */
    public static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try { lock.release(); }
            catch (IOException ignored) { /* close channel 会继续释放操作系统锁，不能中断后续资源清理。 */ }
            finally {
                try { channel.close(); } catch (IOException ignored) { /* 不把清理异常覆盖到原采集异常。 */ }
            }
        }
    }
}
