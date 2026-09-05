package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;

/**
 * Diagnostic-only critical-tail trace from FancyMenu preload return through first title present.
 *
 * <p>The trace observes stock futures/barriers and wraps only apply executor Runnables so the exact
 * listener owning FancyMenu's {@code preLoadAll} call can be identified dynamically. It never
 * sleeps, parks, reorders work, replaces a future, changes a result, or moves GL/OpenAL work.</p>
 */
public final class PostFancyMenuTailProfiler {
    public static final String PROPERTY = "boot_optim.profilePostFancyMenuTail";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicBoolean FIRST_RELOAD_CLAIMED = new AtomicBoolean();
    private static final AtomicReference<ReloadTrace> ACTIVE_RELOAD = new AtomicReference<>();
    private static final ThreadLocal<ListenerTrace> APPLY_CONTEXT = new ThreadLocal<>();

    private PostFancyMenuTailProfiler() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean hasActiveTrace() {
        return ENABLED && ACTIVE_RELOAD.get() != null;
    }

    public static ReloadTrace beginReload() {
        if (!ENABLED || !FIRST_RELOAD_CLAIMED.compareAndSet(false, true)) {
            return null;
        }
        CpuSupport.initialize();
        ReloadTrace trace = new ReloadTrace(System.nanoTime(), Thread.currentThread().threadId());
        ACTIVE_RELOAD.set(trace);
        return trace;
    }

    /** Called at the exact RETURN of FancyMenu ResourcePreLoader.preLoadAll. */
    public static void markPreloadReturn() {
        ReloadTrace trace = ACTIVE_RELOAD.get();
        if (trace != null) {
            trace.markPreloadReturn(APPLY_CONTEXT.get());
        }
    }

    public static void markTitleOpen() {
        ReloadTrace trace = ACTIVE_RELOAD.get();
        if (trace != null) {
            trace.markTitleOpen();
        }
    }

    public static void markTitleRenderReturn() {
        ReloadTrace trace = ACTIVE_RELOAD.get();
        if (trace != null) {
            trace.markTitleRenderReturn();
        }
    }

    /** Benchmark harness only: delay the existing exit-on-title stop until one title frame presents. */
    public static void requestExitAfterPresent() {
        ReloadTrace trace = ACTIVE_RELOAD.get();
        if (trace != null) {
            trace.exitAfterPresent.set(true);
        }
    }

    /**
     * @return true exactly when the first title-present boundary is recorded and the benchmark
     *         harness requested its normal stop after that boundary.
     */
    public static boolean markTitlePresentReturn() {
        ReloadTrace trace = ACTIVE_RELOAD.get();
        return trace != null && trace.markTitlePresentReturn() && trace.exitAfterPresent.get();
    }

    public static final class ReloadTrace {
        private static final PostFancyMenuTailTimeline.Event[] CPU_EVENTS = {
                PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS,
                PostFancyMenuTailTimeline.Event.PRELOAD_RETURN,
                PostFancyMenuTailTimeline.Event.ALL_DONE,
                PostFancyMenuTailTimeline.Event.TITLE_OPEN,
                PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN
        };

        private final long startNanos;
        private final long renderThreadId;
        private final PostFancyMenuTailTimeline timeline = new PostFancyMenuTailTimeline();
        private final ConcurrentHashMap<PostFancyMenuTailTimeline.Event, CpuSnapshot> cpuSnapshots =
                new ConcurrentHashMap<>();
        private final AtomicInteger nextListenerIndex = new AtomicInteger();
        private final AtomicInteger observedListeners = new AtomicInteger();
        private final AtomicBoolean allPreparationsObserved = new AtomicBoolean();
        private final AtomicBoolean allDoneObserved = new AtomicBoolean();
        private final AtomicBoolean failureObserved = new AtomicBoolean();
        private final AtomicBoolean listenerResolutionFailed = new AtomicBoolean();
        private final AtomicBoolean renderThreadMismatch = new AtomicBoolean();
        private final AtomicBoolean exitAfterPresent = new AtomicBoolean();
        private volatile int expectedListenerCount = -1;
        private volatile ListenerTrace fancyMenuListener;

        private ReloadTrace(long startNanos, long renderThreadId) {
            this.startNanos = startNanos;
            this.renderThreadId = renderThreadId;
        }

        public ListenerTrace addListener(PreparableReloadListener listener) {
            observedListeners.incrementAndGet();
            String className = listener.getClass().getName();
            String name;
            try {
                name = listener.getName();
            } catch (Throwable failure) {
                name = "<getName_failed_" + failure.getClass().getSimpleName() + ">";
            }
            if (name == null || name.isBlank()) {
                name = className;
            }
            return new ListenerTrace(this, nextListenerIndex.getAndIncrement(), name, className);
        }

