package dev.wachipayox.bootoptim.bootstrap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Lightweight startup report. It is a no-op unless startup logging/debug profiling is enabled. */
public final class StartupDiagnostics {
    private static final Object LOCK = new Object();
    private static volatile boolean initialized;

    private StartupDiagnostics() {
    }

    public static boolean isEnabled() {
        return BootstrapStartupConfig.state().logEnabled();
    }

    public static void initialize() {
        if (!isEnabled() || initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            var config = BootstrapStartupConfig.state();
            Path log = config.logPath();
            try {
                var runtime = ManagementFactory.getRuntimeMXBean();
                Files.createDirectories(log.getParent());
                Files.writeString(
                        log,
                        "BootOptim startup report\n"
                                + "started=" + Instant.now() + "\n"
                                + "jvm_started=" + Instant.ofEpochMilli(runtime.getStartTime()) + "\n"
                                + "jvm_uptime_at_report_ms=" + runtime.getUptime() + "\n"
                                + "version=" + BootOptimRuntimeInfo.version() + "\n"
                                + "java=" + Runtime.version() + "\n"
                                + "config=" + config.configPath() + "\n"
                                + "startupLog.configured=" + config.configuredLog() + "\n"
                                + "startupLog.debugForced=" + config.debug() + "\n"
                                + "startupLog.profilingForced=" + config.profiling() + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                initialized = true;
                if (config.problem() != null) {
                    append("FAILURE component=config detail=" + sanitize(config.problem()));
                }
            } catch (Throwable ignored) {
                // Diagnostics must never be able to prevent the game from starting.
                initialized = false;
            }
        }
    }

    public static void optimization(String id, boolean enabled, String reason) {
        append("OPTIMIZATION id=" + sanitize(id)
                + " status=" + (enabled ? "enabled" : "disabled")
                + " reason=" + sanitize(reason));
    }

    public static void cache(String message) {
        append("CACHE " + sanitize(message));
    }

    public static void failure(String component, Throwable failure) {
        if (!isEnabled()) {
            return;
        }
        String detail = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        append("FAILURE component=" + sanitize(component) + " detail=" + sanitize(detail));
        if (BootstrapStartupConfig.state().debug()) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            append("STACK component=" + sanitize(component) + " detail=" + sanitize(stack.toString()));
        }
    }

    public static void event(String category, String message) {
        append(sanitize(category) + " " + sanitize(message));
    }

    private static void append(String line) {
        if (!isEnabled()) {
            return;
        }
        initialize();
        if (!initialized) {
            return;
        }
        synchronized (LOCK) {
            try {
                Files.writeString(
                        BootstrapStartupConfig.state().logPath(),
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Fail open: reporting must not affect startup behavior.
            }
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
}
