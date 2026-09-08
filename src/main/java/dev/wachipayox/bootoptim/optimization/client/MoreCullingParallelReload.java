package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Experimental, opt-in scheduling helpers for MoreCulling's two resource-reload
 * listeners. The stock path is deliberately unchanged unless the corresponding
 * system property is enabled for an exact-pack experiment.
 */
public final class MoreCullingParallelReload {
    private static final String SHAPES_PROPERTY = "boot_optim.morecullingParallelShapes";
    private static final String OPACITY_PROPERTY = "boot_optim.morecullingParallelOpacity";
    private static final String THREADS_PROPERTY = "boot_optim.morecullingParallelThreads";

    private MoreCullingParallelReload() {
    }

    public static void states(Iterable<?> states, Consumer<Object> action) {
        if (!Boolean.getBoolean(SHAPES_PROPERTY)) {
            states.forEach(action);
            return;
        }
        parallelSnapshot(states, action);
    }

    public static void models(java.util.Map<?, ?> models, BiConsumer<Object, Object> action) {
        if (!Boolean.getBoolean(OPACITY_PROPERTY)) {
            models.forEach(action);
            return;
        }
        List<ModelEntry> snapshot = new ArrayList<>(models.size());
        models.forEach((key, value) -> snapshot.add(new ModelEntry(key, value)));
        parallelSnapshot(snapshot, value -> {
            ModelEntry entry = (ModelEntry) value;
            action.accept(entry.key(), entry.value());
        });
    }

    private static void parallelSnapshot(Iterable<?> values, Consumer<Object> action) {
        List<Object> snapshot = new ArrayList<>();
        values.forEach(snapshot::add);
        if (snapshot.size() < 2) {
            snapshot.forEach(action);
            return;
        }

        int workers = workerCount();
        if (workers < 2) {
            snapshot.forEach(action);
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers, new DaemonThreadFactory());
        try {
            List<Future<?>> futures = new ArrayList<>(snapshot.size());
            for (Object value : snapshot) {
                futures.add(executor.submit(() -> action.accept(value)));
            }
            for (Future<?> future : futures) {
                await(future);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static int workerCount() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        int defaultWorkers = Math.max(1, Math.min(4, available - 1));
        String configured = System.getProperty(THREADS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return defaultWorkers;
        }
        try {
            return Math.max(1, Math.min(available, Integer.parseInt(configured)));
        } catch (NumberFormatException ignored) {
            return defaultWorkers;
        }
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while joining MoreCulling cache work", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        }
    }

    private record ModelEntry(Object key, Object value) {
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId;

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "bootoptim-moreculling-" + (++nextId));
            thread.setDaemon(true);
            return thread;
        }
    }
}
