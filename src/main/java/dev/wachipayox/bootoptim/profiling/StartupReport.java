package dev.wachipayox.bootoptim.profiling;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends late game-layer information to the report created by the early bootstrap module. */
public final class StartupReport {
    private static final String ENABLED_PROPERTY = "boot_optim.startupLog.enabled";
    private static final String PATH_PROPERTY = "boot_optim.startupLog.path";
    private static final String VERSION_PROPERTY = "boot_optim.version.resolved";
    private static final Object LOCK = new Object();

    private StartupReport() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static void phase(String phase, long uptimeMs) {
        append("PHASE name=" + sanitize(phase) + " uptime_ms=" + uptimeMs);
    }

    public static void complete(long totalStartupMs) {
        append("SUMMARY version=" + sanitize(System.getProperty(VERSION_PROPERTY, "unknown"))
                + " total_startup_ms=" + totalStartupMs
                + " status=main_menu_reached");
    }

    public static void optimization(String id, boolean enabled, String reason) {
        append("OPTIMIZATION id=" + sanitize(id)
                + " status=" + (enabled ? "enabled" : "disabled")
                + " reason=" + sanitize(reason));
    }

    public static void failure(String component, Throwable failure) {
        append("FAILURE component=" + sanitize(component)
                + " detail=" + sanitize(failure.getClass().getName() + ": " + failure.getMessage()));
    }

    private static void append(String line) {
        if (!isEnabled()) {
            return;
        }
        String configuredPath = System.getProperty(PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            try {
                Path path = Path.of(configuredPath);
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Startup reporting is strictly best-effort.
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
