package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheVersioningTest {
    @TempDir
    Path tempDirectory;

    @Test
    void invalidatesCurrentAndLegacyCachesWhenModVersionChanges() throws Exception {
        Path root = tempDirectory.resolve(".bootoptim");
        Path cache = CacheVersioning.prepare(root, "0.1.0");
        Path cachedEntry = cache.resolve("future-cache").resolve("entry.bin");
        Files.createDirectories(cachedEntry.getParent());
        Files.writeString(cachedEntry, "old");

        Path legacyEntry = root.resolve("mod-scan-cache-v1").resolve("legacy.bin");
        Files.createDirectories(legacyEntry.getParent());
        Files.writeString(legacyEntry, "old");

        Path sameVersionCache = CacheVersioning.prepare(root, "0.1.0");
        assertTrue(Files.exists(cachedEntry), "same version must keep cache entries");
        assertTrue(Files.exists(legacyEntry), "legacy cleanup only runs with version invalidation");
        assertEquals(cache, sameVersionCache);

        Path newVersionCache = CacheVersioning.prepare(root, "0.2.0");
        assertFalse(Files.exists(cachedEntry), "version change must delete the current cache namespace");
        assertFalse(Files.exists(legacyEntry), "version change must delete pre-versioning caches too");
        assertTrue(Files.isDirectory(newVersionCache));
        assertEquals("0.2.0", Files.readString(root.resolve("cache-version.txt")).trim());
    }
}
