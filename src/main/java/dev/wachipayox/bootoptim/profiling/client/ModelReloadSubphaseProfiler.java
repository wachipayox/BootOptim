package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Diagnostic-only timings inside the model/atlas preparation branch of the client resource reload. */
public final class ModelReloadSubphaseProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelReload");

    private ModelReloadSubphaseProfiler() {
    }

    public static boolean enabled() {
        return StartupProfiler.isEnabled();
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void observeFuture(String phase, long startedNanos, CompletableFuture<?> future) {
        if (!enabled() || future == null) {
            return;
        }
        future.whenComplete((value, failure) -> log(
                phase,
                startedNanos,
                failure,
                count(value),
                "future"));
    }

    public static void observeFutureMap(String phase, long startedNanos, Map<?, ? extends CompletableFuture<?>> futures) {
        if (!enabled() || futures == null) {
            return;
        }
        CompletableFuture<?>[] values = futures.values().toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(values).whenComplete((ignored, failure) -> log(
                phase,
                startedNanos,
                failure,
                futures.size(),
                "futures"));
    }

    public static void endSync(String phase, long startedNanos) {
        if (!enabled() || startedNanos <= 0L) {
            return;
        }
        log(phase, startedNanos, null, -1, "sync");
    }

    private static void log(String phase, long startedNanos, Throwable failure, int entries, String kind) {
        LOGGER.info(
                "BOOTOPTIM_MODEL_RELOAD phase={} kind={} elapsed_ms={} entries={} result={}",
                phase,
                kind,
                format((System.nanoTime() - startedNanos) / 1_000_000.0),
                entries,
                failure == null ? "success" : "failed");
    }

    private static int count(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        return -1;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
