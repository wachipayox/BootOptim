package dev.wachipayox.bootoptim.profiling.client;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only, low-cardinality timing for the ModelManager/atlas preparation path.
 *
 * <p>No resource is wrapped and no executor/future is replaced. The profiler attaches completion
 * observers only to the aggregate futures that already exist in stock 1.21.1.</p>
 */
public final class ResourceReloadBoundaryProfiler {
    public static final String PROPERTY = "boot_optim.profileResourceReloadBoundaries";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ReloadBoundary");
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean()
            instanceof OperatingSystemMXBean bean ? bean : null;

    private ResourceReloadBoundaryProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static Stamp start() {
        return ENABLED ? new Stamp(System.nanoTime(), processCpuNanos()) : null;
    }

    public static void endSync(String phase, Stamp started) {
        if (started != null) {
            log(phase, "sync_complete", started, null, -1);
        }
    }

    public static void observeFuture(String phase, Stamp started, CompletableFuture<?> future) {
        if (started == null || future == null) {
            return;
        }
        future.whenComplete((value, failure) -> log(phase, "future_complete", started, failure, count(value)));
    }

    public static void observeFutureMap(String phase, Stamp started, Map<?, ? extends CompletableFuture<?>> futures) {
        if (started == null || futures == null) {
            return;
        }
        CompletableFuture<?>[] values = futures.values().toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(values).whenComplete((ignored, failure) ->
                log(phase, "future_complete", started, failure, futures.size()));
    }

    private static void log(String phase, String event, Stamp started, Throwable failure, int entries) {
        long now = System.nanoTime();
        long cpuNow = processCpuNanos();
        double cpuDeltaMs = started.cpuNanos < 0L || cpuNow < 0L
                ? -1.0D
                : (cpuNow - started.cpuNanos) / 1_000_000.0D;
        LOGGER.info(
                "BOOTOPTIM_RELOAD_BOUNDARY phase={} event={} elapsed_ms={} uptime_ms={} process_cpu_ms={} cpu_delta_ms={} entries={} result={}",
                phase,
                event,
                format((now - started.wallNanos) / 1_000_000.0D),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                format(cpuNow < 0L ? -1.0D : cpuNow / 1_000_000.0D),
                format(cpuDeltaMs),
                entries,
                failure == null ? "success" : failure.getClass().getName());
    }

    private static int count(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        return -1;
    }

    private static long processCpuNanos() {
        return OS == null ? -1L : OS.getProcessCpuTime();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public record Stamp(long wallNanos, long cpuNanos) {}
}
