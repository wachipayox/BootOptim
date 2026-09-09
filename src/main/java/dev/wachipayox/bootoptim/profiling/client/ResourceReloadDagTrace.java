package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Coarse startup resource/model DAG producer for the bootstrap-owned structured trace.
 *
 * <p>This class never imports trace-core. It emits through {@link RegularBootTraceBridge} only,
 * observes stock futures/barriers without replacing them, and traces only the first reload generation.
 * Tasks are strictly lexical; cross-thread work is represented by phase/barrier events.</p>
 */
public final class ResourceReloadDagTrace {
    private static final String MODE_PROPERTY = "boot_optim.bootTrace.mode";
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final AtomicLong ACTIVE_MODEL_GENERATION = new AtomicLong();
    private static final AtomicLong LAST_LOAD_MODELS_TASK = new AtomicLong();
    private static final ThreadLocal<Long> LISTENER_GENERATION = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Deque<Long>> TASK_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private ResourceReloadDagTrace() {
    }

    public static boolean enabled() {
        String mode = System.getProperty(MODE_PROPERTY, "off");
        return mode != null && !mode.isBlank() && !"off".equalsIgnoreCase(mode.trim());
    }

    public static long beginReloadGeneration() {
        long generation = NEXT_GENERATION.incrementAndGet();
        if (!enabled() || generation != 1L) {
            return generation;
        }
        RegularBootTraceBridge.record("phase_begin", 0L, 0L, null,
                "resource_reload", -1L, "boot_optim", null, generation, "initial_reload");
        RegularBootTraceBridge.record("barrier_wait", 0L, 0L, null,
                "reload_global_preparation", -1L, "boot_optim", null, generation,
                "waiting_for_all_listener_preparations");
        return generation;
    }

    public static void observeReloadFutures(long generation, CompletableFuture<?> allPreparations,
            CompletableFuture<?> allDone) {
        if (!isStartupGeneration(generation)) {
            return;
        }
        if (allPreparations != null) {
            allPreparations.whenComplete((ignored, failure) -> RegularBootTraceBridge.record(
                    "barrier_open", 0L, 0L, null, "reload_global_preparation", -1L,
                    "boot_optim", null, generation, resultDetail(failure, "all_preparations_open")));
        }
        if (allDone != null) {
            allDone.whenComplete((ignored, failure) -> RegularBootTraceBridge.record(
                    "phase_end", 0L, 0L, null, "resource_reload", -1L,
                    "boot_optim", null, generation, resultDetail(failure, "all_done")));
        }
    }