        public void setExpectedListenerCount(int expectedListenerCount) {
            this.expectedListenerCount = expectedListenerCount;
        }

        /** Register this callback as soon as the first StateFactory invocation is intercepted. */
        public void observeAllPreparations(CompletableFuture<?> future) {
            if (!allPreparationsObserved.compareAndSet(false, true)) {
                return;
            }
            future.whenComplete((ignored, failure) -> {
                long now = System.nanoTime();
                if (failure != null) {
                    failureObserved.set(true);
                }
                if (timeline.record(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS, now)) {
                    captureCpu(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS);
                }
            });
        }

        public void observeAllDone(CompletableFuture<?> future) {
            if (!allDoneObserved.compareAndSet(false, true)) {
                return;
            }
            future.whenComplete((ignored, failure) -> {
                long now = System.nanoTime();
                if (failure != null) {
                    failureObserved.set(true);
                }
                if (timeline.record(PostFancyMenuTailTimeline.Event.ALL_DONE, now)) {
                    captureCpu(PostFancyMenuTailTimeline.Event.ALL_DONE);
                }
            });
        }

        private void markPreloadReturn(ListenerTrace listener) {
            validateRenderThread();
            long now = System.nanoTime();
            if (timeline.record(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN, now)) {
                captureCpu(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN);
            }

            if (listener == null || listener.reload != this) {
                listenerResolutionFailed.set(true);
                return;
            }

            synchronized (this) {
                if (fancyMenuListener == null) {
                    fancyMenuListener = listener;
                } else if (fancyMenuListener != listener) {
                    listenerResolutionFailed.set(true);
                    return;
                }
            }
            listener.publishAlreadyObservedBoundaries();
        }

        private void markTitleOpen() {
            validateRenderThread();
            long now = System.nanoTime();
            if (timeline.record(PostFancyMenuTailTimeline.Event.TITLE_OPEN, now)) {
                captureCpu(PostFancyMenuTailTimeline.Event.TITLE_OPEN);
            }
        }

        private void markTitleRenderReturn() {
            if (timeline.timestamp(PostFancyMenuTailTimeline.Event.TITLE_OPEN) < 0L) {
                return;
            }
            validateRenderThread();
            timeline.record(PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN, System.nanoTime());
        }

        private boolean markTitlePresentReturn() {
            if (timeline.timestamp(PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN) < 0L) {
                return false;
            }
            validateRenderThread();
            long now = System.nanoTime();
            if (!timeline.record(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN, now)) {
                return false;
            }
            captureCpu(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN);
            emitSummary();
            return true;
        }

        private void captureCpu(PostFancyMenuTailTimeline.Event event) {
            cpuSnapshots.putIfAbsent(event, CpuSupport.snapshot(renderThreadId));
        }

        private void validateRenderThread() {
            if (Thread.currentThread().threadId() != renderThreadId) {
                renderThreadMismatch.set(true);
            }
        }

        private boolean isFancyMenuListener(ListenerTrace listener) {
            return fancyMenuListener == listener;
        }

        private void onFancyTurnReady(long nanoTime) {
            timeline.record(PostFancyMenuTailTimeline.Event.FANCYMENU_TURN_READY, nanoTime);
        }

        private void onFancyListenerComplete(long nanoTime) {
            timeline.record(PostFancyMenuTailTimeline.Event.FANCYMENU_LISTENER_COMPLETE, nanoTime);
        }

