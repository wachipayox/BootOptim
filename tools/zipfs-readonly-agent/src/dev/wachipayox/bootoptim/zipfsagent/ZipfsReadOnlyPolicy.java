package dev.wachipayox.bootoptim.zipfsagent;

import java.nio.file.Path;
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

    private static final AtomicBoolean ACTIVE_LOGGED = new AtomicBoolean();

    private ZipfsReadOnlyPolicy() {
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
                System.err.println(LOG_PREFIX + " ACTIVE project=BootOptim mechanism=" + MECHANISM
                        + " java=" + System.getProperty("java.version"));
            }
            return adjusted;
        } catch (Throwable failure) {
            System.err.println(LOG_PREFIX + " FALLBACK project=BootOptim mechanism=" + MECHANISM
                    + " reason=" + failure);
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