    public static <T> PreparableReloadListener.PreparationBarrier wrapModelManagerBarrier(
            long generation, PreparableReloadListener.PreparationBarrier original) {
        if (!isStartupGeneration(generation) || original == null) {
            return original;
        }
        return new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <V> CompletableFuture<V> wait(V value) {
                RegularBootTraceBridge.record("phase_end", 0L, 0L, null,
                        "model_manager_preparation", -1L, "boot_optim", null, generation,
                        "preparation_reached_stock_barrier");
                RegularBootTraceBridge.record("barrier_wait", 0L, 0L, null,
                        "model_manager_apply_turn", -1L, "boot_optim", null, generation,
                        "stock_preparation_barrier");
                CompletableFuture<V> future = original.wait(value);
                future.whenComplete((ignored, failure) -> RegularBootTraceBridge.record(
                        "barrier_open", 0L, 0L, null, "model_manager_apply_turn", -1L,
                        "boot_optim", null, generation, resultDetail(failure, "ordered_apply_turn_ready")));
                return future;
            }
        };
    }

    public static void enterListener(long generation) {
        if (isStartupGeneration(generation)) {
            LISTENER_GENERATION.set(generation);
        }
    }

    public static void exitListener() {
        LISTENER_GENERATION.remove();
    }

    public static long listenerGeneration() {
        return LISTENER_GENERATION.get();
    }

    public static void beginModelManager(long generation) {
        if (!isStartupGeneration(generation)) {
            return;
        }
        ACTIVE_MODEL_GENERATION.set(generation);
        RegularBootTraceBridge.record("phase_begin", 0L, 0L, null,
                "model_manager_reload", -1L, "boot_optim", null, generation, null);
        RegularBootTraceBridge.record("phase_begin", 0L, 0L, null,
                "model_manager_preparation", -1L, "boot_optim", null, generation, null);
    }

    public static void observeModelManagerCompletion(long generation, CompletableFuture<?> future) {
        if (!isStartupGeneration(generation) || future == null) {
            return;
        }
        future.whenComplete((ignored, failure) -> {
            RegularBootTraceBridge.record("phase_end", 0L, 0L, null,
                    "model_manager_reload", -1L, "boot_optim", null, generation,
                    resultDetail(failure, "listener_future_complete"));
            ACTIVE_MODEL_GENERATION.compareAndSet(generation, 0L);
        });
    }

    public static long activeModelGeneration() {
        long generation = ACTIVE_MODEL_GENERATION.get();
        return isStartupGeneration(generation) ? generation : 0L;
    }

    public static long beginLexicalTask(String phase, long generation, long[] dependencies) {
        if (!isStartupGeneration(generation)) {
            return 0L;
        }
        Deque<Long> stack = TASK_STACK.get();
        long parent = stack.isEmpty() ? 0L : stack.peekLast();
        long taskId = RegularBootTraceBridge.beginTask(phase, parent, dependencies,
                "boot_optim", null, generation);
        if (taskId != 0L) {
            stack.addLast(taskId);
        }
        return taskId;
    }

    public static void endLexicalTask(long taskId, String phase, String detail) {
        if (taskId == 0L) {
            return;
        }
        try {
            Deque<Long> stack = TASK_STACK.get();
            if (!stack.isEmpty() && stack.peekLast() == taskId) {
                stack.removeLast();
            }
            RegularBootTraceBridge.endTask(taskId, phase, -1L, detail);
        } finally {
            if (TASK_STACK.get().isEmpty()) {
                TASK_STACK.remove();
            }
        }
    }

    public static void beginAsyncPhase(String phase, long generation, long taskId) {
        if (isStartupGeneration(generation)) {
            RegularBootTraceBridge.record("phase_begin", taskId, 0L, null,
                    phase, -1L, "boot_optim", null, generation, null);
        }
    }

    public static void observeAsyncPhase(String phase, long generation, long taskId,
            CompletableFuture<?> future) {
        if (!isStartupGeneration(generation) || future == null) {
            return;
        }
        future.whenComplete((ignored, failure) -> RegularBootTraceBridge.record(
                "phase_end", taskId, 0L, null, phase, -1L, "boot_optim", null,
                generation, resultDetail(failure, "future_complete")));
    }

    public static void observeAsyncPhaseMap(String phase, long generation, long taskId,
            Map<?, ? extends CompletableFuture<?>> futures) {
        if (!isStartupGeneration(generation) || futures == null) {
            return;
        }
        int count = futures.size();
        if (count == 0) {
            RegularBootTraceBridge.record("phase_end", taskId, 0L, null,
                    phase, -1L, "boot_optim", null, generation, "empty_future_map");
            return;
        }
        AtomicInteger remaining = new AtomicInteger(count);
        AtomicBoolean failed = new AtomicBoolean();
        for (CompletableFuture<?> future : futures.values()) {
            if (future == null) {
                if (remaining.decrementAndGet() == 0) {
                    endAggregatePhase(phase, generation, taskId, failed.get(), count);
                }
                continue;
            }
            future.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    failed.set(true);
                }
                if (remaining.decrementAndGet() == 0) {
                    endAggregatePhase(phase, generation, taskId, failed.get(), count);
                }
            });
        }
    }

    public static void rememberLoadModelsTask(long taskId) {
        if (taskId != 0L) {
            LAST_LOAD_MODELS_TASK.set(taskId);
        }
    }

    public static long[] loadModelsDependency() {
        long taskId = LAST_LOAD_MODELS_TASK.get();
        return taskId == 0L ? null : new long[] {taskId};
    }

    public static void commitBegin(long taskId, long generation) {
        if (taskId != 0L && isStartupGeneration(generation)) {
            RegularBootTraceBridge.record("commit_begin", taskId, 0L, null,
                    "model_manager_commit", -1L, "boot_optim", null, generation,
                    "stock_ModelManager.apply");
        }
    }

    public static void commitEnd(long taskId, long generation) {
        if (taskId != 0L && isStartupGeneration(generation)) {
            RegularBootTraceBridge.record("commit_end", taskId, 0L, null,
                    "model_manager_commit", -1L, "boot_optim", null, generation,
                    "stock_ModelManager.apply_return");
        }
    }

    public static void markMainMenuEndpoint() {
        if (enabled()) {
            RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                    "main_menu", -1L, "boot_optim", null, 1L, "endpoint");
        }
    }

    private static boolean isStartupGeneration(long generation) {
        return enabled() && generation == 1L;
    }

    private static void endAggregatePhase(String phase, long generation, long taskId,
            boolean failed, int count) {
        RegularBootTraceBridge.record("phase_end", taskId, 0L, null,
                phase, -1L, "boot_optim", null, generation,
                (failed ? "failed" : "success") + ";future_count=" + count);
    }

    private static String resultDetail(Throwable failure, String detail) {
        return (failure == null ? "success" : "failed") + ';' + detail;
    }
}
