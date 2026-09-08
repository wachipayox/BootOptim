package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLLoader;

/** Runtime hooks injected into FML by {@link FmlLoadingTraceTransformer}; profile-only and fail-open. */
public final class FmlLoadingTraceHooks {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong GATHER_TASK = new AtomicLong();
    private static final ConcurrentHashMap<String, Long> CONSTRUCTION_TASKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> ACTIVE_CONSTRUCTION = new ThreadLocal<>();

    private FmlLoadingTraceHooks() {}

    public static void beginGatherAndInitialize() {
        if (!TRACE.isEnabled()) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            GATHER_TASK.compareAndSet(0L,
                    TRACE.beginTask("fml_gather_and_initialize_mods", 0L, dependencies, null, null, -1L));
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    public static void endGatherAndInitialize() {
        if (!TRACE.isEnabled()) return;
        try {
            long taskId = GATHER_TASK.get();
            if (taskId != 0L) TRACE.endTask(taskId, "fml_gather_and_initialize_mods", -1L, "fml_gather_complete");
        } catch (Throwable ignored) {
        }
    }

    public static void beforeBackgroundScanWait() {
        if (!TRACE.isEnabled()) return;
        try {
            TRACE.record(StructuredBootTrace.EventType.BARRIER_WAIT, GATHER_TASK.get(), "fml_background_scan_complete");
        } catch (Throwable ignored) {
        }
    }

    public static void afterBackgroundScanWait() {
        if (!TRACE.isEnabled()) return;
        try {
            TRACE.record(StructuredBootTrace.EventType.BARRIER_OPEN, GATHER_TASK.get(), "fml_background_scan_complete");
        } catch (Throwable ignored) {
        }
    }

    public static void beginModConstruction(ModContainer container) {
        if (!TRACE.isEnabled() || container == null) return;
        try {
            String modId = container.getModId();
            long[] dependencies = constructionDependencies(container);
            long taskId = TRACE.beginTask(
                    "fml_mod_construction", GATHER_TASK.get(), dependencies, modId, null, -1L);
            CONSTRUCTION_TASKS.put(modId, taskId);
            ACTIVE_CONSTRUCTION.set(taskId);
        } catch (Throwable ignored) {
            ACTIVE_CONSTRUCTION.remove();
        }
    }

    public static void endModConstruction() {
        if (!TRACE.isEnabled()) return;
        Long taskId = ACTIVE_CONSTRUCTION.get();
        ACTIVE_CONSTRUCTION.remove();
        if (taskId == null || taskId == 0L) return;
        try {
            TRACE.endTask(taskId, "fml_mod_construction", -1L, "construct_mod_return");
        } catch (Throwable ignored) {
        }
    }

    private static long[] constructionDependencies(ModContainer container) {
        // Benchmark mode keeps the trace-core no-clock/no-buffer contract and avoids dependency-array work.
        if (!TRACE.isDetailed()) return null;
        try {
            var dependencies = FMLLoader.getCurrent().getLoadingModList().getDependencies(container.getModInfo());
            if (dependencies.isEmpty()) return predecessorOnly();
            var ids = new ArrayList<Long>(dependencies.size() + 1);
            for (var dependency : dependencies) {
                Long id = CONSTRUCTION_TASKS.get(dependency.getModId());
                if (id != null && id != 0L) ids.add(id);
            }
            if (ids.isEmpty()) return predecessorOnly();
            long[] result = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
            return result;
        } catch (Throwable ignored) {
            return predecessorOnly();
        }
    }

    private static long[] predecessorOnly() {
        long predecessor = DiscoveryProfiler.dependencyTaskId();
        return predecessor == 0L ? null : new long[] { predecessor };
    }
}
