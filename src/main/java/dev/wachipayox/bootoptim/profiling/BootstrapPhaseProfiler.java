package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/** Profiling-only wall-clock timings for the vanilla bootstrap sequence. */
public final class BootstrapPhaseProfiler {
    private static final Map<String, Long> START_NANOS = new ConcurrentHashMap<>();

    private BootstrapPhaseProfiler() {
    }

    public static void begin(String phase) {
        if (!StartupProfiler.isEnabled()) {
            return;
        }
        START_NANOS.put(phase, System.nanoTime());
    }

    public static void end(String phase) {
        if (!StartupProfiler.isEnabled()) {
            return;
        }
        Long started = START_NANOS.remove(phase);
        if (started == null) {
            return;
        }
        long durationNanos = System.nanoTime() - started;
        logger().info(
                "BOOTOPTIM_BOOTSTRAP phase={} duration_ms={} uptime_ms={} thread={}",
                phase,
                durationNanos / 1_000_000.0,
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Thread.currentThread().getName());
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }
}
