package dev.wachipayox.bootoptim.profiling.client;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** Diagnostic only: stock table/strategy in both arms; clocks only on growth, clear and trim. */
public final class MeasuredQuadSet<K> extends ObjectOpenCustomHashSet<K> {
    private static final ThreadMXBean CLOCK = ManagementFactory.getThreadMXBean();
    private static volatile MeasuredQuadSet<?> instance;
    private long growthCpu, growthWall, clearCpu, clearWall, trimCpu, trimWall;
    private long insertionCpu, insertionWall, insertions;
    private int growths, clears, trims, previousUnique, trimDepth;
    private boolean valid = true;

    public record Snapshot(long growthCpu, long growthWall, long clearCpu, long clearWall,
                           long trimCpu, long trimWall, int growths, int clears, int trims,
                           int previousUnique, int slots, int size, boolean valid,
                           long insertionCpu, long insertionWall, long insertions) {}

    public MeasuredQuadSet(Hash.Strategy<? super K> strategy) {
        super(strategy);
        if (!CLOCK.isCurrentThreadCpuTimeSupported()) valid = false;
        else if (!CLOCK.isThreadCpuTimeEnabled()) CLOCK.setThreadCpuTimeEnabled(true);
        if (instance != null) valid = false;
        instance = this;
    }

    public static MeasuredQuadSet<?> instance() { return instance; }
    private long cpu() { return CLOCK.isThreadCpuTimeEnabled() ? CLOCK.getCurrentThreadCpuTime() : -1; }
    public long startInsertionCpu() { return cpu(); }
    public void endInsertion(long beginCpu, long beginWall) {
        insertionWall += System.nanoTime() - beginWall;
        insertionCpu += delta(beginCpu);
        insertions++;
    }
    private long delta(long start) {
        long end = cpu();
        if (start < 0 || end < start) { valid = false; return 0; }
        return end - start;
    }

    public synchronized void resetMetrics() {
        growthCpu = growthWall = clearCpu = clearWall = trimCpu = trimWall = 0;
        insertionCpu = insertionWall = insertions = 0;
        growths = clears = trims = previousUnique = 0;
        if (size != 0 || trimDepth != 0) valid = false;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(growthCpu, growthWall, clearCpu, clearWall, trimCpu, trimWall,
                growths, clears, trims, previousUnique, n, size, valid && trimDepth == 0,
                insertionCpu, insertionWall, insertions);
    }

    @Override protected void rehash(int capacity) {
        // Empty rehash inside trim is already included in the trim clock. Never add it twice.
        if (trimDepth != 0) { super.rehash(capacity); return; }
        long beginCpu = cpu(), beginWall = System.nanoTime();
        boolean success = false;
        try { super.rehash(capacity); success = true; }
        finally {
            growthWall += System.nanoTime() - beginWall;
            growthCpu += delta(beginCpu);
            growths++;
            valid &= success;
        }
    }

    @Override public void clear() {
        long beginCpu = cpu(), beginWall = System.nanoTime();
        previousUnique = size;
        boolean success = false;
        try { super.clear(); success = true; }
        finally {
            clearWall += System.nanoTime() - beginWall;
            clearCpu += delta(beginCpu);
            clears++;
            valid &= success && size == 0;
        }
    }

    @Override public boolean trim() {
        // Exact fastutil trim() delegates to virtual trim(size); measure in that override once.
        return super.trim();
    }

    @Override public boolean trim(int expected) {
        long beginCpu = cpu(), beginWall = System.nanoTime();
        trimDepth++;
        boolean success = false;
        try { success = super.trim(expected); return success; }
        finally {
            trimDepth--;
            trimWall += System.nanoTime() - beginWall;
            trimCpu += delta(beginCpu);
            trims++;
            valid &= success;
        }
    }
}
