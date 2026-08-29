package dev.wachipayox.bootoptim.bootstrap;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes optional scan-cache writes away from FML's scan workers.
 *
 * <p>A single low-priority daemon avoids turning cache persistence into a second
 * burst of competing disk I/O during cold startup. Callers must treat failed
 * submission or failed writes as cache misses, never as startup failures.</p>
 */
final class AsyncScanCacheWriter {
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName("bootoptim-scan-cache-writer");
        try {
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
        } catch (SecurityException ignored) {
            // Daemon/priority settings are best effort; cache correctness does not depend on them.
        }
        return thread;
    });

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                WRITER.shutdown();
                try {
                    if (!WRITER.awaitTermination(10, TimeUnit.SECONDS)) {
                        WRITER.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    WRITER.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }, "bootoptim-scan-cache-shutdown"));
        } catch (IllegalStateException | SecurityException ignored) {
            // Shutdown persistence is best effort and must never interfere with startup.
        }
    }

    private AsyncScanCacheWriter() {}

    static boolean submit(Runnable write) {
        PENDING.incrementAndGet();
        try {
            WRITER.execute(() -> {
                try {
                    write.run();
                } finally {
                    PENDING.decrementAndGet();
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            PENDING.decrementAndGet();
            return false;
        }
    }

    static int pendingWrites() {
        return PENDING.get();
    }

    static boolean awaitIdle(Duration timeout) throws InterruptedException {
        Future<?> fence;
        try {
            fence = WRITER.submit(() -> {});
        } catch (RuntimeException rejected) {
            return PENDING.get() == 0;
        }

        try {
            fence.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return PENDING.get() == 0;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }
}
