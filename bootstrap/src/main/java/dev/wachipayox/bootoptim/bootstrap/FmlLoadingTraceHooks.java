package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime hooks injected at NeoForge's game-layer transition into FML mod construction; trace-only and fail-open. */
public final class FmlLoadingTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong COMMON_PREFIX_TASK = new AtomicLong();
    private static final AtomicLong GATHER_TASK = new AtomicLong();

    private FmlLoadingTraceHooks() {}

    public static void beginCommonModLoaderPrefix() {
        if (!TRACE.isEnabled() || COMMON_PREFIX_TASK.get() != 0L) return;
        try {
            long predecessor = NeoForgeLanguageTraceHooks.builtinLanguageTaskId();
            if (predecessor == 0L) predecessor = MinecraftClientTransitionTraceHooks.clientEntryTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.validateTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.bootstrapTaskId();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "neoforge_common_modloader_pregather", 0L, dependencies, null, null, -1L);
            if (!COMMON_PREFIX_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "neoforge_common_modloader_pregather", -1L, "duplicate_common_prefix_hook");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void endCommonModLoaderPrefix() {
        if (!TRACE.isEnabled()) return;
        try {
            long taskId = COMMON_PREFIX_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "neoforge_common_modloader_pregather", -1L, "common_modloader_gather_call");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void beginGatherAndInitialize() {
        if (!TRACE.isEnabled() || GATHER_TASK.get() != 0L) return;
        try {
            long predecessor = COMMON_PREFIX_TASK.get();
            if (predecessor == 0L) predecessor = NeoForgeLanguageTraceHooks.builtinLanguageTaskId();
            if (predecessor == 0L) predecessor = MinecraftClientTransitionTraceHooks.clientEntryTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.validateTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.bootstrapTaskId();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "fml_gather_and_initialize_mods", 0L, dependencies, null, null, -1L);
            if (!GATHER_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "fml_gather_and_initialize_mods", -1L, "duplicate_gather_hook");
            }
        } catch (Throwable ignored) {
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
