package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.logging.LogUtils;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.resources.IoSupplier;
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
    private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BootOptim-DecocraftSpritePrepare");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<Integer> STARTING_GENERATION = new ThreadLocal<>();
    private static volatile Snapshot snapshot;
    private static volatile Target target;
    private static volatile boolean failed;
    private static volatile int activeGeneration;
    private static Future<?> preparation;
    private static final LongAdder hits = new LongAdder();
    private static final LongAdder fallbacks = new LongAdder();
    private static final LongAdder pendingFallbacks = new LongAdder();
    private static final LongAdder verified = new LongAdder();
    private static int reloads;

    private DecocraftSpriteArchiveBatch() {}

    public static void beginReload() {
        if (!ENABLED) return;
        synchronized (LOCK) {
            if (preparation != null) preparation.cancel(true);
            snapshot = null;
            failed = false;
            target = findTarget();
            int generation = ++reloads;
            activeGeneration = generation;
            STARTING_GENERATION.set(generation);
            LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=enabled generation={} archive_present={} verify={} mode=async_generation_scoped",
                    generation, target != null, VERIFY);
            if (target != null) {
                Path physicalPath = target.physicalPath();
                preparation = PREPARE.submit(() -> prepare(generation, physicalPath));
            } else {
                preparation = null;
            }
        }
    }

    public static void attachFinish(CompletableFuture<?> future) {
        if (!ENABLED) return;
        Integer generation = STARTING_GENERATION.get();
        STARTING_GENERATION.remove();
        if (generation == null) return;
        if (future == null) finishReload(generation, false);
        else future.whenComplete((ignored, failure) -> finishReload(generation, failure == null));
    }

    private static void finishReload(int generation, boolean success) {
        synchronized (LOCK) {
            if (generation != activeGeneration) return;
            if (preparation != null) preparation.cancel(true);
            preparation = null;
            snapshot = null;
            target = null;
            activeGeneration = 0;
            LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=complete generation={} success={} hits={} fallbacks={} pending_fallbacks={} verified={} retained_bytes=0",
                    generation, success, hits.sumThenReset(), fallbacks.sumThenReset(),
                    pendingFallbacks.sumThenReset(), verified.sumThenReset());
        }
    }

    private static void prepare(int generation, Path physicalPath) {
        long started = System.nanoTime();
        try {
            Snapshot loaded = load(physicalPath);
            synchronized (LOCK) {
                if (generation != activeGeneration || Thread.currentThread().isInterrupted()) return;
                snapshot = loaded;
                LOGGER.info("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=ready generation={} entries={} bytes={} prepare_ms={} digest={}",
                        generation, EXPECTED_ENTRIES, EXPECTED_PNG_BYTES,
                        (System.nanoTime() - started) / 1_000_000, EXPECTED_DIGEST);
            }
        } catch (IOException | RuntimeException exception) {
            synchronized (LOCK) {
                if (generation != activeGeneration || Thread.currentThread().isInterrupted()) return;
                failed = true;
                LOGGER.warn("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=disabled generation={} reason=archive_validation_failed",
                        generation, exception);
            }
        }
    }

    public static IoSupplier<InputStream> wrap(Path path, IoSupplier<InputStream> stock) {
        if (!ENABLED || stock == null || failed) return stock;
        Target currentTarget = target;
        if (currentTarget == null || !path.getFileSystem().equals(currentTarget.secureRoot().getFileSystem())
                || !path.startsWith(currentTarget.secureRoot())) return stock;
        String name = currentTarget.secureRoot().relativize(path).toString().replace('\\', '/');
        if (!name.startsWith("assets/decocraft/textures/") || !name.endsWith(".png")) return stock;
        int generation = activeGeneration;
        return () -> open(name, generation, stock::get);
    }

    private static InputStream open(String name, int generation, StockOpen stock) throws IOException {
        if (generation != activeGeneration || failed) {
            fallbacks.increment();
            return stock.open();
        }
        Snapshot current = snapshot;
        if (current == null) {
            pendingFallbacks.increment();
            fallbacks.increment();
            return stock.open();
        }
        if (current != null) {
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
                        LOGGER.error("BOOTOPTIM_DECOCRAFT_SPRITE_BATCH status=disabled reason=resource_byte_mismatch path={}", name);
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
                if (Thread.currentThread().isInterrupted()) throw new IOException("archive preparation interrupted");
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
