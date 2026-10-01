package dev.wachipayox.bootoptim.profiling.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/** Diagnostic task sums, never critical-path wall. Nested open is not added to task. */
public final class DecocraftJsonMetrics {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileDecocraftJsonOwner");
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final LongAdder taskCpu = new LongAdder(), taskWall = new LongAdder(), tasks = new LongAdder();
    private static final LongAdder openCpu = new LongAdder(), openWall = new LongAdder(), opens = new LongAdder();
    private static final LongAdder batchCpu = new LongAdder(), batchWall = new LongAdder(), batches = new LongAdder();
    private static volatile boolean valid;
    private DecocraftJsonMetrics() {}
    public record Stamp(long wall, long cpu) {}
    public record Metrics(boolean valid, long tasks, long taskCpu, long taskWall,
                          long opens, long openCpu, long openWall, long batches, long batchCpu, long batchWall) {}
    public static boolean eligible(ResourceLocation id, Resource resource) {
        return ENABLED && "decocraft".equals(id.getNamespace()) && id.getPath().endsWith(".json")
                && (id.getPath().startsWith("models/") || id.getPath().startsWith("blockstates/"))
                && resource.source() != null && "mod/decocraft".equals(resource.sourcePackId());
    }
    public static void reset() {
        taskCpu.reset(); taskWall.reset(); tasks.reset(); openCpu.reset(); openWall.reset(); opens.reset();
        batchCpu.reset(); batchWall.reset(); batches.reset();
        valid = CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled();
    }
    public static Stamp start() {
        return new Stamp(System.nanoTime(), CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()
                ? CPU.getCurrentThreadCpuTime() : -1);
    }
    private static void end(Stamp stamp, LongAdder count, LongAdder wall, LongAdder cpu) {
        long nowCpu = CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()
                ? CPU.getCurrentThreadCpuTime() : -1;
        long nowWall = System.nanoTime();
        if (stamp.cpu < 0 || nowCpu < stamp.cpu) valid = false;
        else cpu.add(nowCpu - stamp.cpu);
        wall.add(nowWall - stamp.wall); count.increment();
    }
    public static void taskEnd(Stamp stamp) { end(stamp, tasks, taskWall, taskCpu); }
    public static void openEnd(Stamp stamp) { end(stamp, opens, openWall, openCpu); }
    public static void batchEnd(Stamp stamp) { end(stamp, batches, batchWall, batchCpu); }
    public static Metrics snapshot() { return new Metrics(valid, tasks.sum(), taskCpu.sum(), taskWall.sum(),
            opens.sum(), openCpu.sum(), openWall.sum(), batches.sum(), batchCpu.sum(), batchWall.sum()); }
}
