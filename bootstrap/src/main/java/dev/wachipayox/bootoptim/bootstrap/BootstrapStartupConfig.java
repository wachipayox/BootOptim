package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Tiny JDK-only configuration reader used before the regular NeoForge mod is available.
 *
 * <p>A normal NeoForge config is loaded too late for BootOptim's SERVICE-layer work, so the one
 * startup-critical setting intentionally lives in a simple properties file.</p>
 */
public final class BootstrapStartupConfig {
    public static final String DEBUG_PROPERTY = "boot_optim.debug";
    public static final String PROFILE_PROPERTY = "boot_optim.profileStartup";
    public static final String BENCHMARK_PROPERTY = "boot_optim.benchmark.exitOnTitle";
    public static final String DECOCRAFT_3D_ITEMS_PROPERTY = "boot_optim.decocraft3dItems";
    public static final String RESOLVED_LOG_PROPERTY = "boot_optim.startupLog.enabled";
    public static final String RESOLVED_LOG_PATH_PROPERTY = "boot_optim.startupLog.path";
    public static final String RESOLVED_CONFIG_PATH_PROPERTY = "boot_optim.startupLog.configPath";
    public static final String RESOLVED_DECOCRAFT_3D_ITEMS_PROPERTY = "boot_optim.decocraft3dItems.enabled";
    public static final String RESOLVED_DECOCRAFT_3D_ITEMS_SOURCE_PROPERTY = "boot_optim.decocraft3dItems.source";

    private static final String CONFIG_NAME = "boot_optim.properties";
    private static final String LOG_NAME = "bootoptim-startup.log";
    private static volatile State state;

    private BootstrapStartupConfig() {
    }

    /**
     * Initializes startup paths from ModLauncher's authoritative game directory.
     *
     * <p>Launchers commonly keep their process working directory separate from the instance's
     * {@code --gameDir}. Using {@code user.dir} here would therefore place BootOptim's config and
     * caches outside the selected modpack instance.</p>
     */
    public static State initialize(Path gameDirectory) {
        Path resolved = normalize(gameDirectory);
        State current = state;
        if (current != null && current.gameDirectory().equals(resolved)) {
            return current;
        }
        synchronized (BootstrapStartupConfig.class) {
            current = state;
            if (current == null || !current.gameDirectory().equals(resolved)) {
                state = load(resolved);
            }
            return state;
        }
    }

    /**
     * Fallback for code paths that do not have access to ModLauncher's environment.
     * Startup initialization should call {@link #initialize(Path)} first.
     */
    public static State state() {
        State current = state;
        if (current != null) {
            return current;
        }
        synchronized (BootstrapStartupConfig.class) {
            if (state == null) {
                state = load(normalize(Path.of(System.getProperty("user.dir", "."))));
            }
            return state;
        }
    }

    static State load(Path gameDirectory) {
        Path resolvedGameDirectory = normalize(gameDirectory);
        Path configPath = resolvedGameDirectory.resolve("config").resolve(CONFIG_NAME);
        Path logPath = resolvedGameDirectory.resolve("logs").resolve(LOG_NAME);
        boolean configured = false;
        boolean decocraft3dItems = true;
        String decocraft3dItemsSource = "default";
        String problem = null;

        try {
            if (Files.notExists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.writeString(
                        configPath,
                        defaultConfigText(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(configPath)) {
                properties.load(input);
            }
            configured = Boolean.parseBoolean(properties.getProperty("startupLog", "false").trim());

            String configuredDecocraft = properties.getProperty("decocraft3dItems");
            if (configuredDecocraft != null) {
                decocraft3dItems = parseBoolean(configuredDecocraft, true);
                decocraft3dItemsSource = "config";
            } else {
                // Existing installs predate this option. Append it so the setting is discoverable while
                // preserving the requested default-on behavior.
                Files.writeString(
                        configPath,
                        "\n# Use Decocraft's 3D block models for item rendering and omit proven redundant item PNGs.\n"
                                + "# Disable if you prefer Decocraft's original prerendered 2D inventory/hand icons.\n"
                                + "decocraft3dItems=true\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            problem = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }

        String jvmDecocraft = System.getProperty(DECOCRAFT_3D_ITEMS_PROPERTY);
        if (jvmDecocraft != null) {
            decocraft3dItems = parseBoolean(jvmDecocraft, decocraft3dItems);
            decocraft3dItemsSource = "jvm";
        }

        boolean debug = Boolean.getBoolean(DEBUG_PROPERTY);
        boolean profiling = Boolean.getBoolean(PROFILE_PROPERTY) || Boolean.getBoolean(BENCHMARK_PROPERTY);
        boolean enabled = configured || debug || profiling;

        System.setProperty(RESOLVED_LOG_PROPERTY, Boolean.toString(enabled));
        System.setProperty(RESOLVED_LOG_PATH_PROPERTY, logPath.toString());
        System.setProperty(RESOLVED_CONFIG_PATH_PROPERTY, configPath.toString());
        System.setProperty(RESOLVED_DECOCRAFT_3D_ITEMS_PROPERTY, Boolean.toString(decocraft3dItems));
        System.setProperty(RESOLVED_DECOCRAFT_3D_ITEMS_SOURCE_PROPERTY, decocraft3dItemsSource);

        return new State(resolvedGameDirectory, configPath, logPath, configured, debug, profiling, enabled, problem);
    }

    private static String defaultConfigText() {
        return "# BootOptim early-startup settings.\n"
                + "# startupLog writes a lightweight startup report to logs/bootoptim-startup.log.\n"
                + "# Heavy profiling remains opt-in through BootOptim's debug/benchmark tooling.\n"
                + "startupLog=false\n"
                + "\n"
                + "# Use Decocraft's 3D block models for item rendering and omit proven redundant item PNGs.\n"
                + "# Disable if you prefer Decocraft's original prerendered 2D inventory/hand icons.\n"
                + "decocraft3dItems=true\n";
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        String normalized = value == null ? "" : value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    private static Path normalize(Path gameDirectory) {
        Path candidate = gameDirectory == null ? Path.of(System.getProperty("user.dir", ".")) : gameDirectory;
        return candidate.toAbsolutePath().normalize();
    }

    public record State(
            Path gameDirectory,
            Path configPath,
            Path logPath,
            boolean configuredLog,
            boolean debug,
            boolean profiling,
            boolean logEnabled,
            String problem) {
    }
}
