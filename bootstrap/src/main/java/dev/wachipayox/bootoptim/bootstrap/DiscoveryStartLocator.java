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
import net.neoforged.fml.loading.FMLPaths;
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
    private static final String WRAPPER_MARKER =
            "dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class";

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        DiscoveryProfiler.beginRoot();

        Path gameDirectory = FMLPaths.GAMEDIR.get();
        LookupStats lookupStats = new LookupStats();
        long lookupStartedNanos = System.nanoTime();
        Path wrapper = locateWrapper(gameDirectory, lookupStats).orElse(null);
        DiscoveryDetailProfiler.wrapperLookup(
                lookupStats.codeSourceScheme,
                lookupStats.mode,
                lookupStats.candidateJars,
                lookupStats.jarOpens,
                lookupStartedNanos,
                wrapper != null);
        if (wrapper == null || !Files.isRegularFile(wrapper)) {
            // Development runs load the ordinary mod directly and do not necessarily execute from a packaged wrapper.
            return;
        }

        try {
            Path nestedMod = extractNestedMod(gameDirectory, wrapper);
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

    private static Optional<Path> locateWrapper(Path gameDirectory, LookupStats stats) {
        try {
            CodeSource source = DiscoveryStartLocator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                stats.codeSourceScheme = uri.getScheme();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path candidate = Path.of(uri).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate)) {
                        stats.candidateJars++;
                        if (isBootOptimWrapper(candidate, stats)) {
                            stats.mode = "code_source_file";
                            return Optional.of(candidate);
                        }
                    }
                    stats.mode = "code_source_file_miss_then_mods_scan";
                } else {
                    stats.mode = "non_file_code_source_then_mods_scan";
                }
            } else {
                stats.mode = "missing_code_source_then_mods_scan";
            }
        } catch (Exception ignored) {
            // SecureJarHandler may provide a non-file code source. Fall back to the physical mods directory below.
            stats.mode = "code_source_error_then_mods_scan";
        }

        Path modsDirectory = gameDirectory.resolve("mods");
        if (!Files.isDirectory(modsDirectory)) {
            return Optional.empty();
        }

        try (var files = Files.list(modsDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .peek(path -> stats.candidateJars++)
                    .filter(path -> isBootOptimWrapper(path, stats))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Identifies the early-service wrapper without inspecting its JarJar payload. This deliberately avoids treating
     * arbitrary mods with nested dependencies as BootOptim wrappers; validation of BootOptim's own nested payload is
     * left to {@link #extractNestedMod(Path, Path)} once the correct wrapper has been selected.
     */
    static boolean isBootOptimWrapper(Path candidate) {
        return isBootOptimWrapper(candidate, null);
    }

    private static boolean isBootOptimWrapper(Path candidate, LookupStats stats) {
        if (stats != null) {
            stats.jarOpens++;
        }
        try (var zip = new ZipFile(candidate.toFile())) {
            return zip.getEntry(WRAPPER_MARKER) != null;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Path extractNestedMod(Path gameDirectory, Path wrapper) throws IOException {
        long startedNanos = System.nanoTime();
        String outcome = "error";
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
                outcome = "cache_hit";
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
            outcome = "copied";
            return target;
        } finally {
            DiscoveryDetailProfiler.nestedExtraction(outcome, startedNanos);
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

    private static final class LookupStats {
        private String codeSourceScheme = "unknown";
        private String mode = "mods_scan";
        private int candidateJars;
        private int jarOpens;
    }
}
