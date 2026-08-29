package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cpw.mods.jarhandling.JarContents;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

class CachingModFileReaderTest {
    @TempDir
    Path gameDir;

    @Test
    void persistsAndReusesStableFmlScanData() throws Exception {
        FMLPaths.loadAbsolutePaths(gameDir);
        Path fixture = Path.of(System.getProperty("bootoptim.fixtureJar"));
        CachingModFileReader reader = new CachingModFileReader();

        PrintStream previousOut = System.out;
        String previousProfile = System.getProperty("boot_optim.profileStartup");
        String previousEnabled = System.getProperty("boot_optim.scanCache");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setProperty("boot_optim.profileStartup", "true");
        System.setProperty("boot_optim.scanCache", "true");
        try {
            ModFile first = assertInstanceOf(ModFile.class,
                    reader.read(JarContents.of(List.of(fixture)), ModFileDiscoveryAttributes.DEFAULT));
            var firstScan = first.compileContent();
            assertTrue(AsyncScanCacheWriter.awaitIdle(Duration.ofSeconds(10)), "Async scan cache writer did not become idle");

            Path cacheDir = gameDir.resolve(".bootoptim/mod-scan-cache-v1");
            assertTrue(java.nio.file.Files.isDirectory(cacheDir));
            try (var files = java.nio.file.Files.list(cacheDir)) {
                assertEquals(1L, files.count());
            }

            ModFile second = assertInstanceOf(ModFile.class,
                    reader.read(JarContents.of(List.of(fixture)), ModFileDiscoveryAttributes.DEFAULT));
            var secondScan = second.compileContent();

            assertEquals(firstScan.getClasses(), secondScan.getClasses());
            assertEquals(firstScan.getAnnotations(), secondScan.getAnnotations());
        } finally {
            restoreProperty("boot_optim.profileStartup", previousProfile);
            restoreProperty("boot_optim.scanCache", previousEnabled);
            System.setOut(previousOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("BOOTOPTIM_SCAN_CACHE result=miss"), output);
        assertTrue(output.contains("BOOTOPTIM_SCAN_CACHE result=hit"), output);
        assertTrue(output.contains("BOOTOPTIM_SCAN_CACHE_WRITE result=success"), output);
        assertTrue(output.contains("elapsed_ms="), output);
        assertTrue(output.contains("scan_window_ms="), output);
    }

    @Test
    void scanCacheRoundTripsClassesWithNoParent() throws Exception {
        ModFileScanData original = new ModFileScanData();
        Type clazz = Type.getObjectType("example/NoParent");
        original.getClasses().add(new ModFileScanData.ClassData(clazz, null, Set.of()));

        Class<?> scanCache = Class.forName(CachingModFileReader.class.getName() + "$ScanCache");
        var writeScanData = scanCache.getDeclaredMethod("writeScanData", DataOutputStream.class, ModFileScanData.class);
        var readScanData = scanCache.getDeclaredMethod("readScanData", DataInputStream.class);
        writeScanData.setAccessible(true);
        readScanData.setAccessible(true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeScanData.invoke(null, out, original);
        }

        ModFileScanData decoded;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            decoded = (ModFileScanData) readScanData.invoke(null, in);
        }

        assertEquals(1, decoded.getClasses().size());
        var decodedClass = decoded.getClasses().iterator().next();
        assertEquals(clazz, decodedClass.clazz());
        assertNull(decodedClass.parent());
        assertTrue(decodedClass.interfaces().isEmpty());
    }

    @Test
    void canDisableCacheForRuntimeAbComparisons() {
        FMLPaths.loadAbsolutePaths(gameDir);
        Path fixture = Path.of(System.getProperty("bootoptim.fixtureJar"));
        String previousEnabled = System.getProperty("boot_optim.scanCache");
        System.setProperty("boot_optim.scanCache", "false");
        try {
            CachingModFileReader reader = new CachingModFileReader();
            ModFile mod = assertInstanceOf(ModFile.class,
                    reader.read(JarContents.of(List.of(fixture)), ModFileDiscoveryAttributes.DEFAULT));
            assertFalse(mod.compileContent().getClasses().isEmpty());
            assertFalse(java.nio.file.Files.exists(gameDir.resolve(".bootoptim/mod-scan-cache-v1")));
        } finally {
            restoreProperty("boot_optim.scanCache", previousEnabled);
        }
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
