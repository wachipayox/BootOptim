package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace-only boundary from BootOptim's transformation-service transformer callback to the first
 * observed Minecraft bootstrap entry.
 *
 * <p>The callback itself is recorded as a normal same-thread task so it can participate in the
 * dependency DAG. The longer callback-to-Bootstrap interval is a phase pair because Bootstrap is
 * entered on a different thread in the exact pack and task spans are intentionally thread-lexical
 * in schema v1.</p>
 */
public final class ModLauncherTransitionTraceHooks {
    private static final String CALLBACK_TASK = "bootoptim_transformation_service_transformers_callback";
    private static final String TRANSITION_PHASE = "modlauncher_transformers_to_minecraft_bootstrap";
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong CALLBACK_TASK_ID = new AtomicLong();
    private static final AtomicBoolean TRANSITION_BEGUN = new AtomicBoolean();
    private static final AtomicBoolean TRANSITION_ENDED = new AtomicBoolean();

    private ModLauncherTransitionTraceHooks() {}

    /** Begin the literal callback-to-Bootstrap phase and a same-thread callback task. */
    public static long beginTransition() {
        if (!TRACE.isEnabled() || !TRANSITION_BEGUN.compareAndSet(false, true)) return 0L;
        try {
            TRACE.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, TRANSITION_PHASE);
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(CALLBACK_TASK, 0L, dependencies, null, null, -1L);
            CALLBACK_TASK_ID.set(taskId);
            return taskId;
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
            return 0L;
        }
    }

    /** Close only the SERVICE callback task on the same thread that opened it. */
    public static void endTransformersCallback(long taskId) {
        if (!TRACE.isEnabled() || taskId == 0L) return;
        try {
            TRACE.endTask(taskId, CALLBACK_TASK, -1L, "transformers_callback_return");
        } catch (Throwable ignored) {
            // Preserve startup even if trace collection fails.
        }
    }

    /**
     * Close the cross-thread transition phase at the exact Minecraft Bootstrap entry and return the
     * already-closed callback task id for Bootstrap's causal dependency edge.
     */
    static long endTransitionAtMinecraftBootstrap() {
        long taskId = CALLBACK_TASK_ID.get();
        if (!TRACE.isEnabled() || !TRANSITION_BEGUN.get()) return taskId;
        if (!TRANSITION_ENDED.compareAndSet(false, true)) return taskId;
        try {
            TRACE.record(StructuredBootTrace.EventType.PHASE_END, 0L, TRANSITION_PHASE);
        } catch (Throwable ignored) {
            // Preserve startup even if trace collection fails.
        }
        return taskId;
    }

    static long callbackTaskId() {
        return CALLBACK_TASK_ID.get();
    }
}
