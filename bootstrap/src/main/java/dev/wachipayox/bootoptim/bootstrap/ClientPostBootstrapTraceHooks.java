package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only hooks for the transformable ClientModLoader pre-gather path. */
public final class ClientPostBootstrapTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong CLIENT_PRE_GATHER_TASK = new AtomicLong();
    private static final AtomicBoolean CLIENT_PRE_GATHER_ENDED = new AtomicBoolean();
    private static final AtomicLong BUILTIN_LANGUAGES_TASK = new AtomicLong();
    private static final AtomicBoolean BUILTIN_LANGUAGES_ENDED = new AtomicBoolean();

    private ClientPostBootstrapTraceHooks() {}

    public static void beginClientPreGather() {
        if (!TRACE.isEnabled() || CLIENT_PRE_GATHER_TASK.get() != 0L) return;
        try {
            long predecessor = MinecraftBootstrapTraceHooks.validateTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.bootstrapTaskId();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask("client_mod_loader_pre_gather", 0L, dependencies, null, null, -1L);
            if (!CLIENT_PRE_GATHER_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "client_mod_loader_pre_gather", -1L, "duplicate_client_begin_hook");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never prevent client mod loading.
        }
    }

    public static void beginBuiltinLanguages() {
        if (!TRACE.isEnabled() || BUILTIN_LANGUAGES_TASK.get() != 0L) return;
        try {
            long parent = CLIENT_PRE_GATHER_TASK.get();
            long taskId = TRACE.beginTask("client_builtin_languages", parent, null, null, null, -1L);
            if (!BUILTIN_LANGUAGES_TASK.compareAndSet(0L, taskId) && taskId != 0L) {
                TRACE.endTask(taskId, "client_builtin_languages", -1L, "duplicate_builtin_languages_hook");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void endBuiltinLanguages() {
        if (!TRACE.isEnabled() || !BUILTIN_LANGUAGES_ENDED.compareAndSet(false, true)) return;
        try {
            long taskId = BUILTIN_LANGUAGES_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "client_builtin_languages", -1L, "builtin_languages_return");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void endClientPreGather() {
        if (!TRACE.isEnabled() || !CLIENT_PRE_GATHER_ENDED.compareAndSet(false, true)) return;
        try {
            long taskId = CLIENT_PRE_GATHER_TASK.get();
            if (taskId != 0L) {
                TRACE.endTask(taskId, "client_mod_loader_pre_gather", -1L, "common_mod_loader_begin_call");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Causal predecessor for the existing CommonModLoader gather task. */
    static long clientPreGatherTaskId() {
        return CLIENT_PRE_GATHER_TASK.get();
    }
}
