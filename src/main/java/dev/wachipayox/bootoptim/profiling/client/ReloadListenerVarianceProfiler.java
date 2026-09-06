package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Low-cardinality resource-reload state tracing.
 *
 * <p>Only the semantic preparation barrier and each listener's existing completion future are
 * observed. Executors and their tasks are not wrapped, so this does not become per-task profiling.</p>
 */
public final class ReloadListenerVarianceProfiler {
    private static final AtomicInteger NEXT_RELOAD_ID = new AtomicInteger();

    private ReloadListenerVarianceProfiler() {}

    public static ReloadTrace begin() {
        if (!VarianceProbe.enabled()) {
            return null;
        }
        return new ReloadTrace(NEXT_RELOAD_ID.incrementAndGet());
    }

    public static final class ReloadTrace {
        private final int id;
        private final VarianceProbe.Stamp started;
        private final AtomicInteger nextListener = new AtomicInteger();

        private ReloadTrace(int id) {
            this.id = id;
            this.started = VarianceProbe.start("resource_reload", "reload_" + id);
        }

        public ListenerTrace addListener(PreparableReloadListener listener) {
            return new ListenerTrace(this, nextListener.getAndIncrement(), listener.getClass().getName());
        }

        public void observeAllPreparations(CompletableFuture<?> future) {
            if (future == null) {
                return;
            }
            future.whenComplete((ignored, failure) -> VarianceProbe.point(
                    "reload_all_preparations",
                    "reload_" + id + "_result_" + result(failure)));
        }

        public void observeAllDone(CompletableFuture<?> future, int listenerCount) {
            if (future == null) {
                return;
            }
            future.whenComplete((ignored, failure) -> VarianceProbe.finish(
                    "resource_reload",
                    "reload_" + id + "_listeners_" + listenerCount + "_result_" + result(failure),
                    started));
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String className;
        private final AtomicInteger barrierCalls = new AtomicInteger();
        private final AtomicBoolean prepareLogged = new AtomicBoolean();
        private final AtomicBoolean turnLogged = new AtomicBoolean();
        private final AtomicBoolean completionLogged = new AtomicBoolean();

        private ListenerTrace(ReloadTrace reload, int index, String className) {
            this.reload = reload;
            this.index = index;
            this.className = className;
        }

        public PreparableReloadListener.PreparationBarrier wrapBarrier(
                PreparableReloadListener.PreparationBarrier original) {
            return new PreparableReloadListener.PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    int calls = barrierCalls.incrementAndGet();
                    if (prepareLogged.compareAndSet(false, true)) {
                        VarianceProbe.point("reload_listener_prepare_done", subject("barrier_" + calls));
                    }
                    CompletableFuture<T> future = original.wait(value);
                    future.whenComplete((ignored, failure) -> {
                        if (turnLogged.compareAndSet(false, true)) {
                            VarianceProbe.point(
                                    "reload_listener_apply_turn",
                                    subject("result_" + result(failure)));
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
                if (completionLogged.compareAndSet(false, true)) {
                    VarianceProbe.point(
                            "reload_listener_complete",
                            subject("barriers_" + barrierCalls.get() + "_result_" + result(failure)));
                }
            });
        }

        private String subject(String suffix) {
            return "reload_" + reload.id + "_listener_" + index + "_" + className + "_" + suffix;
        }
    }

    private static String result(Throwable failure) {
        return failure == null ? "success" : failure.getClass().getSimpleName();
    }
}
