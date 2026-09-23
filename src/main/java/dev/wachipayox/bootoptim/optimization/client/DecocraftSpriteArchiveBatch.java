package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.mixin.client.PathPackResourcesRootAccessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

/** Default-off exact-pack experiment: archive-order encoded PNG input for stock sprite loading. */
public final class DecocraftSpriteArchiveBatch {
    @FunctionalInterface
    public interface StockOpen {
        InputStream open() throws IOException;
    }

    private record Fingerprint(Path path, Object fileKey, long size, long modifiedMillis) {}
    private record Snapshot(Fingerprint fingerprint, Map<String, byte[]> entries) {}
    private record Target(Path physicalPath, Path secureRoot) {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.experimentDecocraftSpriteArchiveBatch");
    private static final boolean VERIFY = Boolean.getBoolean("boot_optim.experimentDecocraftSpriteArchiveBatchVerify");
    private static final long EXPECTED_ARCHIVE_BYTES = 93_170_262L;
    private static final int EXPECTED_ENTRIES = 5_773;
    private static final int EXPECTED_PNG_BYTES = 20_700_997;
    private static final int EXPECTED_COMPRESSED_BYTES = 20_537_997;
    private static final int MAX_ENTRY_BYTES = 131_072;
    private static final String EXPECTED_DIGEST = "82abe8adc40ee92d8d917c81d7db701db937cc0f26c597d511ea76508c7b4c0d";
    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot;
    private static volatile Target target;
    private static volatile boolean failed;
    private static final LongAdder hits = new LongAdder();
    private static final LongAdder fallbacks = new LongAdder();
    private static final LongAdder verified = new LongAdder();
    private static int reloads;

    private DecocraftSpriteArchiveBatch() {}

    public static void beginReload() {
        if (!ENABLED) return;
        synchronized (LOCK) {
            target = findTarget();
            reloads++;
            LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=enabled generation={} archive_present={} verify={}",
                    reloads, target != null, VERIFY);
            Snapshot current = snapshot;
            if (current != null) {
                try {
                    if (target == null || !current.fingerprint().equals(fingerprint(target.physicalPath()))) {
                        snapshot = null;
                        failed = false;
                        LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=invalidated reason=archive_changed");
                    }
                } catch (IOException exception) {
                    snapshot = null;
                    failed = true;
                    LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=disabled reason=archive_unavailable");
                }
            }
        }
    }

    public static void finishReload(boolean success) {
        if (!ENABLED) return;
        LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=complete generation={} success={} hits={} fallbacks={} verified={} retained_bytes={}",
                reloads, success, hits.sumThenReset(), fallbacks.sumThenReset(), verified.sumThenReset(),
                snapshot == null ? 0 : EXPECTED_PNG_BYTES);
    }

    public static InputStream open(Resource resource, ResourceLocation spriteId, StockOpen stock) throws IOException {
        if (!ENABLED || failed || resource.source() == null
                || !"mod/decocraft".equals(resource.sourcePackId())
                || !"decocraft".equals(spriteId.getNamespace())) {
            return stock.open();
        }
        Target currentTarget = target;
        if (currentTarget == null || !sourceRootMatches(resource, currentTarget.secureRoot())) {
            fallbacks.increment();
            return stock.open();
        }

        Snapshot current = snapshot;
        if (current == null) {
            synchronized (LOCK) {
                current = snapshot;
                if (current == null && !failed) {
                    try {
                        current = load(currentTarget.physicalPath());
                        snapshot = current;
                        LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=ready entries={} bytes={} digest={}",
                                EXPECTED_ENTRIES, EXPECTED_PNG_BYTES, EXPECTED_DIGEST);
                    } catch (IOException | RuntimeException exception) {
                        failed = true;
                        LOGGER.warn("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=disabled reason=archive_validation_failed", exception);
                    }
                }
            }
        }
        if (current != null) {
            String name = "assets/decocraft/textures/" + spriteId.getPath() + ".png";
            byte[] bytes = current.entries().get(name);
            if (bytes != null) {
                hits.increment();
                if (VERIFY) {
                    byte[] stockBytes;
                    try (InputStream original = stock.open()) {
                        stockBytes = original.readAllBytes();
                    }
                    if (!java.util.Arrays.equals(bytes, stockBytes)) {
                        failed = true;
                        LOGGER.error("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=disabled reason=resource_byte_mismatch id={}", spriteId);
                    } else {
                        verified.increment();
                    }
                    return new ByteArrayInputStream(stockBytes);
                }
                return new ByteArrayInputStream(bytes);
            }
        }
        fallbacks.increment();
        return stock.open();
    }

    private static boolean sourceRootMatches(Resource resource, Path secureRoot) {
        try {
            return resource.source() instanceof PathPackResources pathPack
                    && secureRoot.equals(((PathPackResourcesRootAccessor) pathPack).bootoptim$getRoot());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Target findTarget() {
        try {
            IModFileInfo info = ModList.get().getModFileById("decocraft");
            if (info == null) return null;
            Path physical = info.getFile().getFilePath();
            if (!Files.isRegularFile(physical)) return null;
            return new Target(physical, info.getFile().getSecureJar().getRootPath());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Fingerprint fingerprint(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        return new Fingerprint(path.toAbsolutePath().normalize(), attrs.fileKey(), attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    private static Snapshot load(Path path) throws IOException {
        Fingerprint before = fingerprint(path);
        if (before.size() != EXPECTED_ARCHIVE_BYTES) throw new IOException("unexpected archive size");
        Map<String, byte[]> entries = new HashMap<>(EXPECTED_ENTRIES * 2);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        int total = 0;
        int compressed = 0;
        byte[] number = new byte[4];
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/decocraft/textures/") || !name.endsWith(".png")) continue;
                if (entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES
                        || entry.getCompressedSize() < 0 || entries.size() >= EXPECTED_ENTRIES) {
                    throw new IOException("entry bound exceeded");
                }
                byte[] value;
                try (InputStream input = zip.getInputStream(entry)) {
                    value = input.readNBytes(MAX_ENTRY_BYTES + 1);
                }
                if (value.length != entry.getSize()) throw new IOException("entry length mismatch");
                if (entries.putIfAbsent(name, value) != null) throw new IOException("duplicate entry");
                total += value.length;
                compressed += (int) entry.getCompressedSize();
                if (total > EXPECTED_PNG_BYTES || compressed > EXPECTED_COMPRESSED_BYTES) {
                    throw new IOException("archive byte bound exceeded");
                }
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                updateInt(digest, number, nameBytes.length);
                digest.update(nameBytes);
                updateInt(digest, number, value.length);
                digest.update(value);
            }
        }
        if (entries.size() != EXPECTED_ENTRIES || total != EXPECTED_PNG_BYTES
                || compressed != EXPECTED_COMPRESSED_BYTES
                || !Objects.equals(HexFormat.of().formatHex(digest.digest()), EXPECTED_DIGEST)
                || !before.equals(fingerprint(path))) {
            throw new IOException("archive corpus fingerprint mismatch");
        }
        return new Snapshot(before, Map.copyOf(entries));
    }

    private static void updateInt(MessageDigest digest, byte[] buffer, int value) {
        buffer[0] = (byte) (value >>> 24);
        buffer[1] = (byte) (value >>> 16);
        buffer[2] = (byte) (value >>> 8);
        buffer[3] = (byte) value;
        digest.update(buffer);
    }
}
