package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryStartLocatorTest {
    private static final String WRAPPER_MARKER =
            "dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class";

    @TempDir
    Path tempDir;

    @Test
    void ignoresUnrelatedModsWithMultipleNestedJarJarDependencies() throws Exception {
        Path unrelated = tempDir.resolve("unrelated.jar");
        writeJar(unrelated,
                "META-INF/jarjar/dependency-a.jar",
                "META-INF/jarjar/dependency-b.jar");

        assertFalse(DiscoveryStartLocator.isBootOptimWrapper(unrelated));
    }

    @Test
    void recognizesBootOptimWrapperByBootstrapMarker() throws Exception {
        Path wrapper = tempDir.resolve("bootoptim.jar");
        writeJar(wrapper,
                WRAPPER_MARKER,
                "META-INF/jarjar/dev.wachipayox.bootoptim.boot_optim-0.1.2.jar");

        assertTrue(DiscoveryStartLocator.isBootOptimWrapper(wrapper));
    }

    private static void writeJar(Path jar, String... entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : entries) {
                output.putNextEntry(new ZipEntry(name));
                output.write(new byte[] {0});
                output.closeEntry();
            }
        }
    }
}
