package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;

/**
 * Diagnostic-only timing for the NeoForge/FML client loading lifecycle.
 *
 * <p>The metrics deliberately separate wall time from CPU. Caller CPU is the CPU consumed by the
 * thread that invokes a phase and therefore under-counts phases which dispatch parallel work.
 * Process CPU is an inclusive upper bound: during resource preparation it also contains unrelated
 * resource-loader, compiler and native work running concurrently.</p>
 */
public final class FmlLifecycleProfiler {
    public static final String PROFILE_PROPERTY = "boot_optim.profileFmlLifecycle";

    private static final boolean ENABLED = Boolean.getBoolean(PROFILE_PROPERTY);
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final ClassLoadingMXBean CLASS_LOADING_MX_BEAN = ManagementFactory.getClassLoadingMXBean();
    private static final CompilationMXBean COMPILATION_MX_BEAN = ManagementFactory.getCompilationMXBean();
    private static final com.sun.management.OperatingSystemMXBean PROCESS_MX_BEAN =
            ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
    private static final ConcurrentMap<String, Snapshot> ACTIVE = new ConcurrentHashMap<>();

    private FmlLifecycleProfiler() {
    }

    public static void begin(String phase, String placement, String criticality) {
        if (!ENABLED) {
            return;
        }
        try {
            ACTIVE.putIfAbsent(phase, new Snapshot(
                    System.nanoTime(),
                    currentThreadCpuNanos(),
                    processCpuNanos(),
                    CLASS_LOADING_MX_BEAN.getTotalLoadedClassCount(),
                    compilationTimeMs(),
                    Thread.currentThread().getName(),
                    placement,
                    criticality));
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    public static void end(String phase) {
        if (!ENABLED) {
            return;
        }
        try {
            Snapshot start = ACTIVE.remove(phase);
            if (start == null) {
                return;
            }

            long wallNanos = nonNegativeDelta(start.wallNanos(), System.nanoTime());
            long callerCpuNanos = deltaOrUnavailable(start.callerCpuNanos(), currentThreadCpuNanos());
            long processCpuNanos = deltaOrUnavailable(start.processCpuNanos(), processCpuNanos());
            long classesLoaded = Math.max(0L, CLASS_LOADING_MX_BEAN.getTotalLoadedClassCount() - start.totalLoadedClasses());
            long compilationMs = deltaOrUnavailable(start.compilationMs(), compilationTimeMs());

            logger().info(
                    "BOOTOPTIM_FML_LIFECYCLE phase={} placement={} criticality={} wall_ms={} caller_cpu_ms={} process_cpu_ms={} classes_loaded_delta={} jit_compilation_ms={} thread={} start_thread={}",
                    phase,
                    start.placement(),
                    start.criticality(),
                    formatNanos(wallNanos),
                    formatCpuNanos(callerCpuNanos),
                    formatCpuNanos(processCpuNanos),
                    classesLoaded,
                    compilationMs >= 0L ? Long.toString(compilationMs) : "unavailable",
                    Thread.currentThread().getName(),
                    start.threadName());
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    private static long currentThreadCpuNanos() {
        try {
            if (THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return THREAD_MX_BEAN.getCurrentThreadCpuTime();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
        }
        return -1L;
    }

    private static long processCpuNanos() {
        try {
            return PROCESS_MX_BEAN != null ? PROCESS_MX_BEAN.getProcessCpuTime() : -1L;
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1L;
        }
    }

    private static long compilationTimeMs() {
        try {
            return COMPILATION_MX_BEAN != null && COMPILATION_MX_BEAN.isCompilationTimeMonitoringSupported()
                    ? COMPILATION_MX_BEAN.getTotalCompilationTime()
                    : -1L;
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1L;
        }
    }

    private static long deltaOrUnavailable(long start, long end) {
        return start >= 0L && end >= start ? end - start : -1L;
    }

    private static long nonNegativeDelta(long start, long end) {
        return end >= start ? end - start : 0L;
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatCpuNanos(long nanos) {
        return nanos >= 0L ? formatNanos(nanos) : "unavailable";
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }

    private record Snapshot(
            long wallNanos,
            long callerCpuNanos,
            long processCpuNanos,
            long totalLoadedClasses,
            long compilationMs,
            String threadName,
            String placement,
            String criticality) {
    }
}
