package dev.wachipayox.bootoptim.bootstrap;

import com.mojang.logging.LogUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.slf4j.Logger;

final class ScanCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CACHE_VERSION = 1;
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bootoptim.scanCache", "true"));
    private static final boolean PROFILE = Boolean.getBoolean("bootoptim.profileStartup");
    private static final String FML_IMPLEMENTATION = Optional.ofNullable(ModFile.class.getPackage().getImplementationVersion()).orElse("unknown");

    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder WRITES = new LongAdder();
    private static final LongAdder BYPASSES = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final LongAdder READ_NANOS = new LongAdder();
    private static final LongAdder WRITE_NANOS = new LongAdder();

    static {
        if (PROFILE) {
            Runtime.getRuntime().addShutdownHook(new Thread(ScanCache::logStats, "BootOptim scan-cache stats"));
        }
    }

    private ScanCache() {
    }

    static boolean isEligible(CachingModFile modFile) {
        if (!ENABLED || modFile.getDiscoveryAttributes().parent() != null || modFile.getSecureJar().hasSecurityData()) {
            return false;
        }

        Path source = modFile.getFilePath();
        return source.getFileSystem().equals(Path.of(".").getFileSystem()) && Files.isRegularFile(source);
    }

    static void recordBypass() {
        BYPASSES.increment();
    }

    static Optional<ModFileScanData> read(CachingModFile modFile) {
        long start = System.nanoTime();
        try {
            SourceStamp stamp = SourceStamp.read(modFile.getFilePath());
            Path cacheFile = cacheFile(stamp);
            if (!Files.isRegularFile(cacheFile)) {
                MISSES.increment();
                return Optional.empty();
            }

            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
                ModFileScanData data = ScanDataCodec.read(input, CACHE_VERSION, stamp.size(), stamp.modifiedMillis());
                HITS.increment();
                return Optional.of(data);
            } catch (Exception exception) {
                ERRORS.increment();
                MISSES.increment();
                try {
                    Files.deleteIfExists(cacheFile);
                } catch (IOException ignored) {
                }
                LOGGER.debug("Discarding invalid BootOptim scan cache for {}", modFile.getFileName(), exception);
                return Optional.empty();
            }
        } catch (Exception exception) {
            ERRORS.increment();
            MISSES.increment();
            LOGGER.debug("Unable to read BootOptim scan cache for {}", modFile.getFileName(), exception);
            return Optional.empty();
        } finally {
            READ_NANOS.add(System.nanoTime() - start);
        }
    }

    static void write(CachingModFile modFile, ModFileScanData scanData) {
        long start = System.nanoTime();
        Path temporary = null;
        try {
            SourceStamp stamp = SourceStamp.read(modFile.getFilePath());
            Path target = cacheFile(stamp);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "scan-", ".tmp");

            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                ScanDataCodec.write(output, CACHE_VERSION, stamp.size(), stamp.modifiedMillis(), scanData);
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            WRITES.increment();
        } catch (Exception exception) {
            ERRORS.increment();
            LOGGER.debug("Unable to write BootOptim scan cache for {}", modFile.getFileName(), exception);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        } finally {
            WRITE_NANOS.add(System.nanoTime() - start);
        }
    }

    private static Path cacheFile(SourceStamp stamp) throws IOException {
        String identity = stamp.path() + '\n'
                + stamp.size() + '\n'
                + stamp.modifiedMillis() + '\n'
                + FML_IMPLEMENTATION + '\n'
                + Runtime.version().feature() + '\n'
                + CACHE_VERSION;
        return FMLPaths.GAMEDIR.get()
                .resolve(".bootoptim")
                .resolve("scan-cache-v" + CACHE_VERSION)
                .resolve(sha256(identity) + ".bin");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void logStats() {
        LOGGER.info(
                "BOOTOPTIM_SCAN_CACHE hits={} misses={} writes={} bypasses={} errors={} read_ms={} write_ms={}",
                HITS.sum(), MISSES.sum(), WRITES.sum(), BYPASSES.sum(), ERRORS.sum(),
                READ_NANOS.sum() / 1_000_000L, WRITE_NANOS.sum() / 1_000_000L);
    }

    private record SourceStamp(String path, long size, long modifiedMillis) {
        static SourceStamp read(Path path) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
            return new SourceStamp(normalized.toString(), attributes.size(), attributes.lastModifiedTime().toMillis());
        }
    }
}
