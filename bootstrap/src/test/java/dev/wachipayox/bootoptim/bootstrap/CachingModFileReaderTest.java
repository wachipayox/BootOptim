package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cpw.mods.jarhandling.JarContents;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setProperty("boot_optim.profileStartup", "true");
        try {
            ModFile first = assertInstanceOf(ModFile.class,
                    reader.read(JarContents.of(List.of(fixture)), ModFileDiscoveryAttributes.DEFAULT));
            var firstScan = first.compileContent();

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
            if (previousProfile == null) {
                System.clearProperty("boot_optim.profileStartup");
            } else {
                System.setProperty("boot_optim.profileStartup", previousProfile);
            }
            System.setOut(previousOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("BOOTOPTIM_SCAN_CACHE result=miss"), output);
        assertTrue(output.contains("BOOTOPTIM_SCAN_CACHE result=hit"), output);
    }
}
