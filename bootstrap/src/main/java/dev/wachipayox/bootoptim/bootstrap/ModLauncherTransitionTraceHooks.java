package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace-only boundary from BootOptim's transformation-service transformer callback to the first
 * observed Minecraft bootstrap entry.
 *
 * <p>The begin edge is emitted from {@code EarlyStartupProbeService.transformers()}, which
 * ModLauncher invokes only after scan completion and GAME resources have been registered. The end
 * edge is emitted immediately before {@code Bootstrap.bootStrap()} tracing begins. The interval is
 * deliberately named for those observable edges; it must not be interpreted as CPU time or as a
 * claim that every enclosed operation belongs to one ModLauncher subsystem.</p>
 */
public final class ModLauncherTransitionTraceHooks {
    private static final String TASK = "modlauncher_transformers_to_minecraft_bootstrap";
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong TRANSITION_TASK = new AtomicLong();
    private static final AtomicBoolean TRANSITION_ENDED = new AtomicBoolean();

    private ModLauncherTransitionTraceHooks() {}

    public static void beginTransition() {
        if (!TRACE.isEnabled() || TRANSITION_TASK.get() != 0L) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(TASK, 0L, dependencies, null, null, -1L);
            if (!TRANSITION_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, TASK, -1L, "duplicate_transformers_callback");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    /**
     * Close the transition at the exact Minecraft bootstrap entry and return its task id for the
     * bootstrap task's dependency edge. Zero means the transition hook never emitted a task.
     */
    static long endTransitionAtMinecraftBootstrap() {
        long taskId = TRANSITION_TASK.get();
        if (!TRACE.isEnabled() || taskId == 0L) return taskId;
        if (!TRANSITION_ENDED.compareAndSet(false, true)) return taskId;
        try {
            TRACE.endTask(taskId, TASK, -1L, "minecraft_bootstrap_entry");
        } catch (Throwable ignored) {
            // Preserve startup even if trace collection fails.
        }
        return taskId;
    }

    static long transitionTaskId() {
        return TRANSITION_TASK.get();
    }
}
