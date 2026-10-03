package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.StrictPathSegmentValidator;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/** Diagnostic census and post-menu C/B/B/C replay, completely separate from startup timing. */
public final class StrictPathSegmentBudget {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileStrictPathSegments");
    private static final ConcurrentHashMap<String, Entry> SEGMENTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean CLOSED = new AtomicBoolean();
    private static final LongAdder INFLIGHT = new LongAdder(), SKIPPED = new LongAdder();
    private static volatile boolean truncated;
    private static long retainedChars;
    private static volatile boolean sink;
    private static final class Entry {
        final boolean observed;
        final LongAdder calls = new LongAdder();
        volatile boolean consistent = true;
        Entry(boolean observed) { this.observed = observed; }
    }
    private StrictPathSegmentBudget() {}
    public static void record(Pattern pattern, String segment, boolean result) {
        if (!ENABLED) return;
        INFLIGHT.increment();
        try {
            if (CLOSED.get()) return;
            if (segment == null || !StrictPathSegmentValidator.compatible(pattern)) { SKIPPED.increment(); return; }
            Entry entry = SEGMENTS.get(segment);
            if (entry == null) synchronized (SEGMENTS) {
                entry = SEGMENTS.get(segment);
                if (entry == null && SEGMENTS.size() < 50_000 && segment.length() <= 4096
                        && retainedChars + segment.length() <= 2_097_152) {
                    entry = new Entry(result); SEGMENTS.put(segment, entry); retainedChars += segment.length();
                }
            }
            if (entry == null) { truncated = true; return; }
            if (entry.observed != result) entry.consistent = false;
            entry.calls.increment();
        } finally { INFLIGHT.decrement(); }
    }
    public static void report() {
        if (!ENABLED || !CLOSED.compareAndSet(false, true)) return;
        var logger = LogUtils.getLogger();
        var cpu = ManagementFactory.getThreadMXBean();
        long count = SEGMENTS.values().stream().mapToLong(e -> e.calls.sum()).sum();
        if (truncated || INFLIGHT.sum() != 0 || SKIPPED.sum() != 0 || count == 0 || count > 10_000_000
                || !cpu.isCurrentThreadCpuTimeSupported() || !cpu.isThreadCpuTimeEnabled()) {
            logger.info("BOOTOPTIM_STRICT_SEGMENT status=unavailable calls={} inflight={} skipped={} truncated={}",
                    count, INFLIGHT.sum(), SKIPPED.sum(), truncated);
            SEGMENTS.clear(); return;
        }
        Pattern stock = Pattern.compile("[-._a-z0-9]+");
        var rows = new ArrayList<>(SEGMENTS.entrySet());
        rows.sort(Comparator.comparing(java.util.Map.Entry::getKey));
        boolean equivalent = true;
        String[] workload = new String[(int) count];
        int offset = 0;
        long chars = 0, rejected = 0;
        for (var row : rows) {
            String s = row.getKey(); Entry e = row.getValue(); long calls = e.calls.sum();
            equivalent &= e.consistent && stock.matcher(s).matches() == e.observed
                    && StrictPathSegmentValidator.guarded(stock, s) == e.observed;
            chars += calls * s.length(); if (!e.observed) rejected += calls;
            for (long i = 0; i < calls; i++) workload[offset++] = s;
            for (int i = 0; i < 32; i++) { sink = stock.matcher(s).matches(); sink = StrictPathSegmentValidator.guarded(stock, s); }
        }
        // One CPU clock around the entire corpus, avoiding per-row Windows tick quantization.
        // Array loads vary the input so the pure scan cannot be hoisted out of a constant-string loop.
        long[] elapsed = new long[4];
        for (int block = 0; block < 4; block++) {
            long start = cpu.getCurrentThreadCpuTime();
            if (block == 0 || block == 3) for (String s : workload) sink = stock.matcher(s).matches();
            else for (String s : workload) sink = StrictPathSegmentValidator.guarded(stock, s);
            elapsed[block] = cpu.getCurrentThreadCpuTime() - start;
        }
        logger.info("BOOTOPTIM_STRICT_SEGMENT status=complete rows={} calls={} characters={} rejected={} equivalent={} inflight={} skipped={} truncated={} c1_cpu_ns={} b1_cpu_ns={} b2_cpu_ns={} c2_cpu_ns={}",
                rows.size(), count, chars, rejected, equivalent, INFLIGHT.sum(), SKIPPED.sum(), truncated,
                elapsed[0], elapsed[1], elapsed[2], elapsed[3]);
        SEGMENTS.clear();
    }
}
