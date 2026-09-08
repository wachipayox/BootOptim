package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime hooks injected at NeoForge's call into FML mod construction; trace-only and fail-open. */
public final class FmlLoadingTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong GATHER_TASK = new AtomicLong();

    private FmlLoadingTraceHooks() {}

    public static void beginGatherAndInitialize() {
        if (!TRACE.isEnabled() || GATHER_TASK.get() != 0L) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "fml_gather_and_initialize_mods", 0L, dependencies, null, null, -1L);
            if (!GATHER_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                // A second invocation is not expected on the client startup path. Close the redundant diagnostic
                // task rather than leaving an unbalanced pair; never affect the underlying load.
                TRACE.endTask(taskId, "fml_gather_and_initialize_mods", -1L, "duplicate_gather_hook");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    public static void endGatherAndInitialize() {
        if (!TRACE.isEnabled()) return;
        try {
            long taskId = GATHER_TASK.getAndSet(0L);
            if (taskId != 0L) {
                TRACE.endTask(taskId, "fml_gather_and_initialize_mods", -1L, "fml_gather_complete");
            }
        } catch (Throwable ignored) {
        }
    }
}
