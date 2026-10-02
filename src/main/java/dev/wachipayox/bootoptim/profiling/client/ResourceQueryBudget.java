package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

/** Diagnostic only: exact ZIP queries, excluding downstream ResourceOutput work. */
public final class ResourceQueryBudget {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileResourceQueryBudget");
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final ConcurrentHashMap<Key, Stats> ROWS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<>();
    private static final LongAdder FAILURES = new LongAdder();
    private ResourceQueryBudget() {}

    public static Frame begin(PackResources pack, PackType type, String namespace, String path) {
        Stats stats = ROWS.computeIfAbsent(new Key(pack, type, namespace, path), ignored -> new Stats());
        Frame frame = new Frame(stats, ACTIVE.get());
        ACTIVE.set(frame);
        return frame;
    }

    public static void callback(Runnable original) {
        Frame frame = ACTIVE.get();
        if (frame == null) { original.run(); return; }
        long startCpu = cpu(), startWall = System.nanoTime();
        try { original.run(); }
        finally {
            frame.callbackWall += System.nanoTime() - startWall;
            long end = cpu();
            if (startCpu >= 0 && end >= startCpu) frame.callbackCpu += end - startCpu;
            else frame.cpuValid = false;
            frame.outputs++;
        }
    }

    public static void finish(Frame frame, boolean success) {
        long endCpu = cpu(), elapsed = System.nanoTime() - frame.startWall;
        ACTIVE.set(frame.parent);
        if (!success) { FAILURES.increment(); return; }
        Stats stats = frame.stats;
        stats.calls.increment(); stats.outputs.add(frame.outputs);
        stats.wall.add(elapsed); stats.ownerWall.add(Math.max(0, elapsed - frame.callbackWall));
        if (frame.cpuValid && frame.startCpu >= 0 && endCpu >= frame.startCpu) {
            long ownerCpu = Math.max(0, endCpu - frame.startCpu - frame.callbackCpu);
            stats.cpuCalls.increment(); stats.cpu.add(ownerCpu);
            if (frame.ordinal >= 3) stats.thirdCpu.add(ownerCpu);
        }
    }

    private static long cpu() {
        return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()
                ? CPU.getCurrentThreadCpuTime() : -1;
    }

    public static void report() {
        if (!ENABLED) return;
        var logger = LogUtils.getLogger();
        logger.info("BOOTOPTIM_RESOURCE_QUERY status=complete rows={} failed_calls={}", ROWS.size(), FAILURES.sum());
        ROWS.forEach((key, stats) -> logger.info(
                "BOOTOPTIM_RESOURCE_QUERY pack={} type={} namespace={} path={} calls={} third_plus={} outputs={} cpu_calls={} owner_cpu_ns={} inclusive_wall_ns={} owner_wall_ns={} third_plus_owner_cpu_ns={}",
                key.pack.packId(), key.type, key.namespace, key.path, stats.calls.sum(),
                Math.max(0, stats.calls.sum() - 2), stats.outputs.sum(), stats.cpuCalls.sum(),
                stats.cpu.sum(), stats.wall.sum(), stats.ownerWall.sum(), stats.thirdCpu.sum()));
    }

    private record Key(PackResources pack, PackType type, String namespace, String path) {}
    private static final class Stats {
        final LongAdder calls = new LongAdder(), outputs = new LongAdder(), cpuCalls = new LongAdder();
        final AtomicLong started = new AtomicLong();
        final LongAdder thirdCpu = new LongAdder();
        final LongAdder cpu = new LongAdder(), wall = new LongAdder(), ownerWall = new LongAdder();
    }
    public static final class Frame {
        final Stats stats;
        final Frame parent;
        final long ordinal;
        final long startCpu = cpu(), startWall = System.nanoTime();
        long callbackCpu, callbackWall, outputs;
        boolean cpuValid = true;
        Frame(Stats stats, Frame parent) { this.stats = stats; this.parent = parent; this.ordinal = stats.started.incrementAndGet(); }
    }
}
