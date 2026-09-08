package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only hooks for the NeoForge GAME-layer built-in language load. */
public final class NeoForgeLanguageTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong BUILTIN_LANGUAGE_TASK = new AtomicLong();

    private NeoForgeLanguageTraceHooks() {}

    public static void beginBuiltinLanguages() {
        if (!TRACE.isEnabled() || BUILTIN_LANGUAGE_TASK.get() != 0L) return;
        try {
            long predecessor = MinecraftBootstrapTraceHooks.validateTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.bootstrapTaskId();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "neoforge_builtin_languages", 0L, dependencies, null, null, -1L);
            if (!BUILTIN_LANGUAGE_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "neoforge_builtin_languages", -1L, "duplicate_builtin_language_hook");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    public static void endBuiltinLanguages() {
        if (!TRACE.isEnabled()) return;
        try {
            long taskId = BUILTIN_LANGUAGE_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "neoforge_builtin_languages", -1L, "builtin_languages_complete");
            }
        } catch (Throwable ignored) {
        }
    }

    static long builtinLanguageTaskId() {
        return BUILTIN_LANGUAGE_TASK.get();
    }
}
