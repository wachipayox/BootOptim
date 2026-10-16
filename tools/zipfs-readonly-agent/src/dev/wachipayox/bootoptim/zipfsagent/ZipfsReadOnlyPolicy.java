package dev.wachipayox.bootoptim.zipfsagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides whether one JDK zip file-system open is forced read-only.
 *
 * <p>Loaded on the boot class path by {@link ZipfsReadOnlyAgent} and called from the patched
 * {@code jdk.nio.zipfs.ZipFileSystemProvider.getZipFileSystem}. It never throws: any failure returns the
 * caller's environment unchanged, which is the stock behaviour.
 *
 * <p>Effect when it applies: the ZipFileSystem is built with {@code readOnly = true} and does not call
 * {@code Files.isWritable}, exactly as if the caller had passed {@code accessMode=readOnly}. Nothing on disk
 * changes.
 */
public final class ZipfsReadOnlyPolicy {

    static final String ACCESS_MODE = "accessMode";
    static final String READ_ONLY = "readOnly";
    static final String CREATE = "create";
    static final String LOG_PREFIX = "[WBOMBO-COMPAT]";
    static final String MECHANISM = "zipfs-readonly-agent";

    /**
     * Optional status file. The launcher does not keep the game's stderr on a normal start, so the agent's own
     * ACTIVE and FALLBACK lines go to this file when the JVM is started with {@code -Dwbombo.zipfs.statusFile=...}.
     */
    public static final String STATUS_PROPERTY = "wbombo.zipfs.statusFile";

    private static final AtomicBoolean ACTIVE_LOGGED = new AtomicBoolean();

    private ZipfsReadOnlyPolicy() {
    }

    /** Writes one status line to the status file if configured, otherwise to stderr. Never throws. */
    public static void status(String line) {
        String file = System.getProperty(STATUS_PROPERTY);
        if (file != null && !file.isBlank()) {
            try {
                Files.writeString(Path.of(file), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            } catch (IOException | RuntimeException ignored) {
                // fall through to stderr
            }
        }
        System.err.println(line);
    }

    /** Called from the patched JDK method on every zip open. */
    public static Map<String, ?> adjust(Path path, Map<String, ?> env) {
        try {
            if (!shouldForceReadOnly(path, env)) {
                return env;
            }
            Map<String, Object> adjusted = new HashMap<>(env);
            adjusted.put(ACCESS_MODE, READ_ONLY);
            if (ACTIVE_LOGGED.compareAndSet(false, true)) {
                status(LOG_PREFIX + " ACTIVE project=BootOptim mechanism=" + MECHANISM
                        + " java=" + System.getProperty("java.version"));
            }
            return adjusted;
        } catch (Throwable failure) {
            status(LOG_PREFIX + " FALLBACK project=BootOptim mechanism=" + MECHANISM + " reason=" + failure);
            return env;
        }
    }

    /**
     * Only plain opens of a .jar under a managed root. Any caller that asks for {@code create} or sets
     * {@code accessMode} itself keeps its own behaviour.
     */
    static boolean shouldForceReadOnly(Path path, Map<String, ?> env) {
        if (path == null || env == null) {
            return false;
        }
        if (env.containsKey(ACCESS_MODE) || env.containsKey(CREATE)) {
            return false;
        }
        Path fileName = path.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        return isUnderManagedRoot(path.toAbsolutePath().normalize());
    }

    /**
     * Managed roots: the instance {@code mods} folder (JAR directly inside) and any {@code libraries} ancestor
     * (the shared Pandora library store).
     */
    static boolean isUnderManagedRoot(Path absolute) {
        Path parent = absolute.getParent();
        if (parent != null && parent.getFileName() != null
                && "mods".equalsIgnoreCase(parent.getFileName().toString())) {
            return true;
        }
        for (Path current = parent; current != null; current = current.getParent()) {
            Path name = current.getFileName();
            if (name != null && "libraries".equalsIgnoreCase(name.toString())) {
                return true;
            }
        }
        return false;
    }
}
