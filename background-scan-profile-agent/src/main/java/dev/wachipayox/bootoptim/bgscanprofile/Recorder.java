package dev.wachipayox.bootoptim.bgscanprofile;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only bootstrap bridge. Stores primitives/strings only and never retains FML/live loader objects. */
public final class Recorder {
    private static final String EXPECTED_FML = System.getProperty(
            "boot_optim.backgroundScanProfile.expectedFmlVersion", "4.0.43");
    private static final Path OUTPUT = Path.of(System.getProperty(
            "boot_optim.backgroundScanProfile.output", "bootoptim-background-scan-profile.jsonl"));
    private static final long ORIGIN_NS = System.nanoTime();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean HEADER = new AtomicBoolean();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static volatile boolean accepted;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-bgscan-profile-flush"));
    }

    public static long[] beginFml(String kind, Class<?> owner) {
        ensureFml(owner);
        return accepted ? beginState() : null;
    }

    public static long[] begin(String kind, Class<?> owner) {
        return accepted ? beginState() : null;
    }

    private static void ensureFml(Class<?> owner) {
        if (HEADER.get()) return;
        String moduleName = moduleName(owner);
        String moduleVersion = moduleVersion(owner);
        boolean ok = "fml_loader".equals(moduleName) && EXPECTED_FML.equals(moduleVersion);
        if (HEADER.compareAndSet(false, true)) {
            long now = System.nanoTime() - ORIGIN_NS;
            EVENTS.add(new Event(next(), "profile_header", ownerName(owner), moduleName, moduleVersion,
                    now, now, -1L, Thread.currentThread().getName(), Thread.currentThread().threadId(),
                    "expected_fml=" + EXPECTED_FML + ";accepted=" + ok));
            accepted = ok;
            if (!ok) {
                EVENTS.add(new Event(next(), "profile_disabled", ownerName(owner), moduleName, moduleVersion,
                        now, now, -1L, Thread.currentThread().getName(), Thread.currentThread().threadId(),
                        "reason=fml_version_mismatch"));
            }
        }
    }

    private static long[] beginState() {
        return new long[] { System.nanoTime(), currentThreadCpu(), Thread.currentThread().threadId() };
    }

    public static void end(String kind, Class<?> owner, long[] state, Throwable thrown) {
        if (!accepted || state == null || state.length < 3) return;
        long end = System.nanoTime();
        long endCpu = currentThreadCpu();
        long cpuDelta = state[1] >= 0L && endCpu >= state[1] ? endCpu - state[1] : -1L;
        String detail = thrown == null ? null : "throw=" + thrown.getClass().getName();
        EVENTS.add(new Event(next(), kind, ownerName(owner), moduleName(owner), moduleVersion(owner),
                state[0] - ORIGIN_NS, end - ORIGIN_NS, cpuDelta,
                Thread.currentThread().getName(), state[2], detail));
    }

    private static long currentThreadCpu() {
        try {
            if (!THREADS.isCurrentThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1L;
            return THREADS.getCurrentThreadCpuTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String ownerName(Class<?> owner) {
        return owner == null ? null : owner.getName();
    }

    private static String moduleName(Class<?> owner) {
        try {
            Module module = owner == null ? null : owner.getModule();
            return module == null ? null : module.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String moduleVersion(Class<?> owner) {
        try {
            Module module = owner == null ? null : owner.getModule();
            return module == null || module.getDescriptor() == null
                    ? null : module.getDescriptor().rawVersion().orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long next() {
        return SEQUENCE.incrementAndGet();
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
            System.err.println("BOOTOPTIM_BGSCAN_PROFILE_FLUSH_FAILED " + error);
        }
    }

    static final class Event {
        final long sequence;
        final String kind;
        final String owner;
        final String moduleName;
        final String moduleVersion;
        final long startNs;
        final long endNs;
        final long cpuNs;
        final String thread;
        final long threadId;
        final String detail;

        Event(long sequence, String kind, String owner, String moduleName, String moduleVersion,
                long startNs, long endNs, long cpuNs, String thread, long threadId, String detail) {
            this.sequence = sequence;
            this.kind = kind;
            this.owner = owner;
            this.moduleName = moduleName;
            this.moduleVersion = moduleVersion;
            this.startNs = startNs;
            this.endNs = endNs;
            this.cpuNs = cpuNs;
            this.thread = thread;
            this.threadId = threadId;
            this.detail = detail;
        }

        String toJson() {
            return "{\"seq\":" + sequence
                    + ",\"kind\":" + quote(kind)
                    + ",\"owner\":" + quote(owner)
                    + ",\"module_name\":" + quote(moduleName)
                    + ",\"module_version\":" + quote(moduleVersion)
                    + ",\"start_ns\":" + startNs
                    + ",\"end_ns\":" + endNs
                    + ",\"duration_ns\":" + Math.max(0L, endNs - startNs)
                    + ",\"cpu_ns\":" + cpuNs
                    + ",\"thread\":" + quote(thread)
                    + ",\"tid\":" + threadId
                    + ",\"detail\":" + quote(detail) + "}";
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
