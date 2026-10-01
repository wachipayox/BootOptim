package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.mixin.client.PathPackResourcesRootAccessor;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * Default-off, exact-pack experiment: materialize Decocraft's tiny model JSON
 * corpus in archive order once, then let stock ModelManager parse fresh readers.
 */
public final class DecocraftModelArchiveBatch {
    @FunctionalInterface
    public interface StockOpen {
        BufferedReader open() throws IOException;
    }

    private record Fingerprint(Path path, Object fileKey, long size, long modifiedMillis) {}
    private record Snapshot(Fingerprint fingerprint, Map<String, byte[]> entries) {}
    private record Target(Path physicalPath, Path secureRoot) {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.experimentDecocraftModelArchiveBatch");
    private static final boolean VERIFY = Boolean.getBoolean("boot_optim.experimentDecocraftModelArchiveBatchVerify");
    private static final long EXPECTED_ARCHIVE_BYTES = 93_170_262L;
    private static final int EXPECTED_ENTRIES = 10_809;
    private static final int EXPECTED_JSON_BYTES = 3_129_313;
    private static final int MAX_ENTRY_BYTES = 4_096;
    private static final String EXPECTED_DIGEST = "7e1ceb06798205056f50669b4c5edde3c95b35eabd7ca5a78416b884754e68d7";
    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot;
    private static volatile Target target;
    private static volatile boolean failed;
    private static final LongAdder hits = new LongAdder();
    private static final LongAdder fallbacks = new LongAdder();
    private static final LongAdder verified = new LongAdder();
    private static int reloads;

    private DecocraftModelArchiveBatch() {}

    public static void beginReload() {
        if (!ENABLED) return;
        synchronized (LOCK) {
            target = findTarget();
            LOGGER.info("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=enabled generation={} archive_present={} verify={}",
                    reloads + 1, target != null, VERIFY);
            reloads++;
            Snapshot current = snapshot;
            if (current != null) {
                try {
                    if (target == null || !current.fingerprint().equals(fingerprint(target.physicalPath()))) {
                        snapshot = null;
                        failed = false;
                        LOGGER.info("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=invalidated reason=archive_changed");
                    }
                } catch (IOException exception) {
                    snapshot = null;
                    failed = true;
                    LOGGER.info("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=disabled reason=archive_unavailable");
                }
            }
        }
    }

    public static void finishReload(boolean success) {
        if (!ENABLED) return;
        LOGGER.info("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=complete generation={} success={} hits={} fallbacks={} verified={} retained_bytes={}",
                reloads, success, hits.sumThenReset(), fallbacks.sumThenReset(), verified.sumThenReset(),
                snapshot == null ? 0 : EXPECTED_JSON_BYTES);
    }

    public static BufferedReader open(Resource resource, ResourceLocation id, StockOpen stock) throws IOException {
        if (!ENABLED || failed || !eligibleId(id) || resource.source() == null
                || !"mod/decocraft".equals(resource.sourcePackId())) {
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
                        LOGGER.info("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=ready entries={} bytes={} digest={}",
                                EXPECTED_ENTRIES, EXPECTED_JSON_BYTES, EXPECTED_DIGEST);
                    } catch (IOException | RuntimeException exception) {
                        failed = true;
                        LOGGER.warn("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=disabled reason=archive_validation_failed", exception);
                    }
                }
            }
        }
        if (current != null) {
            byte[] bytes = current.entries().get("assets/" + id.getNamespace() + "/" + id.getPath());
            if (bytes != null) {
                hits.increment();
                if (VERIFY) {
                    String stockText;
                    try (BufferedReader original = stock.open()) {
                        StringWriter writer = new StringWriter();
                        original.transferTo(writer);
                        stockText = writer.toString();
                    }
                    if (!stockText.equals(new String(bytes, StandardCharsets.UTF_8))) {
                        failed = true;
                        LOGGER.error("BOOTOPTIM_DECOCRAFT_MODEL_BATCH status=disabled reason=resource_byte_mismatch id={}", id);
                    } else {
                        verified.increment();
                    }
                    return new BufferedReader(new StringReader(stockText));
                }
                return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            }
        }
        fallbacks.increment();
        return stock.open();
    }

    private static boolean eligibleId(ResourceLocation id) {
        return "decocraft".equals(id.getNamespace()) && id.getPath().endsWith(".json")
                && (id.getPath().startsWith("models/") || id.getPath().startsWith("blockstates/"));
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
        byte[] number = new byte[4];
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                String name = entry.getName();
                if (!name.startsWith("assets/decocraft/") || !name.endsWith(".json")
                        || !(name.contains("/models/") || name.contains("/blockstates/"))) continue;
                if (entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES
                        || entries.size() >= EXPECTED_ENTRIES) throw new IOException("entry bound exceeded");
                byte[] value;
                try (var input = zip.getInputStream(entry)) {
                    value = input.readNBytes(MAX_ENTRY_BYTES + 1);
                }
                if (value.length != entry.getSize()) throw new IOException("entry length mismatch");
                if (entries.putIfAbsent(name, value) != null) throw new IOException("duplicate entry");
                total += value.length;
                if (total > EXPECTED_JSON_BYTES) throw new IOException("archive byte bound exceeded");
                byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                updateInt(digest, number, nameBytes.length);
                digest.update(nameBytes);
                updateInt(digest, number, value.length);
                digest.update(value);
            }
        }
        if (entries.size() != EXPECTED_ENTRIES || total != EXPECTED_JSON_BYTES
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
