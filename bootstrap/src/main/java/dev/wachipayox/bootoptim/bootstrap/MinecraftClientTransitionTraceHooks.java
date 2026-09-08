package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicLong;

/** Trace-only marker at the 1.21.1 GAME-layer callsite into ClientModLoader.begin. */
public final class MinecraftClientTransitionTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong CLIENT_ENTRY_TASK = new AtomicLong();

    private MinecraftClientTransitionTraceHooks() {}

    public static void markClientModLoaderEntry() {
        if (!TRACE.isEnabled() || CLIENT_ENTRY_TASK.get() != 0L) return;
        try {
            long predecessor = MinecraftBootstrapTraceHooks.validateTaskId();
            if (predecessor == 0L) predecessor = MinecraftBootstrapTraceHooks.bootstrapTaskId();
            if (predecessor == 0L) predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long taskId = TRACE.beginTask(
                    "minecraft_client_modloader_entry", 0L, dependencies, null, null, -1L);
            if (CLIENT_ENTRY_TASK.compareAndSet(0L, taskId)) {
                if (taskId != 0L) {
                    TRACE.endTask(taskId, "minecraft_client_modloader_entry", -1L, "client_modloader_callsite");
                }
            } else if (taskId != 0L) {
                TRACE.endTask(taskId, "minecraft_client_modloader_entry", -1L, "duplicate_client_entry_hook");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    static long clientEntryTaskId() {
        return CLIENT_ENTRY_TASK.get();
    }
}
