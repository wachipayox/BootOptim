package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

/** Runtime hooks injected at NeoForge's call into FML mod construction; trace-only and fail-open. */
public final class FmlLoadingTraceHooks {
    static final String MOD_CONSTRUCTION = "Mod Construction";
    static final String MOD_CONSTRUCTION_DEFERRED = "Mod Construction: Deferred Queue";

    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicLong GATHER_TASK = new AtomicLong();
    private static final AtomicLong CURRENT_PHASE_TASK = new AtomicLong();
    private static final AtomicInteger STAGE = new AtomicInteger();

    private FmlLoadingTraceHooks() {}

    public static void beginGatherAndInitialize() {
        if (!TRACE.isEnabled() || GATHER_TASK.get() != 0L) return;
        try {
            long predecessor = DiscoveryProfiler.dependencyTaskId();
            long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
            long gatherTaskId = TRACE.beginTask(
                    "fml_gather_and_initialize_mods", 0L, dependencies, null, null, -1L);
            if (!GATHER_TASK.compareAndSet(0L, gatherTaskId)) {
                if (gatherTaskId != 0L) {
                    TRACE.endTask(gatherTaskId, "fml_gather_and_initialize_mods", -1L, "duplicate_gather_hook");
                }
                return;
            }

            STAGE.set(0);
            CURRENT_PHASE_TASK.set(0L);
            if (TRACE.isDetailed()) {
                long prefixTaskId = TRACE.beginTask(
                        "fml_gather_pre_construction", gatherTaskId, null, null, null, -1L);
                CURRENT_PHASE_TASK.set(prefixTaskId);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never become a startup dependency.
        }
    }

    /**
     * Wraps only FML's existing periodic callback at the transformable GAME-layer callsite.
     *
     * <p>The delegate is invoked exactly once, first, on the original thread. Observation happens only after a
     * successful callback and never changes executor/future ownership. Progress snapshots are limited to detailed
     * profile/development modes so benchmark mode keeps its counter-only per-event contract.</p>
     */
    public static Runnable wrapPeriodicTask(Runnable delegate) {
        if (!TRACE.isDetailed() || delegate == null) return delegate;
        return () -> {
            delegate.run();
            observeProgress();
        };
    }

    private static void observeProgress() {
        try {
            int observed = progressStage(StartupNotificationManager.getCurrentProgress());
            int current = STAGE.get();
            if (observed <= current) return;

            if (observed >= 1 && STAGE.compareAndSet(0, 1)) {
                transitionPhase("fml_gather_pre_construction", "fml_mod_construction_parallel");
                current = 1;
            } else {
                current = STAGE.get();
            }
            if (observed >= 2 && current == 1 && STAGE.compareAndSet(1, 2)) {
                transitionPhase("fml_mod_construction_parallel", "fml_mod_construction_deferred_queue");
            }
        } catch (Throwable ignored) {
            // Progress observation is diagnostic-only and must fail open.
        }
    }

    static int progressStage(List<ProgressMeter> meters) {
        boolean construction = false;
        if (meters != null) {
            for (ProgressMeter meter : meters) {
                if (meter == null) continue;
                String name = meter.name();
                if (MOD_CONSTRUCTION_DEFERRED.equals(name)) return 2;
                if (MOD_CONSTRUCTION.equals(name)) construction = true;
            }
        }
        return construction ? 1 : 0;
    }

    private static void transitionPhase(String endingPhase, String nextPhase) {
        long gatherTaskId = GATHER_TASK.get();
        long endingTaskId = CURRENT_PHASE_TASK.getAndSet(0L);
        if (endingTaskId != 0L) {
            TRACE.endTask(endingTaskId, endingPhase, -1L, "progress_gate_transition");
        }
        if (gatherTaskId != 0L) {
            long nextTaskId = TRACE.beginTask(
                    nextPhase,
                    gatherTaskId,
                    endingTaskId == 0L ? null : new long[] { endingTaskId },
                    null,
                    null,
                    -1L);
            CURRENT_PHASE_TASK.set(nextTaskId);
        }
    }

    public static void endGatherAndInitialize() {
        if (!TRACE.isEnabled()) return;
        try {
            int stage = STAGE.getAndSet(0);
            long phaseTaskId = CURRENT_PHASE_TASK.getAndSet(0L);
            if (phaseTaskId != 0L) {
                String phase = switch (stage) {
                    case 0 -> "fml_gather_pre_construction";
                    case 1 -> "fml_mod_construction_parallel";
                    default -> "fml_mod_construction_deferred_queue";
                };
                TRACE.endTask(phaseTaskId, phase, -1L, "fml_gather_complete");
            }

            long gatherTaskId = GATHER_TASK.getAndSet(0L);
            if (gatherTaskId != 0L) {
                TRACE.endTask(gatherTaskId, "fml_gather_and_initialize_mods", -1L, "fml_gather_complete");
            }
        } catch (Throwable ignored) {
        }
    }
}
