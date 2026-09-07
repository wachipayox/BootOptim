package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-noise resource-reload lifecycle tracing.
 *
 * <p>Listener hot-path observation is intentionally restricted to {@link System#nanoTime()}, atomics,
 * and the stock preparation barrier/future callbacks. No MXBean snapshot, logger lookup, or log formatting
 * occurs per listener while the initial reload is on the startup critical path. Listener rows are emitted
 * only after the first title frame is presented.</p>
 */
public final class ReloadListenerVarianceProfiler {
    private static final AtomicInteger NEXT_RELOAD_ID = new AtomicInteger();
    private static final CopyOnWriteArrayList<ReloadTrace> RELOADS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean EMITTED = new AtomicBoolean();

    private ReloadListenerVarianceProfiler() {}

    public static ReloadTrace begin() {
        if (!VarianceProbe.enabled()) {
            return null;
        }
        ReloadTrace trace = new ReloadTrace(NEXT_RELOAD_ID.incrementAndGet());
        RELOADS.add(trace);
        return trace;
    }

    /** Emit stored listener timing after the first title-present boundary, outside TTMM. */
    public static void emitAfterTitle() {
        if (!VarianceProbe.enabled() || !EMITTED.compareAndSet(false, true)) {
            return;
        }
        for (ReloadTrace reload : RELOADS) {
            reload.emit();
        }
    }

    public static final class ReloadTrace {
        private final int id;
        private final long startNanos;
        private final VarianceProbe.Stamp started;
        private final AtomicInteger nextListener = new AtomicInteger();
        private final CopyOnWriteArrayList<ListenerTrace> listeners = new CopyOnWriteArrayList<>();
        private final AtomicLong allPreparationsNanos = new AtomicLong(-1L);
        private final AtomicLong allDoneNanos = new AtomicLong(-1L);
        private final AtomicReference<String> allDoneResult = new AtomicReference<>("pending");
        private volatile int expectedListenerCount = -1;

        private ReloadTrace(int id) {
            this.id = id;
            this.startNanos = System.nanoTime();
            this.started = VarianceProbe.start("resource_reload", "reload_" + id);
        }

        public ListenerTrace addListener(PreparableReloadListener listener) {
            ListenerTrace trace = new ListenerTrace(this, nextListener.getAndIncrement(), listener.getClass().getName());
            listeners.add(trace);
            return trace;
        }

        public void observeAllPreparations(CompletableFuture<?> future) {
            if (future == null) {
                return;
            }
            future.whenComplete((ignored, failure) -> {
                allPreparationsNanos.compareAndSet(-1L, System.nanoTime());
                VarianceProbe.point("reload_all_preparations", "reload_" + id + "_result_" + result(failure));
            });
        }

        public void observeAllDone(CompletableFuture<?> future, int listenerCount) {
            expectedListenerCount = listenerCount;
            if (future == null) {
                return;
            }
            future.whenComplete((ignored, failure) -> {
                allDoneNanos.compareAndSet(-1L, System.nanoTime());
                allDoneResult.set(result(failure));
                VarianceProbe.finish(
                        "resource_reload",
                        "reload_" + id + "_listeners_" + listenerCount + "_result_" + result(failure),
                        started);
            });
        }

        private void emit() {
            logger().info(
                    "BOOTOPTIM_VARIANCE_RELOAD reload_id={} expected_listeners={} observed_listeners={} all_preparations_ms={} all_done_ms={} result={}",
                    id,
                    expectedListenerCount,
                    listeners.size(),
                    relativeMs(allPreparationsNanos.get()),
                    relativeMs(allDoneNanos.get()),
                    allDoneResult.get());
            for (ListenerTrace listener : List.copyOf(listeners)) {
                listener.emit(allPreparationsNanos.get());
            }
        }

        private String relativeMs(long absoluteNanos) {
            return absoluteNanos < 0L ? "-1" : format((absoluteNanos - startNanos) / 1_000_000.0D);
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String className;
        private final AtomicInteger barrierCalls = new AtomicInteger();
        private final AtomicLong prepareDoneNanos = new AtomicLong(-1L);
        private final AtomicLong turnReadyNanos = new AtomicLong(-1L);
        private final AtomicLong completionNanos = new AtomicLong(-1L);
        private final AtomicReference<String> turnResult = new AtomicReference<>("missing");
        private final AtomicReference<String> completionResult = new AtomicReference<>("pending");

        private ListenerTrace(ReloadTrace reload, int index, String className) {
            this.reload = reload;
            this.index = index;
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
                        if (turnReadyNanos.compareAndSet(-1L, System.nanoTime())) {
                            turnResult.set(result(failure));
                        }
                    });
                    return future;
                }
            };
        }

        public void observeCompletion(CompletableFuture<?> future) {
            if (future == null) {
                return;
            }
            future.whenComplete((ignored, failure) -> {
                if (completionNanos.compareAndSet(-1L, System.nanoTime())) {
                    completionResult.set(result(failure));
                }
            });
        }

        private void emit(long allPreparationsNanos) {
            long prepared = prepareDoneNanos.get();
            long turn = turnReadyNanos.get();
            long complete = completionNanos.get();
            long globalWait = prepared < 0L || allPreparationsNanos < 0L
                    ? -1L : Math.max(0L, allPreparationsNanos - prepared);
            long orderWait = prepared < 0L || turn < 0L || allPreparationsNanos < 0L
                    ? -1L : Math.max(0L, turn - Math.max(prepared, allPreparationsNanos));
            long postTurn = turn < 0L || complete < 0L ? -1L : Math.max(0L, complete - turn);
            logger().info(
                    "BOOTOPTIM_VARIANCE_LISTENER reload_id={} index={} class={} barrier_calls={} prepare_done_ms={} apply_turn_ms={} complete_ms={} global_wait_ms={} order_wait_ms={} post_turn_ms={} turn_result={} result={}",
                    reload.id,
                    index,
                    token(className),
                    barrierCalls.get(),
                    reload.relativeMs(prepared),
                    reload.relativeMs(turn),
                    reload.relativeMs(complete),
                    nanosToMs(globalWait),
                    nanosToMs(orderWait),
                    nanosToMs(postTurn),
                    turnResult.get(),
                    completionResult.get());
        }
    }

    private static String result(Throwable failure) {
        return failure == null ? "success" : token(failure.getClass().getSimpleName());
    }

    private static String nanosToMs(long nanos) {
        return nanos < 0L ? "-1" : format(nanos / 1_000_000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String token(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9_.$:/#-]", "_");
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/VarianceListeners");
    }
}
