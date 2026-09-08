package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only hooks for Minecraft's exact Bootstrap.bootStrap() method body. */
public final class MinecraftBootstrapTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong BOOTSTRAP_TASK = new AtomicLong();
    private static final AtomicBoolean BOOTSTRAP_ENDED = new AtomicBoolean();

    private MinecraftBootstrapTraceHooks() {}

    public static void beginBootstrap() {
        if (!TRACE.isEnabled() || BOOTSTRAP_TASK.get() != 0L) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask("minecraft_bootstrap", 0L, dependencies, null, null, -1L);
            if (!BOOTSTRAP_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap", -1L, "duplicate_bootstrap_hook");
            }
        } catch (Throwable ignored) {
            // Observability must never become a startup dependency.
        }
    }

    public static void endBootstrap() {
        if (!TRACE.isEnabled() || !BOOTSTRAP_ENDED.compareAndSet(false, true)) return;
        try {
            long taskId = BOOTSTRAP_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap", -1L, "minecraft_bootstrap_return");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Causal predecessor for CommonModLoader's gather task; zero when this hook did not emit a task. */
    static long bootstrapTaskId() {
        return BOOTSTRAP_TASK.get();
    }
}
