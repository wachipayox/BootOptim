package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Very low-overhead startup markers used both by local modpack profiling and CI.
 * Profiling is opt-in so normal installations only pay a couple of property reads.
 */
public final class StartupProfiler {
    public static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    public static final String EXIT_ON_TITLE_PROPERTY = "boot_optim.benchmark.exitOnTitle";

    private static final boolean ENABLED = Boolean.getBoolean(PROFILE_PROPERTY)
            || Boolean.getBoolean(EXIT_ON_TITLE_PROPERTY);
    private static final AtomicBoolean MAIN_MENU_REPORTED = new AtomicBoolean();

    private StartupProfiler() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void markModEntrypoint() {
        if (ENABLED) {
            logPhase("mod_entrypoint");
        }
    }

    /**
     * @return true exactly once when the first main menu is reached while profiling is enabled.
     */
    public static boolean markMainMenu() {
        if (!ENABLED || !MAIN_MENU_REPORTED.compareAndSet(false, true)) {
            return false;
        }

        logPhase("main_menu");
        return true;
    }

    public static boolean shouldExitOnTitle() {
        return Boolean.getBoolean(EXIT_ON_TITLE_PROPERTY);
    }

    private static void logPhase(String phase) {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long maxBytes = runtime.maxMemory();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        logger().info(
                "BOOTOPTIM_STARTUP phase={} uptime_ms={} processors={} heap_used_mib={} heap_max_mib={}",
                phase,
                uptimeMs,
                runtime.availableProcessors(),
                bytesToMiB(usedBytes),
                bytesToMiB(maxBytes));
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
