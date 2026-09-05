package dev.wachipayox.bootoptim.profiling;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic-only, low-cardinality attribution for the vanilla Bootstrap.bootStrap() wall.
 *
 * <p>The probe is opt-in and takes exactly two aggregate JVM/process snapshots. It does not sample,
 * enumerate files/classes, change executors, force GC, or alter bootstrap behavior. Management-bean
 * lookup is prepared at method HEAD, before ModernFix's measured field-write boundary, and its setup
 * wall is reported separately so first-use management initialization cannot contaminate that target.</p>
 */
public final class BootstrapVarianceDiagnostic {
    public static final String ENABLE_PROPERTY = "boot_optim.bootstrapVarianceDiagnostic";

    private static Snapshot startSnapshot;
    private static long startNanos;
    private static double setupWallMs = -1.0D;
    private static MetricHandles handles;
    private static String failure;
    private static boolean finished;

    private BootstrapVarianceDiagnostic() {
    }

    /** Prepare management handles before ModernFix starts its vanilla-bootstrap stopwatch. */
    public static synchronized void prepare() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || handles != null || failure != null || finished) {
            return;
        }

        try {
            long setupStart = System.nanoTime();
            handles = MetricHandles.create();
            setupWallMs = (System.nanoTime() - setupStart) / 1_000_000.0D;
        } catch (Throwable throwable) {
            disable(throwable.getClass().getName());
        }
    }

    public static synchronized void begin() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || startSnapshot != null || failure != null || finished) {
            return;
        }
        if (handles == null) {
            disable("not_prepared");
            return;
        }

        try {
            startSnapshot = Snapshot.capture(handles);
            // Start after the snapshot so the reported target wall excludes start-capture overhead.
            startNanos = System.nanoTime();
        } catch (Throwable throwable) {
            disable(throwable.getClass().getName());
        }
    }

    public static synchronized void end() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || finished || failure != null || startSnapshot == null) {
            return;
        }
        finished = true;

        try {
            // Stop before the ending snapshot so reported target wall excludes end-capture/log overhead.
            long endNanos = System.nanoTime();
            Snapshot endSnapshot = Snapshot.capture(handles);
            double wallMs = (endNanos - startNanos) / 1_000_000.0D;

            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_BOOTSTRAP_VARIANCE status=ok wall_ms=%.3f caller_cpu_ms=%.3f process_cpu_ms=%.3f "
                            + "gc_count_delta=%d gc_time_ms=%d loaded_classes_delta=%d total_loaded_classes_delta=%d "
                            + "jit_ms_delta=%d heap_used_mib_start=%d heap_used_mib_end=%d "
                            + "physical_free_mib_start=%d physical_free_mib_end=%d uptime_ms_start=%d uptime_ms_end=%d "
                            + "probe_setup_ms=%.3f%n",
                    wallMs,
                    deltaNanosMs(endSnapshot.callerCpuNanos, startSnapshot.callerCpuNanos),
                    deltaNanosMs(endSnapshot.processCpuNanos, startSnapshot.processCpuNanos),
                    deltaLong(endSnapshot.gcCount, startSnapshot.gcCount),
                    deltaLong(endSnapshot.gcTimeMs, startSnapshot.gcTimeMs),
                    deltaLong(endSnapshot.loadedClasses, startSnapshot.loadedClasses),
                    deltaLong(endSnapshot.totalLoadedClasses, startSnapshot.totalLoadedClasses),
                    deltaLong(endSnapshot.jitTimeMs, startSnapshot.jitTimeMs),
                    mib(startSnapshot.heapUsedBytes),
                    mib(endSnapshot.heapUsedBytes),
                    mib(startSnapshot.physicalFreeBytes),
                    mib(endSnapshot.physicalFreeBytes),
                    startSnapshot.uptimeMs,
                    endSnapshot.uptimeMs,
                    setupWallMs);
        } catch (Throwable throwable) {
            failure = throwable.getClass().getName();
            System.out.printf(
                    "BOOTOPTIM_BOOTSTRAP_VARIANCE status=failed reason=%s%n",
                    token(failure));
        }
    }

    private static void disable(String reason) {
        failure = reason;
        System.out.printf(
                "BOOTOPTIM_BOOTSTRAP_VARIANCE status=disabled reason=%s%n",
                token(reason));
    }

    private static double deltaNanosMs(long end, long start) {
        if (end < 0L || start < 0L) {
            return -1.0D;
        }
        return Math.max(0L, end - start) / 1_000_000.0D;
    }

    private static long deltaLong(long end, long start) {
        if (end < 0L || start < 0L) {
            return -1L;
        }
        return Math.max(0L, end - start);
    }

    private static long mib(long bytes) {
        return bytes < 0L ? -1L : bytes / (1024L * 1024L);
    }

    private static String token(String value) {
        return value == null ? "null" : value.replace(' ', '_').replace('\t', '_').replace('\r', '_').replace('\n', '_');
    }

    private record MetricHandles(
            OperatingSystemMXBean operatingSystem,
            ThreadMXBean threads,
            ClassLoadingMXBean classes,
            CompilationMXBean compiler,
            MemoryMXBean memory,
            List<GarbageCollectorMXBean> collectors) {
        static MetricHandles create() {
            java.lang.management.OperatingSystemMXBean genericOs = ManagementFactory.getOperatingSystemMXBean();
            OperatingSystemMXBean os = genericOs instanceof OperatingSystemMXBean extended ? extended : null;
            return new MetricHandles(
                    os,
                    ManagementFactory.getThreadMXBean(),
                    ManagementFactory.getClassLoadingMXBean(),
                    ManagementFactory.getCompilationMXBean(),
                    ManagementFactory.getMemoryMXBean(),
                    List.copyOf(ManagementFactory.getGarbageCollectorMXBeans()));
        }
    }

    private record Snapshot(
            long uptimeMs,
            long callerCpuNanos,
            long processCpuNanos,
            long gcCount,
            long gcTimeMs,
            long loadedClasses,
            long totalLoadedClasses,
            long jitTimeMs,
            long heapUsedBytes,
            long physicalFreeBytes) {
        static Snapshot capture(MetricHandles handles) {
            ThreadMXBean threads = handles.threads();
            long callerCpu = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                    ? threads.getCurrentThreadCpuTime()
                    : -1L;
            long processCpu = handles.operatingSystem() == null ? -1L : handles.operatingSystem().getProcessCpuTime();
            long physicalFree = handles.operatingSystem() == null ? -1L : handles.operatingSystem().getFreeMemorySize();
            long jitTime = handles.compiler() != null && handles.compiler().isCompilationTimeMonitoringSupported()
                    ? handles.compiler().getTotalCompilationTime()
                    : -1L;

            long gcCount = 0L;
            long gcTime = 0L;
            boolean gcCountAvailable = false;
            boolean gcTimeAvailable = false;
            for (GarbageCollectorMXBean collector : handles.collectors()) {
                long count = collector.getCollectionCount();
                if (count >= 0L) {
                    gcCount += count;
                    gcCountAvailable = true;
                }
                long time = collector.getCollectionTime();
                if (time >= 0L) {
                    gcTime += time;
                    gcTimeAvailable = true;
                }
            }

            return new Snapshot(
                    ManagementFactory.getRuntimeMXBean().getUptime(),
                    callerCpu,
                    processCpu,
                    gcCountAvailable ? gcCount : -1L,
                    gcTimeAvailable ? gcTime : -1L,
                    handles.classes().getLoadedClassCount(),
                    handles.classes().getTotalLoadedClassCount(),
                    jitTime,
                    handles.memory().getHeapMemoryUsage().getUsed(),
                    physicalFree);
        }
    }
}
