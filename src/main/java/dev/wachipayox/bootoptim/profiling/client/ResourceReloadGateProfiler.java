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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead post-promotion resource-reload gate tracer.
 *
 * <p>This intentionally records only semantic milestones: preparation-barrier arrival, ordered turn readiness,
 * listener completion, all-preparations and all-done. It delegates the original barrier and leaves both executors
 * completely untouched, so this second-generation diagnostic is lighter than PR #47's executor accounting.</p>
 */
public final class ResourceReloadGateProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ReloadGate");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private ResourceReloadGateProfiler() {}

    public static ReloadTrace begin() {
        return StartupProfiler.isEnabled() ? new ReloadTrace(NEXT_ID.incrementAndGet(), System.nanoTime()) : null;
    }

    public static final class ReloadTrace {
        private final int id;
        private final long startNanos;
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final CopyOnWriteArrayList<ListenerTrace> listeners = new CopyOnWriteArrayList<>();
        private final AtomicLong allPreparationsNanos = new AtomicLong(-1L);
        private final AtomicLong allDoneNanos = new AtomicLong(-1L);
        private final AtomicBoolean allDoneObserved = new AtomicBoolean();
        private final AtomicBoolean emitted = new AtomicBoolean();
        private volatile int expectedListeners = -1;

        private ReloadTrace(int id, long startNanos) {
            this.id = id;
            this.startNanos = startNanos;
            LOGGER.info("BOOTOPTIM_RELOAD_GATE event=start reload_id={}", id);
        }

        public ListenerTrace addListener(PreparableReloadListener listener) {
            String className = listener.getClass().getName();
            String name;
            try {
                name = listener.getName();
            } catch (Throwable t) {
                name = className;
            }
            if (name == null || name.isBlank()) name = className;
            ListenerTrace trace = new ListenerTrace(this, nextIndex.getAndIncrement(), name, className);
            listeners.add(trace);
            return trace;
        }

        public void setExpectedListeners(int expectedListeners) {
            this.expectedListeners = expectedListeners;
        }

        public void observeAllPreparations(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                long now = System.nanoTime();
                if (allPreparationsNanos.compareAndSet(-1L, now)) {
                    LOGGER.info("BOOTOPTIM_RELOAD_GATE event=all_preparations reload_id={} at_ms={} result={}",
                            id, ms(now - startNanos), failure == null ? "success" : "failed");
                }
            });
        }

        public void observeAllDone(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                allDoneNanos.compareAndSet(-1L, System.nanoTime());
                allDoneObserved.set(true);
                tryEmit();
            });
        }

        private void tryEmit() {
            if (!allDoneObserved.get() || emitted.get()) return;
            for (ListenerTrace listener : listeners) {
                if (listener.doneNanos.get() < 0L) return;
            }
            if (!emitted.compareAndSet(false, true)) return;
            emit();
        }

        private void emit() {
            long allPrep = allPreparationsNanos.get();
            long allDone = allDoneNanos.get();
            List<ListenerTrace> ordered = new ArrayList<>(listeners);
            ordered.sort(Comparator.comparingInt(value -> value.index));

            LOGGER.info("BOOTOPTIM_RELOAD_GATE event=summary reload_id={} expected_listeners={} observed_listeners={} all_preparations_ms={} all_done_ms={}",
                    id, expectedListeners, ordered.size(), relativeMs(allPrep), relativeMs(allDone));

            for (ListenerTrace listener : ordered) listener.emit(allPrep);

            ListenerTrace prepGate = ordered.stream()
                    .filter(value -> value.prepareDoneNanos.get() >= 0L)
                    .max(Comparator.comparingLong(value -> value.prepareDoneNanos.get()))
                    .orElse(null);
            ListenerTrace orderGate = ordered.stream()
                    .filter(value -> value.orderWaitNanos(allPrep) >= 0L)
                    .max(Comparator.comparingLong(value -> value.orderWaitNanos(allPrep)))
                    .orElse(null);
            ListenerTrace postGate = ordered.stream()
                    .filter(value -> value.postTurnNanos() >= 0L)
                    .max(Comparator.comparingLong(ListenerTrace::postTurnNanos))
                    .orElse(null);

            emitCritical("preparation_gate", prepGate,
                    prepGate == null ? -1L : prepGate.prepareDoneNanos.get() - startNanos);
            emitCritical("order_wait", orderGate,
                    orderGate == null ? -1L : orderGate.orderWaitNanos(allPrep));
            emitCritical("post_turn", postGate,
                    postGate == null ? -1L : postGate.postTurnNanos());
        }

        private void emitCritical(String kind, ListenerTrace trace, long nanos) {
            if (trace == null) return;
            LOGGER.info("BOOTOPTIM_RELOAD_GATE event=critical reload_id={} kind={} index={} value_ms={} name=\"{}\" class={}",
                    id, kind, trace.index, nanos < 0L ? "-1.000" : ms(nanos), trace.name, trace.className);
        }

        private String relativeMs(long absolute) {
            return absolute < 0L ? "-1.000" : ms(absolute - startNanos);
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String name;
        private final String className;
        private final AtomicInteger barrierCalls = new AtomicInteger();
        private final AtomicLong prepareDoneNanos = new AtomicLong(-1L);
        private final AtomicLong turnReadyNanos = new AtomicLong(-1L);
        private final AtomicLong doneNanos = new AtomicLong(-1L);
        private final AtomicBoolean failed = new AtomicBoolean();

        private ListenerTrace(ReloadTrace reload, int index, String name, String className) {
            this.reload = reload;
            this.index = index;
            this.name = name;
            this.className = className;
        }

        public PreparableReloadListener.PreparationBarrier wrapBarrier(PreparableReloadListener.PreparationBarrier original) {
            return new PreparableReloadListener.PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    barrierCalls.incrementAndGet();
                    prepareDoneNanos.compareAndSet(-1L, System.nanoTime());
                    CompletableFuture<T> future = original.wait(value);
                    future.whenComplete((ignored, failure) -> {
                        turnReadyNanos.compareAndSet(-1L, System.nanoTime());
                        if (failure != null) failed.set(true);
                    });
                    return future;
                }
            };
        }

        public void observeCompletion(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                doneNanos.compareAndSet(-1L, System.nanoTime());
                if (failure != null) failed.set(true);
                reload.tryEmit();
            });
        }

        private long orderWaitNanos(long allPrep) {
            long prepared = prepareDoneNanos.get();
            long turn = turnReadyNanos.get();
            if (prepared < 0L || turn < 0L || allPrep < 0L) return -1L;
            return Math.max(0L, turn - Math.max(prepared, allPrep));
        }

        private long globalWaitNanos(long allPrep) {
            long prepared = prepareDoneNanos.get();
            if (prepared < 0L || allPrep < 0L) return -1L;
            return Math.max(0L, allPrep - prepared);
        }

        private long postTurnNanos() {
            long turn = turnReadyNanos.get();
            long done = doneNanos.get();
            if (turn < 0L || done < 0L) return -1L;
            return Math.max(0L, done - turn);
        }

        private void emit(long allPrep) {
            LOGGER.info("BOOTOPTIM_RELOAD_GATE event=listener reload_id={} index={} name=\"{}\" class={} barrier_calls={} prepare_done_ms={} global_wait_ms={} order_wait_ms={} turn_ready_ms={} done_ms={} post_turn_ms={} result={}",
                    reload.id,
                    index,
                    name,
                    className,
                    barrierCalls.get(),
                    reload.relativeMs(prepareDoneNanos.get()),
                    globalWaitNanos(allPrep) < 0L ? "-1.000" : ms(globalWaitNanos(allPrep)),
                    orderWaitNanos(allPrep) < 0L ? "-1.000" : ms(orderWaitNanos(allPrep)),
                    reload.relativeMs(turnReadyNanos.get()),
                    reload.relativeMs(doneNanos.get()),
                    postTurnNanos() < 0L ? "-1.000" : ms(postTurnNanos()),
                    failed.get() ? "failed" : "success");
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }
}