        private void emitSummary() {
            if (!timeline.claimEmission()) {
                return;
            }

            ListenerTrace listener = fancyMenuListener;
            String status;
            if (listenerResolutionFailed.get() || listener == null) {
                status = "listener_unresolved";
            } else if (expectedListenerCount < 0 || expectedListenerCount != observedListeners.get()) {
                status = "listener_coverage_invalid";
            } else if (failureObserved.get() || listener.failed.get()) {
                status = "future_failed";
            } else if (renderThreadMismatch.get()) {
                status = "render_thread_mismatch";
            } else if (!timeline.isComplete()) {
                status = "incomplete";
            } else if (!timeline.isMonotonic()) {
                status = "non_monotonic";
            } else {
                status = "ok";
            }

            LOGGER.info(
                    "BOOTOPTIM_POST_FANCYMENU_TAIL status={} cpu_status={} expected_listeners={} observed_listeners={} render_thread_id={} fancymenu_index={} fancymenu_name=\"{}\" fancymenu_class={} all_preparations_ms={} turn_ready_ms={} preload_return_ms={} listener_complete_ms={} all_done_ms={} title_open_ms={} title_render_return_ms={} title_present_return_ms={} turn_to_preload_ms={} preload_to_listener_complete_ms={} listener_complete_to_all_done_ms={} all_done_to_title_open_ms={} title_open_to_render_ms={} render_to_present_ms={} preload_to_present_ms={} process_cpu_all_preparations_ms={} render_cpu_all_preparations_ms={} process_cpu_preload_return_ms={} render_cpu_preload_return_ms={} process_cpu_all_done_ms={} render_cpu_all_done_ms={} process_cpu_title_open_ms={} render_cpu_title_open_ms={} process_cpu_title_present_ms={} render_cpu_title_present_ms={}",
                    status,
                    cpuStatus(),
                    expectedListenerCount,
                    observedListeners.get(),
                    renderThreadId,
                    listener == null ? -1 : listener.index,
                    listener == null ? "na" : sanitize(listener.name),
                    listener == null ? "na" : sanitize(listener.className),
                    relativeMs(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS),
                    relativeMs(PostFancyMenuTailTimeline.Event.FANCYMENU_TURN_READY),
                    relativeMs(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN),
                    relativeMs(PostFancyMenuTailTimeline.Event.FANCYMENU_LISTENER_COMPLETE),
                    relativeMs(PostFancyMenuTailTimeline.Event.ALL_DONE),
                    relativeMs(PostFancyMenuTailTimeline.Event.TITLE_OPEN),
                    relativeMs(PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN),
                    relativeMs(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN),
                    segmentMs(PostFancyMenuTailTimeline.Event.FANCYMENU_TURN_READY,
                            PostFancyMenuTailTimeline.Event.PRELOAD_RETURN),
                    segmentMs(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN,
                            PostFancyMenuTailTimeline.Event.FANCYMENU_LISTENER_COMPLETE),
                    segmentMs(PostFancyMenuTailTimeline.Event.FANCYMENU_LISTENER_COMPLETE,
                            PostFancyMenuTailTimeline.Event.ALL_DONE),
                    segmentMs(PostFancyMenuTailTimeline.Event.ALL_DONE,
                            PostFancyMenuTailTimeline.Event.TITLE_OPEN),
                    segmentMs(PostFancyMenuTailTimeline.Event.TITLE_OPEN,
                            PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN),
                    segmentMs(PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN,
                            PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN),
                    segmentMs(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN,
                            PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN),
                    processCpuMs(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS),
                    renderCpuMs(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS),
                    processCpuMs(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN),
                    renderCpuMs(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN),
                    processCpuMs(PostFancyMenuTailTimeline.Event.ALL_DONE),
                    renderCpuMs(PostFancyMenuTailTimeline.Event.ALL_DONE),
                    processCpuMs(PostFancyMenuTailTimeline.Event.TITLE_OPEN),
                    renderCpuMs(PostFancyMenuTailTimeline.Event.TITLE_OPEN),
                    processCpuMs(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN),
                    renderCpuMs(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN));
        }

        private String cpuStatus() {
            boolean processUnavailable = false;
            boolean renderUnavailable = false;
            for (PostFancyMenuTailTimeline.Event event : CPU_EVENTS) {
                CpuSnapshot snapshot = cpuSnapshots.get(event);
                if (snapshot == null || snapshot.processCpuNanos < 0L) {
                    processUnavailable = true;
                }
                if (snapshot == null || snapshot.renderThreadCpuNanos < 0L) {
                    renderUnavailable = true;
                }
            }
            if (processUnavailable && renderUnavailable) {
                return "process_and_render_unavailable";
            }
            if (processUnavailable) {
                return "process_unavailable";
            }
            if (renderUnavailable) {
                return "render_unavailable";
            }
            return "ok";
        }

        private String relativeMs(PostFancyMenuTailTimeline.Event event) {
            long timestamp = timeline.timestamp(event);
            return formatNanos(timestamp < 0L ? -1L : timestamp - startNanos);
        }

        private String segmentMs(PostFancyMenuTailTimeline.Event from, PostFancyMenuTailTimeline.Event to) {
            long start = timeline.timestamp(from);
            long end = timeline.timestamp(to);
            return formatNanos(start < 0L || end < start ? -1L : end - start);
        }

        private String processCpuMs(PostFancyMenuTailTimeline.Event event) {
            CpuSnapshot snapshot = cpuSnapshots.get(event);
            return formatNanos(snapshot == null ? -1L : snapshot.processCpuNanos);
        }

        private String renderCpuMs(PostFancyMenuTailTimeline.Event event) {
            CpuSnapshot snapshot = cpuSnapshots.get(event);
            return formatNanos(snapshot == null ? -1L : snapshot.renderThreadCpuNanos);
        }
    }

