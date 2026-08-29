package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

/** Owns the persistent cache namespace and invalidates it when the BootOptim version changes. */
public final class CacheVersioning {
    private static final String VERSION_FILE = "cache-version.txt";
    private static volatile boolean prepared;
    private static Path cacheRoot;

    private CacheVersioning() {
    }

    public static Path cacheRoot() {
        ensureCurrent();
        return cacheRoot;
    }

    public static void ensureCurrent() {
        if (prepared) {
            return;
        }
        synchronized (CacheVersioning.class) {
            if (prepared) {
                return;
            }
            Path bootOptimRoot = BootstrapStartupConfig.state().gameDirectory().resolve(".bootoptim");
            try {
                cacheRoot = prepare(bootOptimRoot, BootOptimRuntimeInfo.version());
                prepared = true;
            } catch (Throwable failure) {
                // Version is also included in every cache key, so even if cleanup fails stale entries cannot match.
                cacheRoot = bootOptimRoot.resolve("cache");
                prepared = true;
                StartupDiagnostics.failure("cache_versioning", failure);
            }
        }
    }

    static Path prepare(Path bootOptimRoot, String currentVersion) throws IOException {
        Files.createDirectories(bootOptimRoot);
        Path versionFile = bootOptimRoot.resolve(VERSION_FILE);
        Path currentCacheRoot = bootOptimRoot.resolve("cache");
        String previousVersion = Files.isRegularFile(versionFile)
                ? Files.readString(versionFile, StandardCharsets.UTF_8).trim()
                : null;

        boolean changed = previousVersion == null || !previousVersion.equals(currentVersion);
        if (changed) {
            deleteTree(currentCacheRoot);
            // Clean the pre-versioning cache layout introduced by the first scan-cache implementation.
            try (DirectoryStream<Path> legacy = Files.newDirectoryStream(bootOptimRoot, "mod-scan-cache-*")) {
                for (Path path : legacy) {
                    deleteTree(path);
                }
            }
            Files.createDirectories(currentCacheRoot);
            Files.writeString(
                    versionFile,
                    currentVersion + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            StartupDiagnostics.cache("version_invalidation previous="
                    + (previousVersion == null ? "none" : previousVersion)
                    + " current=" + currentVersion);
        } else {
            Files.createDirectories(currentCacheRoot);
        }

        return currentCacheRoot;
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
