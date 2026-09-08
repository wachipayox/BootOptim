import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Bounded JDK ZipFile replay for external FilePackResources model entries.
 *
 * This deliberately models vanilla's shared-open-ZipFile shape: one ZipFile per pack,
 * central-directory enumeration, then getEntry/getInputStream reads. It does not model
 * Minecraft callbacks or claim TTMM. The byte cache is a simulation only, used to
 * establish whether exact repeated entry reads have a plausible savings ceiling.
 */
public final class FilePackEntryReadReplay {
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final long CACHE_LIMIT = 16L * 1024L * 1024L;

    record Request(String role, String logicalId, String pack, Path archive, String entry,
                   int priority, long crc, long size, long compressedSize, String expectedSha) {}

    record ArchiveFingerprint(Path path, long size, long mtimeMillis, Object fileKey) {
        static ArchiveFingerprint capture(Path path) throws IOException {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            return new ArchiveFingerprint(path.toAbsolutePath().normalize(), a.size(),
                    a.lastModifiedTime().toMillis(), a.fileKey());
        }
    }

    record EntryFingerprint(String name, long crc, long size, long compressedSize, int method) {}
    record CacheKey(ArchiveFingerprint archive, EntryFingerprint entry) {}

    static final class BoundedByteCache {
        private final LinkedHashMap<CacheKey, byte[]> map = new LinkedHashMap<>(32, 0.75f, true);
        private long bytes;
        long hits;
        long misses;
        long rejectedOversize;

        synchronized byte[] get(CacheKey key) {
            byte[] value = map.get(key);
            if (value != null) hits++; else misses++;
            return value;
        }

        synchronized void put(CacheKey key, byte[] value) {
            if (value.length > CACHE_LIMIT) {
                rejectedOversize++;
                return;
            }
            byte[] old = map.put(key, value);
            if (old != null) bytes -= old.length;
            bytes += value.length;
            while (bytes > CACHE_LIMIT && !map.isEmpty()) {
                var it = map.entrySet().iterator();
                var eldest = it.next();
                bytes -= eldest.getValue().length;
                it.remove();
            }
        }

        synchronized long retainedBytes() { return bytes; }
        synchronized int retainedEntries() { return map.size(); }
    }

    record Phase(String name, long wallNanos, long cpuNanos, long bytesRead, long entries,
                 long centralEntries, long cacheHits, long cacheMisses, long retainedBytes) {
        String line() {
            return String.format(Locale.ROOT,
                    "FILEPACK_REPLAY phase=%s wall_ms=%.3f cpu_ms=%.3f bytes_read=%d entries=%d central_entries=%d cache_hits=%d cache_misses=%d retained_bytes=%d",
                    name, wallNanos / 1_000_000.0, cpuNanos / 1_000_000.0, bytesRead,
                    entries, centralEntries, cacheHits, cacheMisses, retainedBytes);
        }
    }

    private static long cpuNow() {
        return THREAD_MX.isCurrentThreadCpuTimeSupported() ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
    }

