package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Diagnostic-only observers for aggregate ModelManager/atlas reload boundaries. */
public final class ResourceReloadBoundaryProfiler {
    private ResourceReloadBoundaryProfiler() {}

    public static VarianceProbe.Stamp start(String phase) {
        return VarianceProbe.start(phase);
    }

    public static void endSync(String phase, VarianceProbe.Stamp started) {
        VarianceProbe.finish(phase, started);
    }

    public static void observeFuture(String phase, VarianceProbe.Stamp started, CompletableFuture<?> future) {
        if (started == null || future == null) {
            return;
        }
        future.whenComplete((value, failure) -> VarianceProbe.finish(
                phase,
                resultSubject(failure, count(value)),
                started));
    }

    public static void observeFutureMap(
            String phase,
            VarianceProbe.Stamp started,
            Map<?, ? extends CompletableFuture<?>> futures) {
        if (started == null || futures == null) {
            return;
        }
        CompletableFuture<?>[] values = futures.values().toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(values).whenComplete((ignored, failure) -> VarianceProbe.finish(
                phase,
                resultSubject(failure, futures.size()),
                started));
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

    private static String resultSubject(Throwable failure, int entries) {
        String result = failure == null ? "success" : failure.getClass().getSimpleName();
        return "result_" + result + "_entries_" + entries;
    }
}
