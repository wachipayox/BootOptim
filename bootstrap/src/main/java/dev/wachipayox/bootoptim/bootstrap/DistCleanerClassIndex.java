package dev.wachipayox.bootoptim.bootstrap;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Persistent, fail-open index used to prove that RuntimeDistCleaner cannot change a class.
 *
 * <p>The index is derived from FML's own completed background scan. That scan records every class
 * plus class/field/method annotations. We only classify a class as clean after the corresponding
 * scan result is authoritative. Classes absent from the index always fall back to stock behavior.
 */
final class DistCleanerClassIndex {
    private static final int MAGIC = 0x424F4449; // BODI
    private static final int VERSION = 2;
    private static final String ONLY_IN = "net/neoforged/api/distmarker/OnlyIn";
    private static final String ONLY_INS = "net/neoforged/api/distmarker/OnlyIns";

    private static final Set<String> KNOWN_CLASSES = ConcurrentHashMap.newKeySet();
    private static final Set<String> DIST_MARKED_CLASSES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean PREPARED = new AtomicBoolean();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong DELEGATED = new AtomicLong();

    private DistCleanerClassIndex() {}

    static boolean canSkip(String internalName) {
        boolean skip = KNOWN_CLASSES.contains(internalName) && !DIST_MARKED_CLASSES.contains(internalName);
        if (skip) {
            SKIPPED.incrementAndGet();
        } else {
            DELEGATED.incrementAndGet();
        }
        return skip;
    }

    static void prepareFromFmlScan() {
        if (!PREPARED.compareAndSet(false, true)) {
            return;
        }

        var loadingModList = FMLLoader.getLoadingModList();
        if (loadingModList == null) {
            System.out.println("BOOTOPTIM_DIST_INDEX status=no_loading_mod_list");
            return;
        }

        List<ModFile> cacheMisses = new ArrayList<>();
        int cacheHits = 0;
        for (var info : loadingModList.getModFiles()) {
            ModFile file = info.getFile();
            if (loadCached(file)) {
                cacheHits++;
            } else {
                cacheMisses.add(file);
            }
        }

        System.out.printf(
                "BOOTOPTIM_DIST_INDEX status=prepared files=%d cache_hits=%d cache_misses=%d known=%d marked=%d%n",
                loadingModList.getModFiles().size(),
                cacheHits,
                cacheMisses.size(),
                KNOWN_CLASSES.size(),
                DIST_MARKED_CLASSES.size());

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("bootoptim-dist-filter-summary").unstarted(() ->
                System.out.printf(
                        "BOOTOPTIM_DIST_FILTER_SUMMARY skipped=%d delegated=%d known=%d marked=%d%n",
                        SKIPPED.get(),
                        DELEGATED.get(),
                        KNOWN_CLASSES.size(),
                        DIST_MARKED_CLASSES.size())));

