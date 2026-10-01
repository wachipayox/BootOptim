package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Startup markers used by local modpack profiling and CI, plus the lightweight user-facing startup report.
 * Heavy profiling stays opt-in; enabling the report alone only records a couple of milestones.
 */
public final class StartupProfiler {
    public static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    public static final String EXIT_ON_TITLE_PROPERTY = "boot_optim.benchmark.exitOnTitle";
    public static final String P02_JVM_PROBE_PROPERTY = "boot_optim.p02JvmProbe";

    private static final boolean PROFILING_ENABLED = Boolean.getBoolean(PROFILE_PROPERTY)
            || Boolean.getBoolean(EXIT_ON_TITLE_PROPERTY);
    private static final boolean REPORT_ENABLED = StartupReport.isEnabled();
    private static final boolean P02_JVM_PROBE_ENABLED = Boolean.getBoolean(P02_JVM_PROBE_PROPERTY);
    private static final AtomicBoolean MAIN_MENU_REPORTED = new AtomicBoolean();

    private StartupProfiler() {
    }

    public static boolean isEnabled() {
        return PROFILING_ENABLED || REPORT_ENABLED;
    }

    public static void markModEntrypoint() {
        if (REPORT_ENABLED) {
            StartupReport.phase("mod_entrypoint", uptimeMs());
        }
        if (PROFILING_ENABLED) {
            logPhase("mod_entrypoint");
        }
        if (P02_JVM_PROBE_ENABLED) {
            p02Snapshot("mod_entrypoint");
        }
    }

    /**
     * @return true exactly once when the first main menu is reached while profiling/reporting is enabled.
     */
    public static boolean markMainMenu() {
        if (!isEnabled() || !MAIN_MENU_REPORTED.compareAndSet(false, true)) {
            return false;
        }

        long uptimeMs = uptimeMs();
        // Finish the on-disk report before emitting the console marker used by CI to terminate the client.
        if (REPORT_ENABLED) {
            StartupReport.phase("main_menu", uptimeMs);
            StartupReport.complete(uptimeMs);
        }
        if (PROFILING_ENABLED) {
            logPhase("main_menu");
        }
        if (P02_JVM_PROBE_ENABLED) {
            p02Snapshot("main_menu");
        }
        return true;
    }

    public static boolean shouldExitOnTitle() {
        return Boolean.getBoolean(EXIT_ON_TITLE_PROPERTY);
    }

    private static void logPhase(String phase) {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long maxBytes = runtime.maxMemory();
        long uptimeMs = uptimeMs();

        logger().info(
                "BOOTOPTIM_STARTUP phase={} uptime_ms={} processors={} heap_used_mib={} heap_max_mib={}",
                phase,
                uptimeMs,
                runtime.availableProcessors(),
                bytesToMiB(usedBytes),
                bytesToMiB(maxBytes));
    }

    /** Default-off P0.2 observer. Runs with this enabled are diagnostic, never clean TTMM samples. */
    private static void p02Snapshot(String phase) {
        try {
            var runtimeBean = ManagementFactory.getRuntimeMXBean();
            var classBean = ManagementFactory.getClassLoadingMXBean();
            var memoryBean = ManagementFactory.getMemoryMXBean();
            var threadBean = ManagementFactory.getThreadMXBean();
            long gcCount = 0L;
            long gcTimeMs = 0L;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = gc.getCollectionCount();
                long time = gc.getCollectionTime();
                if (count >= 0L) gcCount += count;
                if (time >= 0L) gcTimeMs += time;
            }
            long processCpuMs = ProcessHandle.current().info().totalCpuDuration()
                    .map(Duration::toMillis)
                    .orElse(-1L);
            var heap = memoryBean.getHeapMemoryUsage();
            System.out.printf(
                    "BOOTOPTIM_P02_JVM phase=%s uptime_ms=%d jvm_start_epoch_ms=%d process_cpu_ms=%d "
                            + "loaded_classes=%d total_loaded_classes=%d unloaded_classes=%d gc_count=%d gc_time_ms=%d "
                            + "heap_used_mib=%d heap_committed_mib=%d threads=%d%n",
                    phase,
                    runtimeBean.getUptime(),
                    runtimeBean.getStartTime(),
                    processCpuMs,
                    classBean.getLoadedClassCount(),
                    classBean.getTotalLoadedClassCount(),
                    classBean.getUnloadedClassCount(),
                    gcCount,
                    gcTimeMs,
                    heap.getUsed() / (1024L * 1024L),
                    heap.getCommitted() / (1024L * 1024L),
                    threadBean.getThreadCount());
        } catch (Throwable error) {
            System.out.printf("BOOTOPTIM_P02_JVM phase=%s error=%s%n", phase, error.getClass().getName());
        }
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private static long bytesToMiB(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }
}
