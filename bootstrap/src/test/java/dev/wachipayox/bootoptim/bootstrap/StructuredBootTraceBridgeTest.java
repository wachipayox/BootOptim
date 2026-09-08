package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StructuredBootTraceBridgeTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupBridge() {
        StructuredBootTraceBridge.unpublishForTest();
    }

    @Test
    void regularJarProducerUsesBootstrapOwnedTraceAcrossDistinctClassLoader() throws Exception {
        Path regularJar = Path.of(System.getProperty("bootoptim.fixtureJar"));
        assertTrue(Files.isRegularFile(regularJar));
        try (ZipFile zip = new ZipFile(regularJar.toFile())) {
            assertTrue(zip.getEntry("dev/wachipayox/bootoptim/profiling/RegularBootTraceBridge.class") != null);
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().startsWith("dev/wachipayox/bootoptim/trace/")),
                    "regular mod jar must not contain trace-core classes");
        }

        Path output = tempDir.resolve("bridge.jsonl");
        StructuredBootTrace trace = StructuredBootTrace.create(new StructuredBootTrace.Config(
                StructuredBootTrace.Mode.PROFILE, output, 64, "bridge_test", "regular_probe", "none"));
        assertTrue(StructuredBootTraceBridge.publishForTest(trace));

        URL[] urls = new URL[] {regularJar.toUri().toURL()};
        try (URLClassLoader regularLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> regularBridge = Class.forName(
                    "dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge", true, regularLoader);
            assertNotSame(StructuredBootTrace.class.getClassLoader(), regularBridge.getClassLoader());
            regularBridge.getMethod("recordProbe", String.class).invoke(null, "regular_bridge_test");
        }

        trace.flush();
        List<String> lines = Files.readAllLines(output);
        assertEquals(3, lines.size(), "one header + one regular event + one summary");
        assertTrue(lines.get(0).contains("\"record\":\"trace_header\""));
        assertTrue(lines.get(1).contains("\"type\":\"mod_callback\""));
        assertTrue(lines.get(1).contains("\"phase\":\"regular_bridge_test\""));
        assertTrue(lines.get(1).contains("URLClassLoader"));
        assertTrue(lines.get(2).contains("\"record\":\"trace_summary\""));
        trace.close();
    }
}
