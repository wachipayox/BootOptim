package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only hooks for Minecraft's patched Bootstrap.bootStrap()/validate boundary. */
public final class MinecraftBootstrapTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong BOOTSTRAP_TASK = new AtomicLong();
    private static final AtomicBoolean BOOTSTRAP_ENDED = new AtomicBoolean();

    private MinecraftBootstrapTraceHooks() {}

    public static void beginBootstrapAndValidate() {
        if (!TRACE.isEnabled() || BOOTSTRAP_TASK.get() != 0L) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "minecraft_bootstrap_and_validate", 0L, dependencies, null, null, -1L);
            if (!BOOTSTRAP_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap_and_validate", -1L, "duplicate_bootstrap_hook");
            }
        } catch (Throwable ignored) {
            // Observability must never become a startup dependency.
        }
    }

    public static void endBootstrapAndValidate() {
        if (!TRACE.isEnabled() || !BOOTSTRAP_ENDED.compareAndSet(false, true)) return;
        try {
            long taskId = BOOTSTRAP_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap_and_validate", -1L, "before_client_mod_loader_begin");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Causal predecessor for CommonModLoader's gather task; zero when this hook did not emit a task. */
    static long bootstrapTaskId() {
        return BOOTSTRAP_TASK.get();
    }
}
