package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Diagnostic only. Every original lookup, read, parse and mutable model remains stock. */
public final class CitMutableParseBudget {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileCitMutableParses");
    private static final AtomicBoolean CLOSED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong(), INFLIGHT = new AtomicLong();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final Stats FIRST = new Stats(), OVERRIDE = new Stats(), READ = new Stats(), PARSE = new Stats();
    private static final Map<String, Long> CONTENT = new HashMap<>();
    private static long retainedChars, contentCalls;
    private static boolean truncated;

    private CitMutableParseBudget() {}
    private static final class Stats {
        final LongAdder calls = new LongAdder(), failures = new LongAdder(), nulls = new LongAdder();
        final LongAdder samples = new LongAdder(), cpuSamples = new LongAdder(), cpu = new LongAdder(), wall = new LongAdder();
    }
    public static final class Scope {
        final Scope parent;
        final Stats stats;
        final boolean sampled;
        final long cpu, wall;
        Scope(Scope parent, Stats stats, boolean sampled) {
            this.parent = parent; this.stats = stats; this.sampled = sampled;
            wall = sampled ? System.nanoTime() : 0;
            cpu = sampled ? ResourceResolutionBudget.cpuNow() : -1;
        }
    }
    public static Scope begin(String method) {
        if (!ENABLED || CLOSED.get()) return null;
        INFLIGHT.incrementAndGet();
        Scope parent = ACTIVE.get();
        Stats stats = method.equals("first") ? FIRST : OVERRIDE;
        stats.calls.increment();
        long x = SEQUENCE.getAndIncrement() + 0x9e3779b97f4a7c15L;
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        Scope scope = new Scope(parent, stats, parent == null && ((x ^ (x >>> 31)) & 15) == 0);
        ACTIVE.set(scope);
        return scope;
    }
    public static Scope child(boolean parse, String json) {
        if (!ENABLED) return null;
        Scope parent = ACTIVE.get();
        if (parent == null) return null;
        if (parse) recordContent(json); // Equality census outside the child CPU endpoints.
        Stats stats = parse ? PARSE : READ;
        stats.calls.increment();
        return new Scope(parent, stats, parent.sampled);
    }
    private static synchronized void recordContent(String json) {
        contentCalls++;
        if (json == null) { truncated = true; return; }
        Long count = CONTENT.get(json);
        if (count != null) { CONTENT.put(json, count + 1); return; }
        // Full String equality, not a hash-only identity claim. At most 4 MiB UTF-16 payload.
        if (CONTENT.size() >= 256 || retainedChars + json.length() > 2_097_152) { truncated = true; return; }
        CONTENT.put(json, 1L); retainedChars += json.length();
    }
    public static void finish(Scope scope, boolean success, boolean nullResult, boolean root) {
        if (scope == null) return;
        long cpu = scope.sampled ? ResourceResolutionBudget.cpuNow() : -1;
        long wall = scope.sampled ? System.nanoTime() : 0;
        if (!success) scope.stats.failures.increment();
        if (nullResult) scope.stats.nulls.increment();
        if (scope.sampled) {
            scope.stats.samples.increment(); scope.stats.wall.add(wall - scope.wall);
            if (scope.cpu >= 0 && cpu >= scope.cpu) { scope.stats.cpuSamples.increment(); scope.stats.cpu.add(cpu - scope.cpu); }
        }
        if (root) { if (scope.parent == null) ACTIVE.remove(); else ACTIVE.set(scope.parent); INFLIGHT.decrementAndGet(); }
    }
    public static synchronized void report() {
        if (!ENABLED || !CLOSED.compareAndSet(false, true)) return;
        LogUtils.getLogger().info("BOOTOPTIM_CIT_MUTABLE_CONTENT calls={} unique={} retained_chars={} inflight={} truncated={}",
                contentCalls, CONTENT.size(), retainedChars, INFLIGHT.get(), truncated);
        row("first", FIRST); row("override", OVERRIDE); row("read", READ); row("parse", PARSE);
        if (INFLIGHT.get() == 0) CONTENT.clear();
    }
    private static void row(String stage, Stats s) {
        LogUtils.getLogger().info("BOOTOPTIM_CIT_MUTABLE_STAGE stage={} calls={} failures={} nulls={} samples={} cpu_samples={} sampled_cpu_ns={} sampled_wall_ns={}",
                stage, s.calls.sum(), s.failures.sum(), s.nulls.sum(), s.samples.sum(), s.cpuSamples.sum(), s.cpu.sum(), s.wall.sum());
    }
}
