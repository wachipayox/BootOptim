package dev.wachipayox.bootoptim.jijprofile;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only bridge loaded from bootstrap; retains strings/events only, never live loader/filesystem objects. */
public final class Recorder {
    private static final String EXPECTED_FML = System.getProperty("boot_optim.jijProfile.expectedFmlVersion", "4.0.43");
    private static final Path OUTPUT = Path.of(System.getProperty(
            "boot_optim.jijProfile.output", "bootoptim-jij-profile.jsonl"));
    private static final long ORIGIN_NS = System.nanoTime();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong SCAN_SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static final AtomicBoolean VERSION_REJECTED = new AtomicBoolean();
    private static final ThreadLocal<Deque<Long>> SCAN_STACK = ThreadLocal.withInitial(ArrayDeque::new);

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
        } catch (Throwable ignored) {}

        boolean accepted = "fml_loader".equals(moduleName) && EXPECTED_FML.equals(moduleVersion);
        long now = System.nanoTime() - ORIGIN_NS;
        record(new Event(next(), "profile_header", now, now, null, null, null, null, null, null,
                "expected_fml=" + EXPECTED_FML + ";module_name=" + moduleName + ";module_fml=" + moduleVersion
                        + ";package_fml=" + packageVersion + ";target=JarInJarDependencyLocator;implementation=jij_filesystem"));
        if (!accepted) {
            VERSION_REJECTED.set(true);
            record(new Event(next(), "profile_disabled", now, now, null, null, null, null, null, null,
                    "reason=fml_version_mismatch"));
            return 0L;
        }
        if (VERSION_REJECTED.get()) return 0L;

        long scanId = SCAN_SEQUENCE.incrementAndGet();
        SCAN_STACK.get().push(scanId);
        return System.nanoTime();
    }

    public static void scanEnd(long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        long scanId = currentScanId();
        String detail = "caller_stack=" + callerStack();
        String failure = throwable(thrown);
        if (failure != null) detail += ";" + failure;
        record(interval("scan", start, end, scanId, null, null, null, null, null, detail));
        Deque<Long> stack = SCAN_STACK.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) SCAN_STACK.remove();
    }

    public static long intervalBegin() {
        return currentScanId() == 0L ? 0L : System.nanoTime();
    }

    public static long fileSystemBegin(String uri) {
        if (currentScanId() == 0L || uri == null || !uri.startsWith("jij:")) return 0L;
        return System.nanoTime();
    }

    public static void loadEnd(Object parent, String relativePath, Object child, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval("load_jij_filesystem", start, end, currentScanId(), relativePath, sourcePath(parent), sourcePath(child),
                child != null, null, throwable(thrown)));
    }

    public static void descriptorEnd(Object parent, String relativePath, Object stream, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval("descriptor_stream_open", start, end, currentScanId(), relativePath, sourcePath(parent), null,
                stream != null, objectId(stream), throwable(thrown)));
    }

    public static void metadataEnd(Object input, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval("descriptor_parse", start, end, currentScanId(), null, null, null, null, objectId(input), throwable(thrown)));
    }

    public static void readerEnd(Object jarContents, boolean resultPresent, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval("reader_callback", start, end, currentScanId(), null, null, primaryPath(jarContents),
                resultPresent, null, throwable(thrown)));
    }

    public static void fileSystemEnd(String uri, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval("filesystem_open", start, end, currentScanId(), uri, null, null, null, null, throwable(thrown)));
    }

    public static void identifyEnd(Object modFile, String identity, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        String detail = identity == null ? null : "identity=" + identity;
        String failure = throwable(thrown);
        if (failure != null) detail = detail == null ? failure : detail + ";" + failure;
        record(interval("identify_callback", start, end, currentScanId(), null, sourcePath(modFile), null,
                identity != null, null, detail));
    }

    public static void simpleEnd(String kind, long start, Throwable thrown) {
        if (start == 0L) return;
        long end = System.nanoTime();
        record(interval(kind, start, end, currentScanId(), null, null, null, null, null, throwable(thrown)));
    }

    public static void selectorPhaseEnd(String method, long start, Throwable thrown) {
        if (start == 0L) return;
        String kind = switch (method) {
            case "detectAndSelect" -> "selector_total";
            case "detect" -> "selector_detect";
            case "recursivelyDetectContainedJars" -> "selector_recursive_detect";
            default -> "selector_" + method;
        };
        simpleEnd(kind, start, thrown);
    }

    private static Event interval(String kind, long startAbsolute, long endAbsolute, Long scanId, String relativePath,
            String parentPath, String childPath, Boolean resultPresent, Long objectId, String detail) {
        return new Event(next(), kind, startAbsolute - ORIGIN_NS, endAbsolute - ORIGIN_NS,
                scanId, relativePath, parentPath, childPath, resultPresent, objectId, detail);
    }

    private static long currentScanId() {
        try {
            Deque<Long> stack = SCAN_STACK.get();
            return stack.isEmpty() ? 0L : stack.peek();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long next() {
        return SEQUENCE.incrementAndGet();
    }

    private static Long objectId(Object value) {
        return value == null ? null : Integer.toUnsignedLong(System.identityHashCode(value));
    }

    private static String throwable(Throwable thrown) {
        return thrown == null ? null : "throw=" + thrown.getClass().getName();
    }

    private static String callerStack() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            boolean afterLocator = false;
            StringJoiner out = new StringJoiner(" <- ");
            int count = 0;
            for (StackTraceElement frame : stack) {
                if (!afterLocator) {
                    if (frame.getClassName().endsWith("JarInJarDependencyLocator") && frame.getMethodName().equals("scanMods")) {
                        afterLocator = true;
                    }
                    continue;
                }
                out.add(frame.getClassName() + "." + frame.getMethodName());
                if (++count >= 8) break;
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "unavailable";
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
        } catch (Throwable ignored) {}
        try {
            Method getFilePath = file.getClass().getMethod("getFilePath");
            Object value = getFilePath.invoke(file);
            if (value instanceof Path path) paths.add(pathKey(path));
        } catch (Throwable ignored) {}
        return paths.toArray(String[]::new);
    }

    private static String primaryPath(Object jarContents) {
        if (jarContents == null) return null;
        try {
            Method method = jarContents.getClass().getMethod("getPrimaryPath");
            Object value = method.invoke(jarContents);
            return value instanceof Path path ? pathKey(path) : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String pathKey(Path path) {
        try {
            String scheme = path.getFileSystem().provider().getScheme();
            if ("file".equalsIgnoreCase(scheme)) return path.toAbsolutePath().normalize().toString();
            try { return scheme + ":" + path.toUri(); }
            catch (Throwable ignored) { return scheme + ":" + path; }
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
        final Long scanId;
        final String relativePath;
        final String parentPath;
        final String childPath;
        final Boolean resultPresent;
        final Long objectId;
        final String detail;
        final String thread;
        final long threadId;

        Event(long sequence, String kind, long startNs, long endNs, Long scanId, String relativePath, String parentPath,
                String childPath, Boolean resultPresent, Long objectId, String detail) {
            Thread current = Thread.currentThread();
            this.sequence = sequence;
            this.kind = kind;
            this.startNs = startNs;
            this.endNs = endNs;
            this.scanId = scanId;
            this.relativePath = relativePath;
            this.parentPath = parentPath;
            this.childPath = childPath;
            this.resultPresent = resultPresent;
            this.objectId = objectId;
            this.detail = detail;
            this.thread = current.getName();
            this.threadId = current.threadId();
        }

        String toJson() {
            return "{\"seq\":" + sequence
                    + ",\"kind\":" + quote(kind)
                    + ",\"start_ns\":" + startNs
                    + ",\"end_ns\":" + endNs
                    + ",\"duration_ns\":" + Math.max(0L, endNs - startNs)
                    + ",\"scan_id\":" + number(scanId)
                    + ",\"relative_path\":" + quote(relativePath)
                    + ",\"parent_path\":" + quote(parentPath)
                    + ",\"child_path\":" + quote(childPath)
                    + ",\"result_present\":" + bool(resultPresent)
                    + ",\"object_id\":" + number(objectId)
                    + ",\"detail\":" + quote(detail)
                    + ",\"thread\":" + quote(thread)
                    + ",\"tid\":" + threadId + "}";
        }

        private static String bool(Boolean value) {
            return value == null ? "null" : value.toString();
        }

        private static String number(Long value) {
            return value == null ? "null" : Long.toString(value);
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