    public static final class ListenerTrace {
        private final ReloadTrace reload;
        private final int index;
        private final String name;
        private final String className;
        private final AtomicLong turnReadyNanos = new AtomicLong(-1L);
        private final AtomicLong listenerCompleteNanos = new AtomicLong(-1L);
        private final AtomicBoolean failed = new AtomicBoolean();

        private ListenerTrace(ReloadTrace reload, int index, String name, String className) {
            this.reload = reload;
            this.index = index;
            this.name = name;
            this.className = className;
        }

        public PreparableReloadListener.PreparationBarrier wrapBarrier(
                PreparableReloadListener.PreparationBarrier original) {
            return new PreparableReloadListener.PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    CompletableFuture<T> future = original.wait(value);
                    future.whenComplete((ignored, failure) -> {
                        long now = System.nanoTime();
                        turnReadyNanos.compareAndSet(-1L, now);
                        if (failure != null) {
                            failed.set(true);
                        }
                        if (reload.isFancyMenuListener(ListenerTrace.this)) {
                            reload.onFancyTurnReady(turnReadyNanos.get());
                        }
                    });
                    return future;
                }
            };
        }

        /**
         * Delegates each apply task exactly once while exposing only the owning listener identity
         * to the thread executing that task. No queue/runtime timing is collected here.
         */
        public Executor wrapApplyExecutor(Executor original) {
            return command -> original.execute(() -> {
                ListenerTrace previous = APPLY_CONTEXT.get();
                APPLY_CONTEXT.set(this);
                try {
                    command.run();
                } finally {
                    if (previous == null) {
                        APPLY_CONTEXT.remove();
                    } else {
                        APPLY_CONTEXT.set(previous);
                    }
                }
            });
        }

        public void observeCompletion(CompletableFuture<?> future) {
            future.whenComplete((ignored, failure) -> {
                long now = System.nanoTime();
                listenerCompleteNanos.compareAndSet(-1L, now);
                if (failure != null) {
                    failed.set(true);
                }
                if (reload.isFancyMenuListener(this)) {
                    reload.onFancyListenerComplete(listenerCompleteNanos.get());
                }
            });
        }

        private void publishAlreadyObservedBoundaries() {
            long turnReady = turnReadyNanos.get();
            if (turnReady >= 0L) {
                reload.onFancyTurnReady(turnReady);
            }
            long complete = listenerCompleteNanos.get();
            if (complete >= 0L) {
                reload.onFancyListenerComplete(complete);
            }
        }
    }

    private record CpuSnapshot(long processCpuNanos, long renderThreadCpuNanos) {}

    /** Management beans are initialized only after the opt-in diagnostic claims the startup reload. */
    private static final class CpuSupport {
        private static volatile ThreadMXBean threadBean;
        private static volatile com.sun.management.OperatingSystemMXBean operatingSystemBean;
        private static volatile boolean threadCpuEnabled;

        private CpuSupport() {}

        private static synchronized void initialize() {
            if (threadBean != null) {
                return;
            }
            try {
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                if (bean.isThreadCpuTimeSupported()) {
                    if (!bean.isThreadCpuTimeEnabled()) {
                        bean.setThreadCpuTimeEnabled(true);
                    }
                    threadCpuEnabled = bean.isThreadCpuTimeEnabled();
                }
                threadBean = bean;
            } catch (SecurityException | UnsupportedOperationException ignored) {
                threadBean = ManagementFactory.getThreadMXBean();
                threadCpuEnabled = false;
            }

            try {
                if (ManagementFactory.getOperatingSystemMXBean()
                        instanceof com.sun.management.OperatingSystemMXBean extended) {
                    operatingSystemBean = extended;
                }
            } catch (RuntimeException ignored) {
                operatingSystemBean = null;
            }
        }

        private static CpuSnapshot snapshot(long renderThreadId) {
            long processCpu = -1L;
            long renderCpu = -1L;
            try {
                com.sun.management.OperatingSystemMXBean os = operatingSystemBean;
                if (os != null) {
                    processCpu = os.getProcessCpuTime();
                }
            } catch (RuntimeException ignored) {
                processCpu = -1L;
            }
            try {
                ThreadMXBean bean = threadBean;
                if (threadCpuEnabled && bean != null) {
                    renderCpu = bean.getThreadCpuTime(renderThreadId);
                }
            } catch (RuntimeException | UnsupportedOperationException ignored) {
                renderCpu = -1L;
            }
            return new CpuSnapshot(processCpu, renderCpu);
        }
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) {
            return "na";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String sanitize(String value) {
        return value.replace('\\', '/').replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }
}
