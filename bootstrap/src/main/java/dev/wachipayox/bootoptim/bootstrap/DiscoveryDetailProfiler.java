package dev.wachipayox.bootoptim.bootstrap;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Low-cardinality diagnostic for the early ModLauncher/FML discovery path.
 *
 * <p>This class never changes discovery decisions. It records cumulative JVM/process counters at the existing
 * root/dependency boundaries plus BootOptim's own wrapper lookup/extraction cost. Every diagnostic call is
 * fail-open and is completely disabled unless {@code -Dboot_optim.profileDiscoveryDetail=true} is supplied.</p>
 */
final class DiscoveryDetailProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileDiscoveryDetail");

    private DiscoveryDetailProfiler() {}

    static boolean enabled() {
        return ENABLED;
    }

    static void snapshot(String phase) {
        if (!ENABLED) {
            return;
        }
        try {
            ThreadMXBean threads = Holder.THREADS;
            long threadCpu = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                    ? threads.getCurrentThreadCpuTime()
                    : -1L;
            long processCpu = Holder.OS == null ? -1L : Holder.OS.getProcessCpuTime();
            long gcCount = 0L;
            long gcMillis = 0L;
            for (GarbageCollectorMXBean collector : Holder.GC) {
                long count = collector.getCollectionCount();
                long time = collector.getCollectionTime();
                if (count >= 0L) gcCount += count;
                if (time >= 0L) gcMillis += time;
            }
            CompilationMXBean compilation = Holder.COMPILATION;
            long jitMillis = compilation != null && compilation.isCompilationTimeMonitoringSupported()
                    ? compilation.getTotalCompilationTime()
                    : -1L;
            System.out.printf(
                    "BOOTOPTIM_DISCOVERY_SNAPSHOT phase=%s uptime_ms=%d process_cpu_ms=%.3f current_thread_cpu_ms=%.3f loaded_classes=%d jit_ms=%d gc_count=%d gc_ms=%d%n",
                    phase,
                    ManagementFactory.getRuntimeMXBean().getUptime(),
                    nanosToMillis(processCpu),
                    nanosToMillis(threadCpu),
                    Holder.CLASSES.getTotalLoadedClassCount(),
                    jitMillis,
                    gcCount,
                    gcMillis);
        } catch (Throwable ignored) {
            // Diagnostics must never affect startup.
        }
    }

    static void wrapperLookup(
            String codeSourceScheme,
            String mode,
            int candidateJars,
            int jarOpens,
            long startedNanos,
            boolean found) {
        if (!ENABLED) {
            return;
        }
        try {
            System.out.printf(
                    "BOOTOPTIM_DISCOVERY_SELF phase=wrapper_lookup code_source_scheme=%s mode=%s candidate_jars=%d jar_opens=%d found=%s wall_ms=%.3f%n",
                    sanitize(codeSourceScheme),
                    sanitize(mode),
                    candidateJars,
                    jarOpens,
                    found,
                    (System.nanoTime() - startedNanos) / 1_000_000.0);
        } catch (Throwable ignored) {
            // Diagnostics must never affect startup.
        }
    }

    static void nestedExtraction(String outcome, long startedNanos) {
        if (!ENABLED) {
            return;
        }
        try {
            System.out.printf(
                    "BOOTOPTIM_DISCOVERY_SELF phase=nested_extract outcome=%s wall_ms=%.3f%n",
                    sanitize(outcome),
                    (System.nanoTime() - startedNanos) / 1_000_000.0);
        } catch (Throwable ignored) {
            // Diagnostics must never affect startup.
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos < 0L ? -1.0 : nanos / 1_000_000.0;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace(' ', '_').replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }

    private static final class Holder {
        private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
        private static final java.lang.management.ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
        private static final CompilationMXBean COMPILATION = ManagementFactory.getCompilationMXBean();
        private static final java.util.List<GarbageCollectorMXBean> GC = ManagementFactory.getGarbageCollectorMXBeans();
        private static final com.sun.management.OperatingSystemMXBean OS = processBean();

        private static com.sun.management.OperatingSystemMXBean processBean() {
            try {
                var bean = ManagementFactory.getOperatingSystemMXBean();
                return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