    private static List<Request> readRequests(Path tsv) throws IOException {
        List<Request> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(tsv, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            if (line == null || !line.startsWith("role\t")) throw new IOException("bad request header");
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length != 10) throw new IOException("bad request row: " + line);
                out.add(new Request(p[0], p[1], p[2], Path.of(p[3]), p[4],
                        Integer.parseInt(p[5]), Long.parseLong(p[6]), Long.parseLong(p[7]),
                        Long.parseLong(p[8]), p[9]));
            }
        }
        return out;
    }

    private static byte[] readAll(ZipFile zf, ZipEntry ze) throws IOException {
        try (InputStream in = zf.getInputStream(ze)) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void verifyRequest(Request req, ZipEntry ze, byte[] bytes) throws Exception {
        if (ze == null) throw new IOException("missing entry " + req.entry() + " in " + req.archive());
        if (req.crc() != ze.getCrc() || req.size() != ze.getSize() || req.compressedSize() != ze.getCompressedSize()) {
            throw new IOException("entry fingerprint mismatch for " + req.logicalId());
        }
        if (!req.expectedSha().isEmpty() && !req.expectedSha().equals(sha256(bytes))) {
            throw new IOException("PR #183 byte SHA mismatch for " + req.logicalId());
        }
    }

    private static Map<Path, List<Request>> groupWinners(List<Request> requests) {
        Map<Path, List<Request>> out = new LinkedHashMap<>();
        requests.stream().filter(r -> r.role().equals("winner"))
                .sorted(Comparator.comparing((Request r) -> r.archive().toString()).thenComparing(Request::logicalId))
                .forEach(r -> out.computeIfAbsent(r.archive(), ignored -> new ArrayList<>()).add(r));
        return out;
    }

    private static Phase stockOnePass(Map<Path, List<Request>> groups) throws Exception {
        long wall0 = System.nanoTime(), cpu0 = cpuNow();
        long bytes = 0, entries = 0, central = 0;
        for (var group : groups.entrySet()) {
            try (ZipFile zf = new ZipFile(group.getKey().toFile())) {
                var en = zf.entries();
                while (en.hasMoreElements()) { en.nextElement(); central++; }
                for (Request req : group.getValue()) {
                    ZipEntry ze = zf.getEntry(req.entry());
                    byte[] data = readAll(zf, ze);
                    verifyRequest(req, ze, data);
                    bytes += data.length;
                    entries++;
                }
            }
        }
        long cpu1 = cpuNow(), wall1 = System.nanoTime();
        return new Phase("stock_one_pass", wall1-wall0, cpu0 < 0 ? -1 : cpu1-cpu0,
                bytes, entries, central, 0, 0, 0);
    }

    private static List<Phase> cacheTwoPasses(Map<Path, List<Request>> groups) throws Exception {
        BoundedByteCache cache = new BoundedByteCache();
        List<Phase> phases = new ArrayList<>();
        Map<Path, ZipFile> open = new LinkedHashMap<>();
        Map<Path, ArchiveFingerprint> fingerprints = new LinkedHashMap<>();
        try {
            long prepCentral = 0;
            for (Path path : groups.keySet()) {
                ZipFile zf = new ZipFile(path.toFile());
                open.put(path, zf);
                fingerprints.put(path, ArchiveFingerprint.capture(path));
                var en = zf.entries();
                while (en.hasMoreElements()) { en.nextElement(); prepCentral++; }
            }
            for (int pass = 1; pass <= 2; pass++) {
                long wall0 = System.nanoTime(), cpu0 = cpuNow();
                long bytes = 0, entries = 0;
                long hitsBefore = cache.hits, missesBefore = cache.misses;
                for (var group : groups.entrySet()) {
                    ZipFile zf = open.get(group.getKey());
                    ArchiveFingerprint af = fingerprints.get(group.getKey());
                    for (Request req : group.getValue()) {
                        ZipEntry ze = zf.getEntry(req.entry());
                        if (ze == null) throw new IOException("missing entry " + req.entry());
                        EntryFingerprint ef = new EntryFingerprint(ze.getName(), ze.getCrc(), ze.getSize(), ze.getCompressedSize(), ze.getMethod());
                        CacheKey key = new CacheKey(af, ef);
                        byte[] data = cache.get(key);
                        if (data == null) {
                            data = readAll(zf, ze);
                            cache.put(key, data);
                            bytes += data.length;
                        }
                        verifyRequest(req, ze, data);
                        entries++;
                    }
                }
                long cpu1 = cpuNow(), wall1 = System.nanoTime();
                phases.add(new Phase(pass == 1 ? "cache_first_pass" : "cache_repeat_upper_bound",
                        wall1-wall0, cpu0 < 0 ? -1 : cpu1-cpu0, bytes, entries,
                        pass == 1 ? prepCentral : 0,
                        cache.hits-hitsBefore, cache.misses-missesBefore, cache.retainedBytes()));
            }
        } finally {
            for (ZipFile zf : open.values()) try { zf.close(); } catch (IOException ignored) {}
        }
        return phases;
    }

    private static void verifyShadowOrdering(List<Request> requests) throws Exception {
        Map<String, List<Request>> byId = new LinkedHashMap<>();
        for (Request r : requests) byId.computeIfAbsent(r.logicalId(), ignored -> new ArrayList<>()).add(r);
        int shadowed = 0;
        for (var e : byId.entrySet()) {
            List<Request> rows = e.getValue();
            Request winner = rows.stream().filter(r -> r.role().equals("winner")).findFirst().orElseThrow();
            for (Request r : rows) {
                if (r.role().equals("shadowed")) {
                    shadowed++;
                    if (r.priority() >= winner.priority()) throw new IOException("shadow priority violates later-wins for " + e.getKey());
                    try (ZipFile zf = new ZipFile(r.archive().toFile())) {
                        ZipEntry ze = zf.getEntry(r.entry());
                        if (ze == null || ze.getCrc() != r.crc() || ze.getSize() != r.size()) {
                            throw new IOException("shadow entry fingerprint mismatch for " + e.getKey());
                        }
                    }
                }
            }
        }
        System.out.println("FILEPACK_REPLAY semantic=shadow_order status=identical checked=" + shadowed);
    }

    private static void invalidationAndBadZipProbe(Map<Path, List<Request>> groups) throws Exception {
        var first = groups.entrySet().stream().findFirst().orElseThrow();
        Path source = first.getKey();
        Request req = first.getValue().get(0);
        Path tempDir = Files.createTempDirectory("bootoptim-filepack-replay-");
        Path copy = tempDir.resolve(source.getFileName().toString());
        Files.copy(source, copy);
        ArchiveFingerprint before = ArchiveFingerprint.capture(copy);
        try (ZipFile zf = new ZipFile(copy.toFile())) {
            ZipEntry ze = Objects.requireNonNull(zf.getEntry(req.entry()));
            byte[] bytes = readAll(zf, ze);
            if (bytes.length != req.size()) throw new IOException("copy read mismatch");
        }
        Files.setLastModifiedTime(copy, FileTime.fromMillis(before.mtimeMillis() + 5000));
        ArchiveFingerprint after = ArchiveFingerprint.capture(copy);
        if (before.equals(after)) throw new IOException("archive fingerprint failed to invalidate on mtime change");
        System.out.println("FILEPACK_REPLAY semantic=invalidation status=pass reason=archive_fingerprint_changed");

        Path corrupt = tempDir.resolve("corrupt.zip");
        byte[] prefix = Files.readAllBytes(copy);
        Files.write(corrupt, java.util.Arrays.copyOf(prefix, Math.min(prefix.length, 64)));
        boolean failed = false;
        try (ZipFile ignored = new ZipFile(corrupt.toFile())) {
            // unexpected
        } catch (ZipException | java.io.EOFException expected) {
            failed = true;
        } catch (IOException expected) {
            failed = true;
        }
        if (!failed) throw new IOException("corrupt ZIP unexpectedly opened");
        System.out.println("FILEPACK_REPLAY semantic=bad_zip status=fail_open_no_cache_return");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: FilePackEntryReadReplay requests.tsv");
        List<Request> requests = readRequests(Path.of(args[0]));
        Map<Path, List<Request>> groups = groupWinners(requests);
        if (groups.isEmpty()) throw new IOException("no external ZIP winner requests");
        verifyShadowOrdering(requests);
        Phase stock = stockOnePass(groups);
        System.out.println(stock.line());
        for (Phase p : cacheTwoPasses(groups)) System.out.println(p.line());
        invalidationAndBadZipProbe(groups);
        System.out.println("FILEPACK_REPLAY conclusion_input winner_entries=" +
                groups.values().stream().mapToInt(List::size).sum() + " archives=" + groups.size() +
                " cache_limit_bytes=" + CACHE_LIMIT + " streams_retained=0");
    }
}
