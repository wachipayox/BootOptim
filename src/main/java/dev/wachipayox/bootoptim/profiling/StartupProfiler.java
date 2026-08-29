package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Startup markers used by local modpack profiling and CI, plus the lightweight user-facing startup report.
 * Heavy profiling stays opt-in; enabling the report alone only records a couple of milestones.
 */
public final class StartupProfiler {
    public static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    public static final String EXIT_ON_TITLE_PROPERTY = "boot_optim.benchmark.exitOnTitle";

    private static final boolean PROFILING_ENABLED = Boolean.getBoolean(PROFILE_PROPERTY)
            || Boolean.getBoolean(EXIT_ON_TITLE_PROPERTY);
    private static final boolean REPORT_ENABLED = StartupReport.isEnabled();
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
