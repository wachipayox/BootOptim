package dev.wachipayox.bootoptim.profiling.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Diagnostic owner scopes; two CPU reads per outer call, no per-vertex timing/allocation. */
public final class SegmentOwnerMetrics {
    public static final boolean ENABLED = TrialFeatureGate.ENABLED;
    public static final int DECOCRAFT = 0, LAYER = 1, MULTIPART = 2;
    public static final String[] NAMES = {"decocraft", "layer", "multipart"};
    private static final ThreadMXBean THREADS = ENABLED ? ManagementFactory.getThreadMXBean() : null;
    private static final ConcurrentLinkedQueue<Counter> COUNTERS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Counter> LOCAL = new ThreadLocal<>();
    private static volatile int generation;
    private SegmentOwnerMetrics() {}
    private static final class Counter {
        final int generation;
        final int[] depth = new int[3];
        final long[] calls = new long[3], cpu = new long[3], wall = new long[3];
        final long[] cpuStart = new long[3], wallStart = new long[3];
        final long[] work = new long[3], matching = new long[3];
        boolean invalid;
        Counter(int generation) { this.generation = generation; }
    }
    private static Counter counter() {
        Counter c = LOCAL.get();
        if (c == null || c.generation != generation) {
            c = new Counter(generation); LOCAL.set(c); COUNTERS.add(c);
        }
        return c;
    }
    public static void reset() { COUNTERS.clear(); generation++; }
    public static void begin(int owner) {
        if (!ENABLED) return;
        Counter c = counter();
        if (c.depth[owner]++ != 0) return; // Recursive call belongs to the outer interval.
        c.cpuStart[owner] = THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
        c.wallStart[owner] = System.nanoTime();
    }
    public static void end(int owner) {
        if (!ENABLED) return;
        Counter c = counter();
        if (c.depth[owner] <= 0) { c.invalid = true; return; }
        if (--c.depth[owner] != 0) return;
        long wall = System.nanoTime() - c.wallStart[owner];
        long cpu = THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() - c.cpuStart[owner] : -1;
        if (c.cpuStart[owner] < 0 || cpu < 0 || wall < 0) c.invalid = true;
        c.calls[owner]++; c.cpu[owner] += Math.max(0, cpu); c.wall[owner] += wall;
    }
    public static void work(int owner, long count, long matching) {
        if (!ENABLED) return;
        Counter c = counter(); c.work[owner] += count; c.matching[owner] += matching;
    }
    public record Result(long calls, long cpu, long wall, long work, long matching, boolean valid) {}
    public static Result result(int owner) {
        long calls = 0, cpu = 0, wall = 0, work = 0, matching = 0; boolean valid = true;
        for (Counter c : COUNTERS) {
            valid &= !c.invalid && c.depth[owner] == 0 && c.generation == generation;
            calls += c.calls[owner]; cpu += c.cpu[owner]; wall += c.wall[owner];
            work += c.work[owner]; matching += c.matching[owner];
        }
        return new Result(calls, cpu, wall, work, matching, valid && calls > 0);
    }
}
