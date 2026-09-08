package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only hooks for Minecraft's exact bootstrap and validation method bodies. */
public final class MinecraftBootstrapTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong BOOTSTRAP_TASK = new AtomicLong();
    private static final AtomicBoolean BOOTSTRAP_ENDED = new AtomicBoolean();
    private static final AtomicLong VALIDATE_TASK = new AtomicLong();
    private static final AtomicBoolean VALIDATE_ENDED = new AtomicBoolean();

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

    public static void beginValidate() {
        if (!TRACE.isEnabled() || VALIDATE_TASK.get() != 0L) return;
        try {
            long predecessor = BOOTSTRAP_TASK.get();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask("minecraft_bootstrap_validate", 0L, dependencies, null, null, -1L);
            if (!VALIDATE_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap_validate", -1L, "duplicate_validate_hook");
            }
        } catch (Throwable ignored) {
            // Trace failure is never fatal to Minecraft validation.
        }
    }

    public static void endValidate() {
        if (!TRACE.isEnabled() || !VALIDATE_ENDED.compareAndSet(false, true)) return;
        try {
            long taskId = VALIDATE_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_bootstrap_validate", -1L, "minecraft_validate_return");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Causal predecessor for post-bootstrap client loading; zero when this hook did not emit a task. */
    static long validateTaskId() {
        return VALIDATE_TASK.get();
    }

    /** Causal predecessor fallback for later game-layer tasks; zero when bootstrap was not traced. */
    static long bootstrapTaskId() {
        return BOOTSTRAP_TASK.get();
    }
}