        if (!cacheMisses.isEmpty()) {
            // FML is already scanning these files. Wait for those existing futures instead of doing a second scan.
            // A platform thread is intentionally non-daemon so a benchmark auto-exit cannot truncate the cache write.
            Thread.ofPlatform().name("bootoptim-dist-index-writer").start(() -> collectMissing(cacheMisses));
        }
    }

    private static void collectMissing(List<ModFile> files) {
        // A single physical artifact can appear as multiple logical ModFiles with different filtered SecureJar views.
        // NeoForge's userdev artifact does this for the Minecraft and NeoForge portions. Persist the union once per
        // physical path so one logical view cannot overwrite the cache produced by another view.
        Map<Path, IndexData> persistentBySource = new HashMap<>();

        for (ModFile file : files) {
            try {
                ModFileScanData scanData = file.getScanResult();
                if (scanData == null) {
                    continue;
                }
                IndexData data = buildIndex(scanData);
                absorb(data);

                Path source = file.getFilePath();
                if (Files.isRegularFile(source)) {
                    Path normalizedSource = source.toAbsolutePath().normalize();
                    persistentBySource.merge(normalizedSource, data, IndexData::merge);
                }

                System.out.printf(
                        "BOOTOPTIM_DIST_INDEX status=collected file=%s known=%d marked=%d%n",
                        file.getFileName(),
                        data.knownClasses().size(),
                        data.distMarkedClasses().size());
            } catch (Throwable throwable) {
                // Filtering is optional. Unknown classes remain on the stock RuntimeDistCleaner path.
                System.out.printf(
                        "BOOTOPTIM_DIST_INDEX status=collect_failed file=%s error=%s%n",
                        file.getFileName(),
                        throwable.getClass().getSimpleName());
            }
        }

        for (var entry : persistentBySource.entrySet()) {
            writeCached(entry.getKey(), entry.getValue());
        }
    }

    private static IndexData buildIndex(ModFileScanData scanData) {
        Set<String> known = new HashSet<>(scanData.getClasses().size());
        for (var classData : scanData.getClasses()) {
            known.add(classData.clazz().getInternalName());
        }

        Set<String> marked = new HashSet<>();
        for (var annotation : scanData.getAnnotations()) {
            String annotationName = annotation.annotationType().getInternalName();
            if (ONLY_IN.equals(annotationName) || ONLY_INS.equals(annotationName)) {
                marked.add(annotation.clazz().getInternalName());
            }
        }
        return new IndexData(known, marked);
    }

    private static void absorb(IndexData data) {
        // Publish marker knowledge before class knowledge. A concurrent class load can therefore only fail open.
        DIST_MARKED_CLASSES.addAll(data.distMarkedClasses());
        KNOWN_CLASSES.addAll(data.knownClasses());
    }

    private static boolean loadCached(ModFile file) {
        Path source = file.getFilePath();
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            Path path = cachePath(source);
            if (!Files.isRegularFile(path)) {
                return false;
            }
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    return false;
                }
                int knownCount = checkedCount(in.readInt());
                Set<String> known = new HashSet<>(knownCount);
                for (int i = 0; i < knownCount; i++) {
                    known.add(in.readUTF());
                }
                int markedCount = checkedCount(in.readInt());
                Set<String> marked = new HashSet<>(markedCount);
                for (int i = 0; i < markedCount; i++) {
                    marked.add(in.readUTF());
                }
                absorb(new IndexData(known, marked));
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeCached(Path source, IndexData data) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path temp = null;
        try {
            Path path = cachePath(source);
            Files.createDirectories(path.getParent());
            temp = path.resolveSibling(path.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(data.knownClasses().size());
                for (String className : data.knownClasses()) {
                    out.writeUTF(className);
                }
                out.writeInt(data.distMarkedClasses().size());
                for (String className : data.distMarkedClasses()) {
                    out.writeUTF(className);
                }
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (Throwable ignored) {
            // Optional persistence only; runtime classification from the completed scan remains valid.
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Path cachePath(Path source) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
        String loaderVersion = String.valueOf(ModFile.class.getPackage().getImplementationVersion());
        String identity = source.toAbsolutePath().normalize() + "\n"
                + attrs.size() + "\n"
                + attrs.lastModifiedTime().toMillis() + "\n"
                + String.valueOf(attrs.fileKey()) + "\n"
                + loaderVersion + "\n"
                + VERSION;
        String key = hex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        return FMLPaths.GAMEDIR.get().resolve(".bootoptim").resolve("dist-cleaner-index-v2").resolve(key + ".bin");
    }

    private static int checkedCount(int count) throws IOException {
        if (count < 0 || count > 1_000_000) {
            throw new IOException("Invalid dist-cleaner index count: " + count);
        }
        return count;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >>> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    private record IndexData(Set<String> knownClasses, Set<String> distMarkedClasses) {
        private static IndexData merge(IndexData left, IndexData right) {
            Set<String> known = new HashSet<>(left.knownClasses());
            known.addAll(right.knownClasses());
            Set<String> marked = new HashSet<>(left.distMarkedClasses());
            marked.addAll(right.distMarkedClasses());
            return new IndexData(known, marked);
        }
    }
}
