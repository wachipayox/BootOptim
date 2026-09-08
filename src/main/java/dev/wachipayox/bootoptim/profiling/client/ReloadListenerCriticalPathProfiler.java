package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in, low-cardinality attribution of the real SimpleReloadInstance critical path.
 *
 * <p>This intentionally does not time executor tasks or sum inclusive listener durations. It records
 * only semantic preparation-barrier completion, ordered apply-turn readiness, and listener future
 * completion. Stock listener futures, executors, callbacks and ordering remain untouched.</p>
 */
public final class ReloadListenerCriticalPathProfiler {
    public static final String PROPERTY = "boot_optim.profileReloadListenerCriticalPath";

    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ReloadListenerPath");
    private static final AtomicInteger NEXT_RELOAD_ID = new AtomicInteger();
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private ReloadListenerCriticalPathProfiler() {
    }

    public static ReloadTrace begin() {
        if (!ENABLED) {
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
        private final AtomicReference<Throwable> allDoneFailure = new AtomicReference<>();
        private final AtomicBoolean allDoneObserved = new AtomicBoolean();
        private final AtomicBoolean emitted = new AtomicBoolean();
        private volatile int expectedListenerCount = -1;

        private ReloadTrace(int id, long startNanos) {
            this.id = id;
            this.startNanos = startNanos;
            LOGGER.info("BOOTOPTIM_RELOAD_LISTENER_PATH event=start reload_id={}", id);
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
            ListenerTrace trace = new ListenerTrace(
                    this,
                    nextListenerIndex.getAndIncrement(),
                    name,
                    className,
                    classifyScope(className));
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
                    LOGGER.info(
                            "BOOTOPTIM_RELOAD_LISTENER_PATH event=all_preparations reload_id={} at_ms={} result={}",
                            id,
                            format(relativeMs(now)),
                            failure == null ? "success" : "failed");
                }
            });
        }

        public void observeAllDone(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                allDoneNanos.compareAndSet(-1L, System.nanoTime());
                if (failure != null) {
                    allDoneFailure.compareAndSet(null, failure);
                }
                allDoneObserved.set(true);
                tryFinish();
            });
        }

        private void tryFinish() {
            if (!allDoneObserved.get() || emitted.get()) {
                return;
            }
            for (ListenerTrace listener : listeners) {
                if (listener.listenerDoneNanos.get() < 0L) {
                    return;
                }
            }
            if (!emitted.compareAndSet(false, true)) {
                return;
            }
            emit(allDoneFailure.get());
        }

        private void emit(Throwable failure) {
            long allPrep = allPreparationsNanos.get();
            long allDone = allDoneNanos.get();
            List<ListenerTrace> ordered = new ArrayList<>(listeners);
            ordered.sort(Comparator.comparingInt(ListenerTrace::index));

            long serialPostTurnSum = 0L;
            long externalSerialPostTurnSum = 0L;
            long modelManagerSerialPostTurnSum = 0L;
            boolean orderedNonOverlap = true;
            ListenerTrace previous = null;
            for (ListenerTrace listener : ordered) {
                long postTurn = listener.postTurnNanos();
                if (postTurn >= 0L) {
                    serialPostTurnSum += postTurn;
                    if (listener.scope == Scope.MODEL_MANAGER) {
                        modelManagerSerialPostTurnSum += postTurn;
                    } else {
                        externalSerialPostTurnSum += postTurn;
                    }
                }
                if (previous != null) {
                    long previousDone = previous.listenerDoneNanos.get();
                    long turn = listener.turnReadyNanos.get();
                    if (previousDone < 0L || turn < 0L || turn < previousDone) {
                        orderedNonOverlap = false;
                    }
                }
                previous = listener;
            }

            long tailNanos = allPrep >= 0L && allDone >= allPrep ? allDone - allPrep : -1L;
            long unaccountedTail = tailNanos >= 0L
                    ? Math.max(0L, tailNanos - serialPostTurnSum)
                    : -1L;

            LOGGER.info(
                    "BOOTOPTIM_RELOAD_LISTENER_PATH event=summary reload_id={} expected_listeners={} observed_listeners={} all_preparations_ms={} all_done_ms={} serial_tail_ms={} post_turn_sum_ms={} external_post_turn_sum_ms={} model_manager_post_turn_sum_ms={} unaccounted_tail_ms={} ordered_nonoverlap={} result={}",
                    id,
                    expectedListenerCount,
                    ordered.size(),
                    format(relativeMs(allPrep)),
                    format(relativeMs(allDone)),
                    format(nanosToMs(tailNanos)),
                    format(nanosToMs(serialPostTurnSum)),
                    format(nanosToMs(externalSerialPostTurnSum)),
                    format(nanosToMs(modelManagerSerialPostTurnSum)),
                    format(nanosToMs(unaccountedTail)),
                    orderedNonOverlap,
                    failure == null ? "success" : "failed");

            for (ListenerTrace listener : ordered) {
                listener.emit(allPrep);
            }

            ListenerTrace preparationGate = ordered.stream()
                    .filter(listener -> listener.preparationDoneNanos.get() >= 0L)
                    .max(Comparator.comparingLong(listener -> listener.preparationDoneNanos.get()))
                    .orElse(null);
            ListenerTrace largestExternalSerial = ordered.stream()
                    .filter(listener -> listener.scope == Scope.EXTERNAL_LISTENER)
                    .filter(listener -> listener.postTurnNanos() >= 0L)
                    .max(Comparator.comparingLong(ListenerTrace::postTurnNanos))
                    .orElse(null);

            emitCritical("preparation_gate", preparationGate,
                    preparationGate == null ? -1L : preparationGate.preparationDoneNanos.get() - startNanos);
            emitCritical("largest_external_serial_apply", largestExternalSerial,
                    largestExternalSerial == null ? -1L : largestExternalSerial.postTurnNanos());
        }

        private void emitCritical(String kind, ListenerTrace listener, long nanos) {
            if (listener == null) {
                return;
            }
            LOGGER.info(
                    "BOOTOPTIM_RELOAD_LISTENER_PATH event=critical reload_id={} kind={} index={} scope={} value_ms={} name=\"{}\" class={}",
                    id,
                    kind,
                    listener.index,
                    listener.scope.label,
                    format(nanosToMs(nanos)),
                    listener.name,
                    listener.className);
        }

        private double relativeMs(long absoluteNanos) {
            return absoluteNanos < 0L ? -1.0D : nanosToMs(absoluteNanos - startNanos);
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String name;
        private final String className;
        private final Scope scope;
        private final AtomicInteger barrierCalls = new AtomicInteger();
        private final AtomicLong preparationDoneNanos = new AtomicLong(-1L);
        private final AtomicLong turnReadyNanos = new AtomicLong(-1L);
        private final AtomicLong listenerDoneNanos = new AtomicLong(-1L);
        private final AtomicBoolean listenerFailed = new AtomicBoolean();

        private ListenerTrace(ReloadTrace reload, int index, String name, String className, Scope scope) {
            this.reload = reload;
            this.index = index;
            this.name = name;
            this.className = className;
            this.scope = scope;
        }

        private int index() {
            return index;
        }

        public PreparableReloadListener.PreparationBarrier wrapBarrier(
                PreparableReloadListener.PreparationBarrier original) {
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

        public void observeCompletion(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                listenerDoneNanos.compareAndSet(-1L, System.nanoTime());
                if (failure != null) {
                    listenerFailed.set(true);
                }
                reload.tryFinish();
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
            boolean serialCritical = allPreparations >= 0L && turnReady >= allPreparations && done >= turnReady;
            LOGGER.info(
                    "BOOTOPTIM_RELOAD_LISTENER_PATH event=listener reload_id={} index={} scope={} name=\"{}\" class={} barrier_calls={} prepare_done_ms={} global_wait_ms={} order_wait_ms={} turn_ready_ms={} done_ms={} serial_post_turn_ms={} serial_critical={} result={}",
                    reload.id,
                    index,
                    scope.label,
                    name,
                    className,
                    barrierCalls.get(),
                    format(reload.relativeMs(prepared)),
                    format(nanosToMs(globalWaitNanos(allPreparations))),
                    format(nanosToMs(orderWaitNanos(allPreparations))),
                    format(reload.relativeMs(turnReady)),
                    format(reload.relativeMs(done)),
                    format(nanosToMs(postTurnNanos())),
                    serialCritical,
                    listenerFailed.get() ? "failed" : "success");
        }
    }

    private enum Scope {
        MODEL_MANAGER("model_manager"),
        EXTERNAL_LISTENER("external_listener");

        private final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    private static Scope classifyScope(String className) {
        if ("net.minecraft.client.resources.model.ModelManager".equals(className)
                || className.endsWith(".ModelManager")) {
            return Scope.MODEL_MANAGER;
        }
        return Scope.EXTERNAL_LISTENER;
    }

    private static double nanosToMs(long nanos) {
        return nanos < 0L ? -1.0D : nanos / 1_000_000.0D;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
