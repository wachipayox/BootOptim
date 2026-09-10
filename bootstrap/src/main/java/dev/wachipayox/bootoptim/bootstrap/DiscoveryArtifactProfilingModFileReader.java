package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.JarContents;
import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic-only decorator for the production scan-cache reader.
 *
 * <p>The delegate is the exact production {@link CachingModFileReader}; this class changes no reader decision and
 * adds no cache. In structured profile/development mode it records the real per-artifact reader wall, discovery
 * provenance and parsed mod identity. The enclosed wall includes metadata probing, SecureJar construction,
 * mods.toml/manifest parsing, module metadata, mixin/AT metadata, and any ZipFS work reached by the reader. JFR
 * attribution separates those internals without transforming FML bootstrap classes.</p>
 */
public final class DiscoveryArtifactProfilingModFileReader implements IModFileReader {
    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private final CachingModFileReader delegate = new CachingModFileReader();

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public @Nullable IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        if (!TRACE.isDetailed() || !DiscoveryProfiler.artifactDetailEnabled()) {
            return delegate.read(jar, attributes);
        }

        Path primary = jar.getPrimaryPath();
        String resource = primary == null ? "unknown" : primary.toString();
        long parent = DiscoveryProfiler.currentTaskIdForCurrentThread();
        long predecessor = DiscoveryProfiler.previousArtifactTaskForCurrentThread();
        long[] dependencies = predecessor == 0L ? null : new long[] { predecessor };
        long task = TRACE.beginTask("fml_discovery_artifact_metadata", parent, dependencies, null, resource, -1L);
        DiscoveryProfiler.noteArtifactTaskForCurrentThread(task);

        String detail = "result=exception";
        try {
            IModFile result = delegate.read(jar, attributes);
            String mod = result == null ? null : safeModId(result);
            detail = describe(primary, attributes, result, mod);
            TRACE.record(
                    StructuredBootTrace.EventType.RESOURCE_PARSE,
                    task,
                    parent,
                    dependencies,
                    "fml_discovery_artifact_metadata",
                    -1L,
                    mod,
                    resource,
                    -1L,
                    detail);
            return result;
        } catch (RuntimeException | Error failure) {
            TRACE.record(
                    StructuredBootTrace.EventType.ERROR,
                    task,
                    parent,
                    dependencies,
                    "fml_discovery_artifact_metadata",
                    -1L,
                    null,
                    resource,
                    -1L,
                    "reader_exception=" + failure.getClass().getName());
            throw failure;
        } finally {
            TRACE.endTask(task, "fml_discovery_artifact_metadata", -1L, detail);
        }
    }

    private static String describe(Path primary, ModFileDiscoveryAttributes attributes, @Nullable IModFile result, @Nullable String mod) {
        StringBuilder out = new StringBuilder(192);
        out.append("fml=4.0.43");
        out.append(";neo=21.1.248");
        out.append(";fs=").append(primary == null ? "unknown" : primary.getFileSystem().provider().getScheme());
        out.append(";regular=").append(primary != null && Files.isRegularFile(primary));
        out.append(";size=").append(fileSize(primary));
        out.append(";locator=").append(typeName(attributes.locator()));
        out.append(";dependency_locator=").append(typeName(attributes.dependencyLocator()));
        out.append(";parent=").append(attributes.parent() == null ? "none" : safeFileName(attributes.parent().getFilePath()));
        out.append(";accepted=").append(result != null);
        out.append(";mod=").append(mod == null ? "none" : mod);
        if (result != null) {
            try {
                out.append(";mods=").append(result.getModInfos().size());
            } catch (Throwable ignored) {
                out.append(";mods=-1");
            }
        }
        return out.toString();
    }

    private static long fileSize(@Nullable Path path) {
        if (path == null) return -1L;
        try {
            return Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String typeName(@Nullable Object value) {
        return value == null ? "none" : value.getClass().getName();
    }

    private static String safeModId(IModFile file) {
        try {
            return file.getModInfos().isEmpty() ? file.getFileName() : file.getModInfos().getFirst().getModId();
        } catch (Throwable ignored) {
            return file.getFileName();
        }
    }

    private static String safeFileName(@Nullable Path path) {
        if (path == null) return "unknown";
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}
