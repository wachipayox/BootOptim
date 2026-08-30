package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Runs before every normal root-mod locator. Besides starting BootOptim's root discovery timer, this exposes the
 * regular NeoForge mod nested inside the early-service wrapper. FML claims early-service jars before normal root
 * discovery, so the wrapper itself cannot rely on the built-in JarJar dependency pass to discover that nested mod.
 */
public final class DiscoveryStartLocator implements IModFileCandidateLocator {
    private static final String JARJAR_PREFIX = "META-INF/jarjar/";
    private static final String JARJAR_SUFFIX = ".jar";

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.beginRoot();

        Path wrapper = locateWrapper(context).orElse(null);
        if (wrapper == null || !Files.isRegularFile(wrapper)) {
            // Development runs load the ordinary mod directly and do not necessarily execute from a packaged wrapper.
            return;
        }

        try {
            Path nestedMod = extractNestedMod(context.gameDirectory(), wrapper);
            pipeline.addPath(
                    nestedMod,
                    ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                    IncompatibleFileReporting.ERROR);
            StartupDiagnostics.event(
                    "BOOTSTRAP_MOD",
                    "result=exposed wrapper=" + wrapper.getFileName() + " nested=" + nestedMod.getFileName());
        } catch (Throwable failure) {
            StartupDiagnostics.failure("bootstrap_nested_mod_discovery", failure);
            throw new IllegalStateException("Failed to expose BootOptim's nested NeoForge mod from " + wrapper, failure);
        }
    }

    private static Optional<Path> locateWrapper(ILaunchContext context) {
        try {
            CodeSource source = DiscoveryStartLocator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path candidate = Path.of(uri).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate) && containsNestedMod(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        } catch (Exception ignored) {
            // SecureJarHandler may provide a non-file code source. Fall back to the physical mods directory below.
        }

        Path modsDirectory = context.gameDirectory().resolve("mods");
        if (!Files.isDirectory(modsDirectory)) {
            return Optional.empty();
        }

        try (var files = Files.list(modsDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(DiscoveryStartLocator::containsNestedMod)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static boolean containsNestedMod(Path wrapper) {
        try (var zip = new ZipFile(wrapper.toFile())) {
            return findNestedModEntry(zip) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path extractNestedMod(Path gameDirectory, Path wrapper) throws IOException {
        try (var zip = new ZipFile(wrapper.toFile())) {
            ZipEntry entry = findNestedModEntry(zip);
            if (entry == null) {
                throw new IOException("No nested BootOptim mod jar found in " + wrapper);
            }

            String filename = Path.of(entry.getName()).getFileName().toString();
            String identity = Long.toUnsignedString(entry.getCrc(), 16) + "-" + entry.getSize();
            Path target = gameDirectory.resolve(".bootoptim")
                    .resolve("embedded-mod-v1")
                    .resolve(identity)
                    .resolve(filename);

            if (Files.isRegularFile(target) && Files.size(target) == entry.getSize()) {
                return target;
            }

            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), filename, ".tmp");
            try {
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(temp) != entry.getSize()) {
                    throw new IOException("Truncated nested BootOptim mod while extracting " + filename);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return target;
        }
    }

    private static ZipEntry findNestedModEntry(ZipFile zip) {
        ZipEntry match = null;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(JARJAR_PREFIX) || !name.endsWith(JARJAR_SUFFIX)) {
                continue;
            }
            if (match != null) {
                // The wrapper intentionally contains only the regular BootOptim mod. Refuse ambiguity instead of
                // accidentally exposing an unrelated future JarJar dependency as a root mod.
                throw new IllegalStateException("Multiple nested jars found in BootOptim early-service wrapper");
            }
            match = entry;
        }
        return match;
    }

    @Override
    public String toString() {
        return "BootOptimServiceModLocator";
    }
}
