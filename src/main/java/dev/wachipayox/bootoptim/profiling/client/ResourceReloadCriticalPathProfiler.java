package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only tracing for the real resource-reload critical path.
 *
 * <p>Unlike {@code ProfiledReloadInstance}, this profiler separates the semantic preparation
 * barrier from listener ordering and from work that runs after a listener gets its apply turn.
 * It never skips, reorders, or substitutes listener work.</p>
 */
public final class ResourceReloadCriticalPathProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ReloadCritical");
    private static final AtomicInteger NEXT_RELOAD_ID = new AtomicInteger();

    private ResourceReloadCriticalPathProfiler() {
    }

    public static ReloadTrace begin() {
        if (!StartupProfiler.isEnabled()) {
            return null;
        }
        return new ReloadTrace(NEXT_RELOAD_ID.incrementAndGet(), System.nanoTime());
    }

    public static final class ReloadTrace {
        private final int id;
        private final long startNanos;
        private final AtomicInteger nextListenerIndex = new AtomicInteger();
        private final CopyOnWriteArrayList<ListenerTrace> listeners = new CopyOnWriteArrayList<>();
        private final AtomicLong allPreparationsNanos = new AtomicLong(-1L);
        private final AtomicLong allDoneNanos = new AtomicLong(-1L);
        private final AtomicBoolean emitted = new AtomicBoolean();
        private volatile int expectedListenerCount = -1;

        private ReloadTrace(int id, long startNanos) {
            this.id = id;
            this.startNanos = startNanos;
            LOGGER.info("BOOTOPTIM_RELOAD_CRITICAL event=start reload_id={}", id);
        }

        public ListenerTrace addListener(PreparableReloadListener listener) {
            String className = listener.getClass().getName();
            String name;
            try {
                name = listener.getName();
            } catch (Throwable throwable) {
                name = "<getName failed: " + throwable.getClass().getSimpleName() + ">";
            }
            if (name == null || name.isBlank()) {
                name = className;
            }
            ListenerTrace trace = new ListenerTrace(this, nextListenerIndex.getAndIncrement(), name, className);
            listeners.add(trace);
            return trace;
        }

        public void setExpectedListenerCount(int expectedListenerCount) {
            this.expectedListenerCount = expectedListenerCount;
        }

        public void observeAllPreparations(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                long now = System.nanoTime();
                if (allPreparationsNanos.compareAndSet(-1L, now)) {
                    LOGGER.info("BOOTOPTIM_RELOAD_CRITICAL event=all_preparations reload_id={} at_ms={} result={}",
                            id, format(relativeMs(now)), failure == null ? "success" : "failed");
                }
            });
        }

        public void observeAllDone(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> finish(failure));
        }

        private void finish(Throwable failure) {
            long now = System.nanoTime();
            allDoneNanos.compareAndSet(-1L, now);
            if (!emitted.compareAndSet(false, true)) {
                return;
            }

            long allPrep = allPreparationsNanos.get();
            long allDone = allDoneNanos.get();
            LOGGER.info(
                    "BOOTOPTIM_RELOAD_CRITICAL event=summary reload_id={} expected_listeners={} observed_listeners={} all_preparations_ms={} all_done_ms={} result={}",
                    id,
                    expectedListenerCount,
                    listeners.size(),
                    format(relativeMs(allPrep)),
                    format(relativeMs(allDone)),
                    failure == null ? "success" : "failed");

            List<ListenerTrace> ordered = new ArrayList<>(listeners);
            ordered.sort(Comparator.comparingInt(ListenerTrace::index));
            for (ListenerTrace listener : ordered) {
                listener.emit(allPrep);
            }

            ListenerTrace criticalPreparation = ordered.stream()
                    .filter(listener -> listener.preparationDoneNanos.get() >= 0L)
                    .max(Comparator.comparingLong(listener -> listener.preparationDoneNanos.get()))
                    .orElse(null);
            ListenerTrace criticalOrder = ordered.stream()
                    .filter(listener -> listener.orderWaitNanos(allPrep) >= 0L)
                    .max(Comparator.comparingLong(listener -> listener.orderWaitNanos(allPrep)))
                    .orElse(null);
            ListenerTrace criticalPostTurn = ordered.stream()
                    .filter(listener -> listener.postTurnNanos() >= 0L)
                    .max(Comparator.comparingLong(ListenerTrace::postTurnNanos))
                    .orElse(null);

            emitCritical("preparation_gate", criticalPreparation,
                    criticalPreparation == null ? -1L : criticalPreparation.preparationDoneNanos.get() - startNanos);
            emitCritical("order_wait", criticalOrder,
                    criticalOrder == null ? -1L : criticalOrder.orderWaitNanos(allPrep));
            emitCritical("post_turn", criticalPostTurn,
                    criticalPostTurn == null ? -1L : criticalPostTurn.postTurnNanos());
        }

        private void emitCritical(String kind, ListenerTrace listener, long nanos) {
            if (listener == null) {
                return;
            }
            LOGGER.info("BOOTOPTIM_RELOAD_CRITICAL event=critical reload_id={} kind={} index={} value_ms={} name=\"{}\" class={}",
                    id, kind, listener.index, format(nanosToMs(nanos)), listener.name, listener.className);
        }

        private double relativeMs(long absoluteNanos) {
            return absoluteNanos < 0L ? -1.0 : nanosToMs(absoluteNanos - startNanos);
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String name;
        private final String className;
        private final AtomicInteger barrierCalls = new AtomicInteger();
        private final AtomicLong preparationDoneNanos = new AtomicLong(-1L);
        private final AtomicLong turnReadyNanos = new AtomicLong(-1L);
        private final AtomicLong listenerDoneNanos = new AtomicLong(-1L);
        private final ExecutorStats prepareStats = new ExecutorStats();
        private final ExecutorStats applyStats = new ExecutorStats();
        private final AtomicBoolean listenerFailed = new AtomicBoolean();

        private ListenerTrace(ReloadTrace reload, int index, String name, String className) {
            this.reload = reload;
            this.index = index;
            this.name = name;
            this.className = className;
        }

        private int index() {
            return index;
        }

        public PreparableReloadListener.PreparationBarrier wrapBarrier(PreparableReloadListener.PreparationBarrier original) {
            return new PreparableReloadListener.PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    barrierCalls.incrementAndGet();
                    preparationDoneNanos.compareAndSet(-1L, System.nanoTime());
                    CompletableFuture<T> future = original.wait(value);
                    future.whenComplete((ignored, failure) -> {
                        turnReadyNanos.compareAndSet(-1L, System.nanoTime());
                        if (failure != null) {
                            listenerFailed.set(true);
                        }
                    });
                    return future;
                }
            };
        }

        public Executor wrapPrepareExecutor(Executor original) {
            return prepareStats.wrap(original);
        }

        public Executor wrapApplyExecutor(Executor original) {
            return applyStats.wrap(original);
        }

        public void observeCompletion(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                listenerDoneNanos.compareAndSet(-1L, System.nanoTime());
                if (failure != null) {
                    listenerFailed.set(true);
                }
            });
        }

        private long globalWaitNanos(long allPreparations) {
            long prepared = preparationDoneNanos.get();
            if (prepared < 0L || allPreparations < 0L) {
                return -1L;
            }
            return Math.max(0L, allPreparations - prepared);
        }

        private long orderWaitNanos(long allPreparations) {
            long prepared = preparationDoneNanos.get();
            long turnReady = turnReadyNanos.get();
            if (prepared < 0L || turnReady < 0L || allPreparations < 0L) {
                return -1L;
            }
            long earliest = Math.max(prepared, allPreparations);
            return Math.max(0L, turnReady - earliest);
        }

        private long postTurnNanos() {
            long turnReady = turnReadyNanos.get();
            long done = listenerDoneNanos.get();
            if (turnReady < 0L || done < 0L) {
                return -1L;
            }
            return Math.max(0L, done - turnReady);
        }

        private void emit(long allPreparations) {
            long prepared = preparationDoneNanos.get();
            long turnReady = turnReadyNanos.get();
            long done = listenerDoneNanos.get();
            LOGGER.info(
                    "BOOTOPTIM_RELOAD_CRITICAL event=listener reload_id={} index={} name=\"{}\" class={} barrier_calls={} prepare_done_ms={} global_wait_ms={} order_wait_ms={} turn_ready_ms={} done_ms={} post_turn_ms={} prepare_tasks={} prepare_cpu_ms={} prepare_queue_sum_ms={} prepare_queue_max_ms={} apply_tasks={} apply_cpu_ms={} apply_queue_sum_ms={} apply_queue_max_ms={} apply_first_start_ms={} apply_last_end_ms={} result={}",
                    reload.id,
                    index,
                    name,
                    className,
                    barrierCalls.get(),
                    format(reload.relativeMs(prepared)),
                    format(nanosToMs(globalWaitNanos(allPreparations))),
                    format(nanosToMs(orderWaitNanos(allPreparations))),
                    format(reload.relativeMs(turnReady)),
                    format(reload.relativeMs(done)),
                    format(nanosToMs(postTurnNanos())),
                    prepareStats.completed.get(),
                    format(nanosToMs(prepareStats.runNanos.sum())),
                    format(nanosToMs(prepareStats.queueNanos.sum())),
                    format(nanosToMs(prepareStats.maxQueueNanos.get())),
                    applyStats.completed.get(),
                    format(nanosToMs(applyStats.runNanos.sum())),
                    format(nanosToMs(applyStats.queueNanos.sum())),
                    format(nanosToMs(applyStats.maxQueueNanos.get())),
                    format(reload.relativeMs(applyStats.firstStartNanos.get())),
                    format(reload.relativeMs(applyStats.lastEndNanos.get())),
                    listenerFailed.get() ? "failed" : "success");
        }
    }

    private static final class ExecutorStats {
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final LongAdder runNanos = new LongAdder();
        private final LongAdder queueNanos = new LongAdder();
        private final AtomicLong maxQueueNanos = new AtomicLong();
        private final AtomicLong firstStartNanos = new AtomicLong(-1L);
        private final AtomicLong lastEndNanos = new AtomicLong(-1L);

        private Executor wrap(Executor original) {
            return command -> {
                long enqueued = System.nanoTime();
                submitted.incrementAndGet();
                original.execute(() -> {
                    long started = System.nanoTime();
                    firstStartNanos.compareAndSet(-1L, started);
                    long queued = Math.max(0L, started - enqueued);
                    queueNanos.add(queued);
                    maxQueueNanos.accumulateAndGet(queued, Math::max);
                    try {
                        command.run();
                    } finally {
                        long ended = System.nanoTime();
                        runNanos.add(Math.max(0L, ended - started));
                        lastEndNanos.set(ended);
                        completed.incrementAndGet();
                    }
                });
            };
        }
    }

    private static double nanosToMs(long nanos) {
        return nanos < 0L ? -1.0 : nanos / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
