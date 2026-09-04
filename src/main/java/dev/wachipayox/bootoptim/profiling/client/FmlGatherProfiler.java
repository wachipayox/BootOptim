package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;

/** Diagnostic-only split of FML gather/scan/container construction. */
public final class FmlGatherProfiler {
    private static final boolean ENABLED = Boolean.getBoolean(FmlLifecycleProfiler.PROFILE_PROPERTY);
    private static final int TOP_FILES = 20;
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<BuildFrame>> BUILDS = ThreadLocal.withInitial(ArrayDeque::new);

    private FmlGatherProfiler() {
    }

    public static void beginGather() {
        if (!ENABLED) return;
        try {
            STATE.set(new State(System.nanoTime()));
            BUILDS.remove();
        } catch (Throwable ignored) {
        }
    }

    public static void beginScanWait() {
        State state = STATE.get();
        if (state != null) state.scanStarted = System.nanoTime();
    }

    public static void endScanWait() {
        State state = STATE.get();
        if (state != null && state.scanStarted >= 0L) {
            state.scanWaitNanos += delta(state.scanStarted, System.nanoTime());
            state.scanStarted = -1L;
        }
    }

    public static void beginBuildMods(IModFile file) {
        if (!ENABLED || STATE.get() == null) return;
        try {
            BUILDS.get().push(new BuildFrame(file.getFileName(), System.nanoTime(), currentThreadCpuNanos()));
        } catch (Throwable ignored) {
        }
    }

    public static void endBuildMods() {
        State state = STATE.get();
        if (state == null) return;
        try {
            ArrayDeque<BuildFrame> stack = BUILDS.get();
            if (stack.isEmpty()) return;
            BuildFrame frame = stack.pop();
            long wall = delta(frame.startedNanos(), System.nanoTime());
            long cpuNow = currentThreadCpuNanos();
            long cpu = frame.callerCpuNanos() >= 0L && cpuNow >= frame.callerCpuNanos()
                    ? cpuNow - frame.callerCpuNanos()
                    : -1L;
            state.serialBuildNanos += wall;
            if (cpu >= 0L) state.serialBuildCallerCpuNanos += cpu;
            state.builds.add(new BuildResult(frame.fileName(), wall, cpu));
        } catch (Throwable ignored) {
        }
    }

    public static void beginParallelConstruction() {
        State state = STATE.get();
        if (state != null) state.parallelStarted = System.nanoTime();
    }

    public static void endParallelConstruction() {
        State state = STATE.get();
        if (state != null && state.parallelStarted >= 0L) {
            state.parallelNanos += delta(state.parallelStarted, System.nanoTime());
            state.parallelStarted = -1L;
        }
    }

    public static void beginDeferredConstruction() {
        State state = STATE.get();
        if (state != null) state.deferredStarted = System.nanoTime();
    }

    public static void endDeferredConstruction() {
        State state = STATE.get();
        if (state != null && state.deferredStarted >= 0L) {
            state.deferredNanos += delta(state.deferredStarted, System.nanoTime());
            state.deferredStarted = -1L;
        }
    }

    public static void endGather() {
        if (!ENABLED) return;
        try {
            State state = STATE.get();
            if (state == null) return;
            long total = delta(state.gatherStartedNanos, System.nanoTime());
            long known = state.scanWaitNanos + state.serialBuildNanos + state.parallelNanos + state.deferredNanos;
            long residual = Math.max(0L, total - known);

            logger().info(
                    "BOOTOPTIM_FML_GATHER summary=split total_wall_ms={} scan_wait_ms={} serial_buildMods_sum_ms={} serial_buildMods_caller_cpu_ms={} parallel_construct_ms={} construction_deferred_ms={} residual_wall_ms={} build_files={}",
                    fmt(total), fmt(state.scanWaitNanos), fmt(state.serialBuildNanos), fmt(state.serialBuildCallerCpuNanos),
                    fmt(state.parallelNanos), fmt(state.deferredNanos), fmt(residual), state.builds.size());

            List<BuildResult> top = new ArrayList<>(state.builds);
            top.sort(Comparator.comparingLong(BuildResult::wallNanos).reversed());
            int count = Math.min(TOP_FILES, top.size());
            for (int i = 0; i < count; i++) {
                BuildResult result = top.get(i);
                logger().info(
                        "BOOTOPTIM_FML_GATHER dimension=serial_build_file rank={} file={} wall_ms={} caller_cpu_ms={} share_of_serial_pct={}",
                        i + 1, result.fileName(), fmt(result.wallNanos()), fmt(result.callerCpuNanos()),
                        pct(result.wallNanos(), state.serialBuildNanos));
            }
        } catch (Throwable ignored) {
        } finally {
            STATE.remove();
            BUILDS.remove();
        }
    }

    private static long currentThreadCpuNanos() {
        try {
            if (THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return THREAD_MX_BEAN.getCurrentThreadCpuTime();
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static long delta(long start, long end) {
        return end >= start ? end - start : 0L;
    }

    private static String fmt(long nanos) {
        return nanos >= 0L ? String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D) : "unavailable";
    }

    private static String pct(long part, long total) {
        return total > 0L ? String.format(Locale.ROOT, "%.2f", part * 100.0D / total) : "0.00";
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }

    private static final class State {
        private final long gatherStartedNanos;
        private long scanStarted = -1L;
        private long scanWaitNanos;
        private long serialBuildNanos;
        private long serialBuildCallerCpuNanos;
        private long parallelStarted = -1L;
        private long parallelNanos;
        private long deferredStarted = -1L;
        private long deferredNanos;
        private final List<BuildResult> builds = new ArrayList<>();

        private State(long gatherStartedNanos) {
            this.gatherStartedNanos = gatherStartedNanos;
        }
    }

    private record BuildFrame(String fileName, long startedNanos, long callerCpuNanos) {}
    private record BuildResult(String fileName, long wallNanos, long callerCpuNanos) {}
}
