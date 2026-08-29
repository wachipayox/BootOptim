package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cpw.mods.jarhandling.JarContents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * A coarse benchmark, intentionally kept in CI, that exercises the same FML scanner and cache codec used at runtime.
 * It is not a pass/fail performance test: hosted runners vary, so the numbers are diagnostic only.
 */
class ScanCacheBenchmarkTest {
    private static final int CLASS_COUNT = 8_500;
    private static final int REPETITIONS = 3;

    @TempDir
    Path tempDir;

    @Test
    void compareStockColdAndWarmScanTimes() throws Exception {
        Path sourceFixture = Path.of(System.getProperty("bootoptim.fixtureJar"));
        Path largeJar = tempDir.resolve("large-mod-fixture.jar");
        createLargeFixture(sourceFixture, largeJar);

        // One unmeasured stock pass warms the JVM and filesystem so the comparison focuses on scan work.
        var warmup = scan(new JarModsDotTomlModFileReader(), largeJar);
        assertEquals(CLASS_COUNT, warmup.data().getClasses().size());

        List<Long> stockTimes = new ArrayList<>();
        List<Long> coldTimes = new ArrayList<>();
        List<Long> warmTimes = new ArrayList<>();

        for (int i = 0; i < REPETITIONS; i++) {
            stockTimes.add(scan(new JarModsDotTomlModFileReader(), largeJar).nanos());

            Path gameDir = tempDir.resolve("game-" + i);
            FMLPaths.loadAbsolutePaths(gameDir);

            // Cold measures only the startup-critical scan path. Persistence is intentionally asynchronous.
            var cold = scan(new CachingModFileReader(), largeJar);
            assertTrue(AsyncScanCacheWriter.awaitIdle(Duration.ofSeconds(30)), "Async scan cache writer did not become idle");
            var warm = scan(new CachingModFileReader(), largeJar);
            assertEquals(CLASS_COUNT, cold.data().getClasses().size());
            assertEquals(cold.data().getClasses(), warm.data().getClasses());
            assertEquals(cold.data().getAnnotations(), warm.data().getAnnotations());

            coldTimes.add(cold.nanos());
            warmTimes.add(warm.nanos());
        }

        double stockMs = nanosToMs(median(stockTimes));
        double coldMs = nanosToMs(median(coldTimes));
        double warmMs = nanosToMs(median(warmTimes));
        double coldVsStock = ((coldMs / stockMs) - 1.0) * 100.0;
        double warmSaved = (1.0 - (warmMs / stockMs)) * 100.0;
        double warmSpeedup = stockMs / warmMs;

        System.out.printf(
                "BOOTOPTIM_SCAN_BENCH classes=%d stock_ms=%.3f bootoptim_cold_ms=%.3f bootoptim_warm_ms=%.3f cold_vs_stock_pct=%+.2f warm_saved_pct=%.2f warm_speedup_x=%.2f%n",
                CLASS_COUNT, stockMs, coldMs, warmMs, coldVsStock, warmSaved, warmSpeedup);
    }

    private static ScanResult scan(IModFileReader reader, Path jarPath) {
        ModFile mod = assertInstanceOf(ModFile.class,
                reader.read(JarContents.of(List.of(jarPath)), ModFileDiscoveryAttributes.DEFAULT));
        long start = System.nanoTime();
        var data = mod.compileContent();
        return new ScanResult(System.nanoTime() - start, data);
    }

    private static void createLargeFixture(Path sourceFixture, Path target) throws IOException {
        byte[] modsToml;
        try (JarFile source = new JarFile(sourceFixture.toFile())) {
            JarEntry entry = source.getJarEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                throw new IOException("Fixture mod does not contain META-INF/neoforge.mods.toml");
            }
            try (var in = source.getInputStream(entry)) {
                modsToml = in.readAllBytes();
            }
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "dev.wachipayox.bootoptim.scanbench");

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            JarEntry metadata = new JarEntry("META-INF/neoforge.mods.toml");
            out.putNextEntry(metadata);
            out.write(modsToml);
            out.closeEntry();

            for (int i = 0; i < CLASS_COUNT; i++) {
                String internalName = "dev/wachipayox/bootoptim/scanbench/C" + i;
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);
                if ((i & 7) == 0) {
                    writer.visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd();
                }
                writer.visitEnd();

                JarEntry classEntry = new JarEntry(internalName + ".class");
                out.putNextEntry(classEntry);
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
    }

    private static long median(List<Long> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList().get(values.size() / 2);
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record ScanResult(long nanos, net.neoforged.neoforgespi.language.ModFileScanData data) {}
}
