package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapStartupConfigTest {
    @TempDir
    Path tempDirectory;

    @Test
    void writesStartupFilesInsideExplicitGameDirectoryInsteadOfProcessDirectory() throws Exception {
        Path launcherDirectory = tempDirectory.resolve("launcher");
        Path instanceDirectory = tempDirectory.resolve("instances").resolve("community-pack");
        Files.createDirectories(launcherDirectory);
        Files.createDirectories(instanceDirectory);

        String previousUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", launcherDirectory.toString());
            var state = BootstrapStartupConfig.load(instanceDirectory);

            assertEquals(instanceDirectory.toAbsolutePath().normalize(), state.gameDirectory());
            assertEquals(instanceDirectory.resolve("config/boot_optim.properties").toAbsolutePath().normalize(), state.configPath());
            assertEquals(instanceDirectory.resolve("logs/bootoptim-startup.log").toAbsolutePath().normalize(), state.logPath());
            assertTrue(Files.isRegularFile(state.configPath()));
            assertFalse(Files.exists(launcherDirectory.resolve("config/boot_optim.properties")));
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
        }
    }
}
