package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace-only boundaries from BootOptim's transformation-service transformer callback to the first
 * observed Minecraft bootstrap entry.
 *
 * <p>The SERVICE callback itself is a normal same-thread task. The longer launch/game transition and
 * its two diagnostic subdivisions are phase pairs because the exact pack crosses from {@code main}
 * to a worker before Minecraft Bootstrap and schema-v1 task spans are intentionally thread-lexical.</p>
 */
public final class ModLauncherTransitionTraceHooks {
    private static final String CALLBACK_TASK = "bootoptim_transformation_service_transformers_callback";
    private static final String TRANSITION_PHASE = "modlauncher_transformers_to_minecraft_bootstrap";
    private static final String TO_BOOTSTRAP_TRANSFORM_PHASE =
            "modlauncher_transformers_to_minecraft_bootstrap_transform_accept";
    private static final String BOOTSTRAP_TRANSFORM_TO_ENTRY_PHASE =
            "minecraft_bootstrap_transform_accept_to_entry";
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong CALLBACK_TASK_ID = new AtomicLong();
    private static final AtomicBoolean TRANSITION_BEGUN = new AtomicBoolean();
    private static final AtomicBoolean BOOTSTRAP_TRANSFORM_ACCEPTED = new AtomicBoolean();
    private static final AtomicBoolean TRANSITION_ENDED = new AtomicBoolean();

    private ModLauncherTransitionTraceHooks() {}

    /** Begin the literal callback-to-Bootstrap phase and a same-thread callback task. */
    public static long beginTransition() {
        if (!TRACE.isEnabled() || !TRANSITION_BEGUN.compareAndSet(false, true)) return 0L;
        try {
            TRACE.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, TRANSITION_PHASE);
            TRACE.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, TO_BOOTSTRAP_TRANSFORM_PHASE);
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
     * Split the inclusive launcher phase at the already-proven strict Bootstrap transformer target.
     * This method runs only after the transformer has accepted exactly one {@code bootStrap()V}
     * method with a normal return, immediately before it mutates that method.
     */
    static void minecraftBootstrapTransformAccepted() {
        if (!TRACE.isEnabled() || !TRANSITION_BEGUN.get()) return;
        if (!BOOTSTRAP_TRANSFORM_ACCEPTED.compareAndSet(false, true)) return;
        try {
            TRACE.record(StructuredBootTrace.EventType.PHASE_END, 0L, TO_BOOTSTRAP_TRANSFORM_PHASE);
            TRACE.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, BOOTSTRAP_TRANSFORM_TO_ENTRY_PHASE);
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
            if (BOOTSTRAP_TRANSFORM_ACCEPTED.get()) {
                TRACE.record(StructuredBootTrace.EventType.PHASE_END, 0L, BOOTSTRAP_TRANSFORM_TO_ENTRY_PHASE);
            }
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
