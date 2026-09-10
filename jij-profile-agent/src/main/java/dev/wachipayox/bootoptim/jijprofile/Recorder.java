package dev.wachipayox.bootoptim.jijprofile;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only bridge loaded from bootstrap; retains strings/events only, never JarContents/IModFile objects. */
public final class Recorder {
    private static final String EXPECTED_FML = System.getProperty("boot_optim.jijProfile.expectedFmlVersion", "4.0.43");
    private static final Path OUTPUT = Path.of(System.getProperty(
            "boot_optim.jijProfile.output", "bootoptim-jij-profile.jsonl"));
    private static final long ORIGIN_NS = System.nanoTime();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, String> PATH_DIGESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> REQUEST_DIGESTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static volatile boolean active;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-jij-profile-flush"));
    }

    public static long scanBegin(Class<?> owner) {
        String moduleName = null;
        String moduleVersion = null;
        String packageVersion = null;
        try {
            Module module = owner == null ? null : owner.getModule();
            if (module != null) {
                moduleName = module.getName();
                if (module.getDescriptor() != null) moduleVersion = module.getDescriptor().rawVersion().orElse(null);
            }
            Package pkg = owner == null ? null : owner.getPackage();
            packageVersion = pkg == null ? null : pkg.getImplementationVersion();
        } catch (Throwable ignored) {
        }
        boolean accepted = "fml_loader".equals(moduleName) && EXPECTED_FML.equals(moduleVersion);
        long now = System.nanoTime() - ORIGIN_NS;
        record(new Event(next(), "profile_header", now, now, null, null, null, -1L, null, null,
                "expected_fml=" + EXPECTED_FML + ";module_name=" + moduleName + ";module_fml=" + moduleVersion
                        + ";package_fml=" + packageVersion + ";target=JarInJarDependencyLocator"));
        if (!accepted) {
            record(new Event(next(), "profile_disabled", now, now, null, null, null, -1L, null, null,
                    "reason=fml_version_mismatch"));
            active = false;
            return 0L;
        }
        active = true;
        return System.nanoTime();
    }

    public static void scanEnd(long start, Throwable thrown) {
        if (!active || start == 0L) return;
        long end = System.nanoTime();
        record(interval("scan", start, end, null, null, null, -1L, null, null, throwable(thrown)));
        active = false;
    }

    public static long intervalBegin() {
        return active ? System.nanoTime() : 0L;
    }

    public static void loadEnd(Object parent, String relativePath, Object child, long start, Throwable thrown) {
        if (!active || start == 0L) return;
        long end = System.nanoTime();
        String parentPath = sourcePath(parent);
        String checksum = REQUEST_DIGESTS.get(requestKey(parentPath, relativePath));
        if (checksum != null && child != null) rememberDigest(child, checksum);
        record(interval("load", start, end, relativePath, parentPath, checksum, -1L, null, child != null, throwable(thrown)));
    }

    public static void extractEnd(
            Object parent, String relativePath, Path destination, String checksum, long start, Throwable thrown) {
        if (!active || start == 0L) return;
        long end = System.nanoTime();
        String parentPath = sourcePath(parent);
        long bytes = -1L;
        Boolean preexisting = null;
        if (thrown == null && checksum != null && destination != null) {
            try {
                bytes = Files.size(destination);
            } catch (Throwable ignored) {
            }
            try {
                String filename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                Path finalPath = destination.getParent().resolve(checksum).resolve(filename);
                // Mirrors stock's immediately-following isRegularFile check. This adds one profile-only stat call.
                preexisting = Files.isRegularFile(finalPath);
            } catch (Throwable ignored) {
            }
            REQUEST_DIGESTS.put(requestKey(parentPath, relativePath), checksum);
        }
        record(interval("extract_sha256", start, end, relativePath, parentPath, checksum, bytes, preexisting, null,
                throwable(thrown)));
    }

    private static Event interval(String kind, long startAbsolute, long endAbsolute, String relativePath,
            String parentPath, String childDigest, long bytes, Boolean preexisting, Boolean resultPresent, String detail) {
        return new Event(next(), kind, startAbsolute - ORIGIN_NS, endAbsolute - ORIGIN_NS, relativePath, parentPath,
                childDigest, bytes, preexisting, resultPresent, detail);
    }

    private static long next() {
        return SEQUENCE.incrementAndGet();
    }

    private static String throwable(Throwable thrown) {
        return thrown == null ? null : "throw=" + thrown.getClass().getName();
    }

    private static String requestKey(String parentPath, String relativePath) {
        return String.valueOf(parentPath) + "\u0000" + String.valueOf(relativePath);
    }

    private static void rememberDigest(Object file, String digest) {
        for (String path : sourcePaths(file)) {
            if (path != null) PATH_DIGESTS.put(path, digest);
        }
    }

    private static String sourcePath(Object file) {
        String[] paths = sourcePaths(file);
        return paths.length == 0 ? null : paths[0];
    }

    private static String[] sourcePaths(Object file) {
        if (file == null) return new String[0];
        Set<String> paths = new LinkedHashSet<>();
        try {
            Method getContents = file.getClass().getMethod("getContents");
            Object contents = getContents.invoke(file);
            if (contents != null) {
                Method getPrimaryPath = contents.getClass().getMethod("getPrimaryPath");
                Object primary = getPrimaryPath.invoke(contents);
                if (primary instanceof Path path) paths.add(pathKey(path));
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getFilePath = file.getClass().getMethod("getFilePath");
            Object value = getFilePath.invoke(file);
            if (value instanceof Path path) paths.add(pathKey(path));
        } catch (Throwable ignored) {
        }
        return paths.toArray(String[]::new);
    }

    private static String pathKey(Path path) {
        try {
            String scheme = path.getFileSystem().provider().getScheme();
            if ("file".equalsIgnoreCase(scheme)) return path.toAbsolutePath().normalize().toString();
            try {
                return scheme + ":" + path.toUri();
            } catch (Throwable ignored) {
                return scheme + ":" + path;
            }
        } catch (Throwable ignored) {
            return path.toString();
        }
    }

    private static void record(Event event) {
        EVENTS.add(event);
    }

    public static void flush() {
        if (!FLUSHED.compareAndSet(false, true)) return;
        ArrayList<Event> snapshot = new ArrayList<>(EVENTS);
        snapshot.sort(Comparator.comparingLong((Event e) -> e.startNs).thenComparingLong(e -> e.sequence));
        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Event event : snapshot) {
                    writer.write(event.toJson());
                    writer.newLine();
                }
            }
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_JIJ_PROFILE_FLUSH_FAILED " + error);
        }
    }

    static final class Event {
        final long sequence;
        final String kind;
        final long startNs;
        final long endNs;
        final String relativePath;
        final String parentPath;
        final String childDigest;
        final long bytes;
        final Boolean preexisting;
        final Boolean resultPresent;
        final String detail;
        final String thread;
        final long threadId;

        Event(long sequence, String kind, long startNs, long endNs, String relativePath, String parentPath,
                String childDigest, long bytes, Boolean preexisting, Boolean resultPresent, String detail) {
            Thread current = Thread.currentThread();
            this.sequence = sequence;
            this.kind = kind;
            this.startNs = startNs;
            this.endNs = endNs;
            this.relativePath = relativePath;
            this.parentPath = parentPath;
            this.childDigest = childDigest;
            this.bytes = bytes;
            this.preexisting = preexisting;
            this.resultPresent = resultPresent;
            this.detail = detail;
            this.thread = current.getName();
            this.threadId = current.threadId();
        }

        String toJson() {
            String parentDigest = parentPath == null ? null : PATH_DIGESTS.get(parentPath);
            return "{\"seq\":" + sequence
                    + ",\"kind\":" + quote(kind)
                    + ",\"start_ns\":" + startNs
                    + ",\"end_ns\":" + endNs
                    + ",\"duration_ns\":" + Math.max(0L, endNs - startNs)
                    + ",\"relative_path\":" + quote(relativePath)
                    + ",\"parent_path\":" + quote(parentPath)
                    + ",\"parent_sha256\":" + quote(parentDigest)
                    + ",\"child_sha256\":" + quote(childDigest)
                    + ",\"bytes\":" + bytes
                    + ",\"output_preexisting\":" + bool(preexisting)
                    + ",\"result_present\":" + bool(resultPresent)
                    + ",\"detail\":" + quote(detail)
                    + ",\"thread\":" + quote(thread)
                    + ",\"tid\":" + threadId + "}";
        }

        private static String bool(Boolean value) {
            return value == null ? "null" : value.toString();
        }

        private static String quote(String value) {
            if (value == null) return "null";
            StringBuilder out = new StringBuilder(value.length() + 8).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
