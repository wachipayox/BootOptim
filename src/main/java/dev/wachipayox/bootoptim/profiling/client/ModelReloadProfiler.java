package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fine-grained client model reload timings used only when startup profiling is enabled.
 *
 * The timings deliberately attach to the futures returned by ModelManager so asynchronous
 * preparation is measured to actual completion rather than merely timing future creation.
 */
public final class ModelReloadProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelProfiler");
    private static final ConcurrentMap<String, Long> START_NANOS = new ConcurrentHashMap<>();

    private ModelReloadProfiler() {
    }

    public static void begin(String phase) {
        if (!StartupProfiler.isEnabled()) {
            return;
        }
        START_NANOS.put(phase, System.nanoTime());
        LOGGER.info("BOOTOPTIM_RESOURCE phase={}_start uptime_ms={} thread={}",
                phase,
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Thread.currentThread().getName());
    }

    public static void end(String phase, Throwable failure) {
        if (!StartupProfiler.isEnabled()) {
            return;
        }
        Long started = START_NANOS.remove(phase);
        if (started == null) {
            return;
        }
        record(phase, System.nanoTime() - started, failure, -1);
    }

    public static void record(String phase, long elapsedNanos, Throwable failure, int operations) {
        if (!StartupProfiler.isEnabled()) {
            return;
        }
        double elapsedMs = elapsedNanos / 1_000_000.0;
        if (operations >= 0) {
            LOGGER.info("BOOTOPTIM_RESOURCE phase={}_end elapsed_ms={} uptime_ms={} thread={} result={} operations={}",
                    phase,
                    String.format(java.util.Locale.ROOT, "%.3f", elapsedMs),
                    ManagementFactory.getRuntimeMXBean().getUptime(),
                    Thread.currentThread().getName(),
                    failure == null ? "success" : "failed",
                    operations);
        } else {
            LOGGER.info("BOOTOPTIM_RESOURCE phase={}_end elapsed_ms={} uptime_ms={} thread={} result={}",
                    phase,
                    String.format(java.util.Locale.ROOT, "%.3f", elapsedMs),
                    ManagementFactory.getRuntimeMXBean().getUptime(),
                    Thread.currentThread().getName(),
                    failure == null ? "success" : "failed");
        }
    }
}
