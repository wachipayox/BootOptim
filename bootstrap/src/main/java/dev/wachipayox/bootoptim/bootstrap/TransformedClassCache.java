package dev.wachipayox.bootoptim.bootstrap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent cache of final ModLauncher output for actual Minecraft class loads. */
final class TransformedClassCache {
    private static final int MAGIC = 0x424F5443; // BOTC
    private static final int VERSION = 1;
    private static final long MAX_CACHE_BYTES = 768L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;

    private static final Map<String, Entry> WARM = new ConcurrentHashMap<>();
    private static final Map<String, Entry> NEW = new ConcurrentHashMap<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger MISSES = new AtomicInteger();
    private static final AtomicLong HIT_BYTES = new AtomicLong();
    private static final AtomicLong ESTIMATED_SAVED_NANOS = new AtomicLong();

    private static volatile String fingerprint;
    private static volatile Path cacheFile;

    private TransformedClassCache() {
    }

    static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        var gameDir = BootstrapStartupConfig.state().gameDirectory();
        cacheFile = CacheVersioning.cacheRoot().resolve("transformed-class-cache-v1").resolve("classes.bin");
        try {
            fingerprint = buildFingerprint(gameDir);
            load();
            Runtime.getRuntime().addShutdownHook(new Thread(TransformedClassCache::shutdown, "BootOptim transformed cache writer"));
            StartupDiagnostics.cache("transformed_class_cache_path=" + cacheFile);
            StartupDiagnostics.event("TRANSFORM_CACHE", "loaded_entries=" + WARM.size() + " fingerprint=" + fingerprint);
        } catch (Throwable t) {
            fingerprint = null;
            WARM.clear();
            StartupDiagnostics.failure("transformed_class_cache_init", t);
        }
    }

    static byte[] lookup(byte[] raw, String name, String context) {
        if (!eligible(raw, name, context) || fingerprint == null) {
            return null;
        }

        Entry entry = WARM.get(name);
        if (entry == null) {
            MISSES.incrementAndGet();
            return null;
        }

        byte[] rawHash = sha256(raw);
        if (!MessageDigest.isEqual(rawHash, entry.rawHash())) {
            WARM.remove(name, entry);
            MISSES.incrementAndGet();
            return null;
        }

        HITS.incrementAndGet();
        HIT_BYTES.addAndGet(entry.transformed().length);
        ESTIMATED_SAVED_NANOS.addAndGet(entry.transformNanos());
        return entry.transformed();
    }

    static void store(byte[] raw, String name, String context, byte[] transformed, long transformNanos) {
        if (!eligible(raw, name, context) || fingerprint == null || transformed.length == 0 || transformed.length > MAX_CLASS_BYTES) {
            return;
        }
        NEW.putIfAbsent(name, new Entry(sha256(raw), transformed.clone(), Math.max(0L, transformNanos)));
    }

    private static boolean eligible(byte[] raw, String name, String context) {
        // context == null is ModuleClassLoader.readerToClass(): the authoritative class-definition path.
        // Requests made by plugins for frame computation or inspection deliberately bypass this cache.
        return INITIALIZED.get()
                && context == null
                && raw.length > 0
                && name != null
                && name.startsWith("net.minecraft.");
    }

    private static void load() throws IOException {
        if (!Files.isRegularFile(cacheFile)) {
            return;
        }
        long fileSize = Files.size(cacheFile);
        if (fileSize <= 0 || fileSize > MAX_CACHE_BYTES) {
            Files.deleteIfExists(cacheFile);
            return;
        }

        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return;
            }
            String storedFingerprint = in.readUTF();
            if (!storedFingerprint.equals(fingerprint)) {
                StartupDiagnostics.cache("transformed_class_cache_invalidated reason=fingerprint_changed old="
                        + storedFingerprint + " new=" + fingerprint);
                return;
            }
            int count = checkedCount(in.readInt());
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                byte[] rawHash = in.readNBytes(32);
                if (rawHash.length != 32) {
                    throw new IOException("Truncated transformed-cache raw hash");
                }
                int length = in.readInt();
                if (length <= 0 || length > MAX_CLASS_BYTES) {
                    throw new IOException("Invalid transformed class length: " + length);
                }
                byte[] transformed = in.readNBytes(length);
                if (transformed.length != length) {
                    throw new IOException("Truncated transformed class payload");
                }
                long transformNanos = in.readLong();
                WARM.put(name, new Entry(rawHash, transformed, Math.max(0L, transformNanos)));
            }
        } catch (Throwable t) {
            WARM.clear();
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException ignored) {
            }
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException("Unable to load transformed cache", t);
        }
    }

    private static void shutdown() {
        int hits = HITS.get();
        int misses = MISSES.get();
        long estimatedSavedMs = ESTIMATED_SAVED_NANOS.get() / 1_000_000L;
        String summary = "hits=" + hits
                + " misses=" + misses
                + " loaded=" + WARM.size()
                + " recorded=" + NEW.size()
                + " hit_mib=" + (HIT_BYTES.get() / (1024L * 1024L))
                + " estimated_saved_ms=" + estimatedSavedMs;
        System.out.println("BOOTOPTIM_TRANSFORM_CACHE " + summary);
        StartupDiagnostics.event("TRANSFORM_CACHE", summary);

        if (fingerprint == null || NEW.isEmpty()) {
            return;
        }

        try {
            WARM.putAll(NEW);
            write();
        } catch (Throwable t) {
            StartupDiagnostics.failure("transformed_class_cache_write", t);
        }
    }

    private static void write() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        List<Map.Entry<String, Entry>> entries = new ArrayList<>(WARM.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(fingerprint);
                out.writeInt(entries.size());
                for (var mapEntry : entries) {
                    Entry entry = mapEntry.getValue();
                    out.writeUTF(mapEntry.getKey());
                    out.write(entry.rawHash());
                    out.writeInt(entry.transformed().length);
                    out.write(entry.transformed());
                    out.writeLong(entry.transformNanos());
                }
            }
            try {
                Files.move(temp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String buildFingerprint(Path gameDir) throws IOException {
        MessageDigest digest = messageDigest();
        update(digest, "schema=" + VERSION);
        update(digest, "bootoptim=" + BootOptimRuntimeInfo.version());
        update(digest, "java=" + Runtime.version().feature());
        update(digest, "modlauncher=" + String.valueOf(cpw.mods.modlauncher.TransformingClassLoader.class.getPackage().getImplementationVersion()));
        update(digest, "fml=" + String.valueOf(net.neoforged.fml.loading.FMLLoader.class.getPackage().getImplementationVersion()));
        update(digest, "mc=" + System.getProperty("fml.mcVersion", "1.21.1"));
        update(digest, "dist=" + String.valueOf(net.neoforged.fml.loading.FMLEnvironment.dist));
        updateMetadataTree(digest, gameDir, "mods");
        updateContentTree(digest, gameDir, "config");
        updateContentTree(digest, gameDir, "defaultconfigs");
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * JARs are large and immutable in normal packs, so their identity stays cheap during the experiment.
     * A production version can retain persistent content hashes keyed by these metadata fields.
     */
    private static void updateMetadataTree(MessageDigest digest, Path gameDir, String child) throws IOException {
        Path root = gameDir.resolve(child);
        update(digest, child + ":exists=" + Files.exists(root));
        if (!Files.isDirectory(root)) {
            return;
        }
        for (Path file : regularFiles(root)) {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            update(digest, child + "/" + relative(root, file));
            update(digest, Long.toString(attrs.size()));
            update(digest, Long.toString(attrs.lastModifiedTime().toMillis()));
        }
    }

    /**
     * Config files are commonly rewritten by loaders/mods even when their effective contents do not change.
     * Hash their bytes rather than mtimes so harmless rewrites do not destroy a warm transformed cache.
     */
    private static void updateContentTree(MessageDigest digest, Path gameDir, String child) throws IOException {
        Path root = gameDir.resolve(child);
        update(digest, child + ":exists=" + Files.exists(root));
        if (!Files.isDirectory(root)) {
            return;
        }
        for (Path file : regularFiles(root)) {
            update(digest, child + "/" + relative(root, file));
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            update(digest, Long.toString(attrs.size()));
            MessageDigest fileDigest = messageDigest();
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        fileDigest.update(buffer, 0, read);
                    }
                }
            }
            update(digest, HexFormat.of().formatHex(fileDigest.digest()));
        }
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static byte[] sha256(byte[] bytes) {
        return messageDigest().digest(bytes);
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int checkedCount(int value) throws IOException {
        if (value < 0 || value > MAX_ENTRIES) {
            throw new IOException("Invalid transformed-cache entry count: " + value);
        }
        return value;
    }

    private record Entry(byte[] rawHash, byte[] transformed, long transformNanos) {
    }
}
