package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded diagnostic: original operations always run. Inclusive samples are never summed. */
public final class ModelAncestryProfiler {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileModelAncestry");
    public enum Kind { PARENTS, DEPENDENCIES, MATERIAL, TEXTURE_ENTRY, ELEMENTS, ROOT, TRANSFORMS }
    private static final int K = Kind.values().length, MAX_DEPTH = 256;
    private static final AtomicBoolean CLOSED = new AtomicBoolean();
    private static final LongAdder INFLIGHT = new LongAdder();
    private static volatile boolean overflow;
    private static final java.lang.management.ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC = CPU instanceof com.sun.management.ThreadMXBean a ? a : null;
    private static final Row[] ROWS = new Row[K * 2];
    static { for (int i = 0; i < ROWS.length; i++) ROWS[i] = new Row(); }
    private static final class Row {
        final AtomicLong serial = new AtomicLong();
        final LongAdder calls=new LongAdder(), nested=new LongAdder(), samples=new LongAdder(), validCpu=new LongAdder(), validAlloc=new LongAdder(), failures=new LongAdder();
        final LongAdder cpu=new LongAdder(), wall=new LongAdder(), alloc=new LongAdder(), mapProbes=new LongAdder(), mapHits=new LongAdder(), aliasChecks=new LongAdder(), direct=new LongAdder(), aliased=new LongAdder(), linked=new LongAdder();
    }
    private static final class State {
        int depth, bakeDepth;
        final int[] row=new int[MAX_DEPTH], same=new int[K];
        final long[] cpu=new long[MAX_DEPTH], wall=new long[MAX_DEPTH], alloc=new long[MAX_DEPTH], aliases=new long[MAX_DEPTH];
        final boolean[] sampled=new boolean[MAX_DEPTH];
    }
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);
    private ModelAncestryProfiler() {}
    static boolean sample(long value) {
        long z=value+0x9e3779b97f4a7c15L; z=(z^(z>>>30))*0xbf58476d1ce4e5b9L; z=(z^(z>>>27))*0x94d049bb133111ebL;
        return ((z^(z>>>31)) & 255L)==0; // whitened expected1/256, no periodic phase alias
    }
    public static int begin(Kind kind, boolean alreadyLinked) {
        if (!ENABLED || CLOSED.get()) return -1;
        State s=LOCAL.get();
        if (s.depth==MAX_DEPTH) { overflow=true; return -1; }
        int slot=s.depth++, id=kind.ordinal(), row=id+(s.bakeDepth==0?0:K); s.row[slot]=row; s.aliases[slot]=0;
        Row r=ROWS[row]; r.calls.increment(); if(alreadyLinked) r.linked.increment();
        boolean outer=s.same[id]++==0; if(!outer) r.nested.increment();
        s.sampled[slot]=outer && sample(r.serial.incrementAndGet());
        INFLIGHT.increment();
        if(s.sampled[slot]) {
            s.cpu[slot]=CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()?CPU.getCurrentThreadCpuTime():-1;
            s.alloc[slot]=ALLOC!=null && ALLOC.isThreadAllocatedMemorySupported() && ALLOC.isThreadAllocatedMemoryEnabled()?ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
            s.wall[slot]=System.nanoTime();
        }
        return slot;
    }
    public static void end(int slot, boolean failed) {
        if(slot<0) return;
        State s=LOCAL.get(); int row=s.row[slot], id=row%K; Row r=ROWS[row];
        if(slot!=s.depth-1) overflow=true;
        if(s.sampled[slot]) {
            long wall=System.nanoTime()-s.wall[slot];
            long cpu=s.cpu[slot]<0?-1:CPU.getCurrentThreadCpuTime()-s.cpu[slot];
            long alloc=s.alloc[slot]<0?-1:ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId())-s.alloc[slot];
            r.samples.increment(); r.wall.add(wall);
            if(cpu>=0) {r.validCpu.increment();r.cpu.add(cpu);} if(alloc>=0){r.validAlloc.increment();r.alloc.add(alloc);}
        }
        if(failed) r.failures.increment();
        if(id==Kind.MATERIAL.ordinal()) {if(s.aliases[slot]==0)r.direct.increment();else r.aliased.increment();}
        s.same[id]--; s.depth=slot; INFLIGHT.decrement();
    }
    public static void textureProbe(boolean hit) {
        if(!ENABLED || CLOSED.get())return;
        State s=LOCAL.get();
        for(int i=s.depth-1;i>=0;i--) if(s.row[i]%K==Kind.TEXTURE_ENTRY.ordinal()) {Row r=ROWS[s.row[i]];r.mapProbes.increment();if(hit)r.mapHits.increment();break;}
    }
    public static void aliasCheck() {
        if(!ENABLED || CLOSED.get())return;
        State s=LOCAL.get();
        for(int i=s.depth-1;i>=0;i--) if(s.row[i]%K==Kind.MATERIAL.ordinal()) {s.aliases[i]++; ROWS[s.row[i]].aliasChecks.increment();break;}
    }
    public static void enterBake() {if(ENABLED) LOCAL.get().bakeDepth++;}
    public static void exitBake() {if(ENABLED) LOCAL.get().bakeDepth--;}
    public static void report() {
        if(!ENABLED || !CLOSED.compareAndSet(false,true))return;
        var log=LogUtils.getLogger();
        for(int i=0;i<ROWS.length;i++) {
            Row r=ROWS[i];
            log.info("BOOTOPTIM_MODEL_ANCESTRY phase={} kind={} calls={} nested={} samples={} cpu_valid={} alloc_valid={} cpu_ns={} wall_ns={} allocated_bytes={} failures={} map_probes={} map_hits={} alias_checks={} direct={} aliased={} already_linked={}",
                i<K?"outside_bake":"bake",Kind.values()[i%K],r.calls.sum(),r.nested.sum(),r.samples.sum(),r.validCpu.sum(),r.validAlloc.sum(),r.cpu.sum(),r.wall.sum(),r.alloc.sum(),r.failures.sum(),r.mapProbes.sum(),r.mapHits.sum(),r.aliasChecks.sum(),r.direct.sum(),r.aliased.sum(),r.linked.sum());
        }
        log.info("BOOTOPTIM_MODEL_ANCESTRY_SUMMARY inflight={} overflow={} scope=sampled_original_inclusive_no_extrapolated_savings",INFLIGHT.sum(),overflow);
    }
}
