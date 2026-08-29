package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.JarContents;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The outer BootOptim jar is loaded in FML's SERVICE layer. The actual game mod
 * is kept as a tiny nested jar so it can still be discovered in the GAME layer.
 */
public final class BootOptimModLocator implements IModFileCandidateLocator {
    private static final String CORE_SUFFIX = "-core.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            URL jarJarUrl = Objects.requireNonNull(
                    BootOptimModLocator.class.getResource("/META-INF/jarjar/"),
                    "BootOptim nested mod directory is missing");
            Path jarJarDirectory = Path.of(jarJarUrl.toURI());

            try (var files = Files.walk(jarJarDirectory, 1)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(CORE_SUFFIX))
                        .forEach(path -> pipeline.addJarContent(
                                JarContents.of(path),
                                ModFileDiscoveryAttributes.DEFAULT,
                                IncompatibleFileReporting.ERROR));
            }
        } catch (Exception exception) {
            throw new RuntimeException("Failed to expose the nested BootOptim core mod", exception);
        }
    }

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }
}
