package dev.wachipayox.bootoptim.stage2profile;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only bootstrap bridge. Retains only primitive timing state and immutable strings. */
public final class Recorder {
    private static final String EXPECTED_FML = System.getProperty(
            "boot_optim.stage2Profile.expectedFmlVersion", "4.0.43");
    private static final Path OUTPUT = Path.of(System.getProperty(
            "boot_optim.stage2Profile.output", "bootoptim-stage2-profile.jsonl"));
    private static final long ORIGIN_NS = System.nanoTime();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean HEADER = new AtomicBoolean();
    private static final AtomicBoolean FLUSHED = new AtomicBoolean();
    private static final ThreadLocal<ArrayDeque<Long>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile boolean accepted;

    private Recorder() {}

    public static void installShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::flush, "bootoptim-stage2-profile-flush"));
    }

    public static long[] beginFml(String kind, Class<?> owner) {
        ensureFml(owner);
        if (!accepted) return null;
        ArrayDeque<Long> stack = STACK.get();
        long id = next();
        long parent = stack.isEmpty() ? 0L : stack.peek();
        long depth = stack.size();
        stack.push(id);
        return new long[] { id, parent, depth, System.nanoTime(), currentThreadCpu(), Thread.currentThread().threadId() };
    }

    private static void ensureFml(Class<?> owner) {
        if (HEADER.get()) return;
        synchronized (Recorder.class) {
            if (HEADER.get()) return;
            String moduleName = moduleName(owner);
            String moduleVersion = moduleVersion(owner);
            boolean ok = "fml_loader".equals(moduleName) && EXPECTED_FML.equals(moduleVersion);
            long now = System.nanoTime() - ORIGIN_NS;
            EVENTS.add(new Event(next(), 0L, 0, "profile_header", ownerName(owner), moduleName, moduleVersion,
                    now, now, -1L, Thread.currentThread().getName(), Thread.currentThread().threadId(),
                    "expected_fml=" + EXPECTED_FML + ";accepted=" + ok));
            accepted = ok;
            HEADER.set(true);
            if (!ok) {
                EVENTS.add(new Event(next(), 0L, 0, "profile_disabled", ownerName(owner), moduleName, moduleVersion,
                        now, now, -1L, Thread.currentThread().getName(), Thread.currentThread().threadId(),
                        "reason=fml_version_mismatch"));
            }
        }
    }

    public static void end(String kind, Class<?> owner, long[] state, Throwable thrown) {
        if (!accepted || state == null || state.length < 6) return;
        long end = System.nanoTime();
        long endCpu = currentThreadCpu();
        long cpuDelta = state[4] >= 0L && endCpu >= state[4] ? endCpu - state[4] : -1L;
        ArrayDeque<Long> stack = STACK.get();
        if (!stack.isEmpty() && stack.peek() == state[0]) stack.pop();
        else stack.remove(state[0]);
        if (stack.isEmpty()) STACK.remove();
        String detail = thrown == null ? null : "throw=" + thrown.getClass().getName();
        EVENTS.add(new Event(state[0], state[1], (int) state[2], kind, ownerName(owner), moduleName(owner), moduleVersion(owner),
                state[3] - ORIGIN_NS, end - ORIGIN_NS, cpuDelta,
                Thread.currentThread().getName(), state[5], detail));
    }

    private static long currentThreadCpu() {
        try {
            if (!THREADS.isCurrentThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1L;
            return THREADS.getCurrentThreadCpuTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String ownerName(Class<?> owner) { return owner == null ? null : owner.getName(); }
    private static String moduleName(Class<?> owner) {
        try { Module m = owner == null ? null : owner.getModule(); return m == null ? null : m.getName(); }
        catch (Throwable ignored) { return null; }
    }
    private static String moduleVersion(Class<?> owner) {
        try {
            Module m = owner == null ? null : owner.getModule();
            return m == null || m.getDescriptor() == null ? null : m.getDescriptor().rawVersion().orElse(null);
        } catch (Throwable ignored) { return null; }
    }
    private static long next() { return SEQUENCE.incrementAndGet(); }

    public static void flush() {
        if (!FLUSHED.compareAndSet(false, true)) return;
        ArrayList<Event> snapshot = new ArrayList<>(EVENTS);
        snapshot.sort(Comparator.comparingLong((Event e) -> e.startNs).thenComparingLong(e -> e.sequence));
        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Event event : snapshot) { writer.write(event.toJson()); writer.newLine(); }
            }
        } catch (Throwable error) {
            System.err.println("BOOTOPTIM_STAGE2_PROFILE_FLUSH_FAILED " + error);
        }
    }

    static final class Event {
        final long sequence, parent, startNs, endNs, cpuNs, threadId;
        final int depth;
        final String kind, owner, moduleName, moduleVersion, thread, detail;
        Event(long sequence, long parent, int depth, String kind, String owner, String moduleName, String moduleVersion,
                long startNs, long endNs, long cpuNs, String thread, long threadId, String detail) {
            this.sequence=sequence; this.parent=parent; this.depth=depth; this.kind=kind; this.owner=owner;
            this.moduleName=moduleName; this.moduleVersion=moduleVersion; this.startNs=startNs; this.endNs=endNs;
            this.cpuNs=cpuNs; this.thread=thread; this.threadId=threadId; this.detail=detail;
        }
        String toJson() {
            return "{\"seq\":"+sequence+",\"parent_id\":"+parent+",\"depth\":"+depth
                    +",\"kind\":"+quote(kind)+",\"owner\":"+quote(owner)+",\"module_name\":"+quote(moduleName)
                    +",\"module_version\":"+quote(moduleVersion)+",\"start_ns\":"+startNs+",\"end_ns\":"+endNs
                    +",\"duration_ns\":"+Math.max(0L,endNs-startNs)+",\"cpu_ns\":"+cpuNs+",\"thread\":"+quote(thread)
                    +",\"tid\":"+threadId+",\"detail\":"+quote(detail)+"}";
        }
        private static String quote(String value) {
            if (value == null) return "null";
            StringBuilder out=new StringBuilder(value.length()+8).append('"');
            for (int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                switch(c) {
                    case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\""); case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                    default -> { if(c<0x20) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
    }
}
