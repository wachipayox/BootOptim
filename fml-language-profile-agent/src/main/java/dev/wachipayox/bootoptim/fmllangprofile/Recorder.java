package dev.wachipayox.bootoptim.fmllangprofile;

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

/** Diagnostic-only JDK bridge: retains primitives and strings only. */
public final class Recorder {
    private static final String EXPECTED_FML = System.getProperty("boot_optim.fmlLanguageProfile.expectedFmlVersion", "4.0.43");
    private static final Path OUTPUT = Path.of(System.getProperty("boot_optim.fmlLanguageProfile.output", "bootoptim-fml-language-profile.jsonl"));
    private static final long ORIGIN = System.nanoTime();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean HEADER = new AtomicBoolean();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static volatile boolean accepted;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-fml-language-profile-flush"));
    }

    public static long[] begin(Class<?> owner) {
        ensure(owner);
        if (!accepted) return null;
        return new long[] {System.nanoTime(), cpu(), Thread.currentThread().threadId()};
    }

    private static void ensure(Class<?> owner) {
        if (HEADER.get()) return;
        String module = moduleName(owner);
        String version = moduleVersion(owner);
        boolean ok = "fml_loader".equals(module) && EXPECTED_FML.equals(version);
        if (HEADER.compareAndSet(false, true)) {
            accepted = ok;
            long now = System.nanoTime() - ORIGIN;
            EVENTS.add(new Event(SEQ.incrementAndGet(), "profile_header", ownerName(owner), now, now, -1L,
                    Thread.currentThread().getName(), Thread.currentThread().threadId(),
                    "module=" + module + ";version=" + version + ";expected=" + EXPECTED_FML + ";accepted=" + ok));
        }
    }

    public static void end(String kind, Class<?> owner, long[] state, Throwable thrown) {
        if (!accepted || state == null) return;
        long end = System.nanoTime();
        long endCpu = cpu();
        long cpuDelta = state[1] >= 0 && endCpu >= state[1] ? endCpu - state[1] : -1L;
        EVENTS.add(new Event(SEQ.incrementAndGet(), kind, ownerName(owner), state[0] - ORIGIN, end - ORIGIN, cpuDelta,
                Thread.currentThread().getName(), state[2], thrown == null ? null : "throw=" + thrown.getClass().getName()));
    }

    private static long cpu() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled() ? THREADS.getCurrentThreadCpuTime() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String ownerName(Class<?> owner) {
        return owner == null ? null : owner.getName();
    }

    private static String moduleName(Class<?> owner) {
        try { return owner == null || owner.getModule() == null ? null : owner.getModule().getName(); }
        catch (Throwable ignored) { return null; }
    }

    private static String moduleVersion(Class<?> owner) {
        try {
            Module m = owner == null ? null : owner.getModule();
            return m == null || m.getDescriptor() == null ? null : m.getDescriptor().rawVersion().orElse(null);
        } catch (Throwable ignored) { return null; }
    }

    public static void flush() {
        if (!FLUSHED.compareAndSet(false, true)) return;
        ArrayList<Event> snapshot = new ArrayList<>(EVENTS);
        snapshot.sort(Comparator.comparingLong((Event e) -> e.startNs).thenComparingLong(e -> e.seq));
        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Event e : snapshot) { w.write(e.json()); w.newLine(); }
            }
        } catch (Throwable t) {
            System.err.println("BOOTOPTIM_FML_LANGUAGE_PROFILE_FLUSH_FAILED " + t);
        }
    }

    static final class Event {
        final long seq, startNs, endNs, cpuNs, tid;
        final String kind, owner, thread, detail;
        Event(long seq, String kind, String owner, long startNs, long endNs, long cpuNs, String thread, long tid, String detail) {
            this.seq=seq; this.kind=kind; this.owner=owner; this.startNs=startNs; this.endNs=endNs; this.cpuNs=cpuNs; this.thread=thread; this.tid=tid; this.detail=detail;
        }
        String json() {
            return "{\"seq\":"+seq+",\"kind\":"+q(kind)+",\"owner\":"+q(owner)+",\"start_ns\":"+startNs+",\"end_ns\":"+endNs+",\"duration_ns\":"+Math.max(0,endNs-startNs)+",\"cpu_ns\":"+cpuNs+",\"thread\":"+q(thread)+",\"tid\":"+tid+",\"detail\":"+q(detail)+"}";
        }
        static String q(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }
}
