package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.List;
import net.minecraft.client.Minecraft;

/** One process, full stock reloads. Flags change only after completion and stable title presentation. */
public final class InProcessCandidateTrial {
    private static final List<TrialPlan.Step> STEPS = TrialPlan.steps();
    private static volatile boolean busy, failed;
    private static int index;
    private static long stableSince;
    private static boolean initialMarked;
    private static boolean finished;
    private static long gcMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum();
    }
    public static void frame(Minecraft client) {
        if (finished || busy) return;
        if (failed) { finished = true; LogUtils.getLogger().error("BOOTOPTIM_TRIAL stage=invalid"); client.stop(); return; }
        long now = System.nanoTime();
        if (stableSince == 0) {
            stableSince = now;
            if (!initialMarked) {
                initialMarked = true;
                LogUtils.getLogger().info("BOOTOPTIM_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms={}", ManagementFactory.getRuntimeMXBean().getUptime());
            }
        }
        if (now - stableSince < 2_000_000_000L) return;
        if (index == STEPS.size()) {
            finished = true;
            LogUtils.getLogger().info("BOOTOPTIM_TRIAL stage=finished steps={} measured=16 origin=reload_invocation endpoint=future_completion", index);
            client.stop(); return;
        }
        if (!client.isSameThread()) throw new IllegalStateException("Trial transition outside client thread");
        int stepIndex = index++;
        TrialPlan.Step step = STEPS.get(stepIndex);
        TrialFeatureGate.select(step.mask());
        TrialBakeMetrics.reset();
        busy = true; stableSince = 0;
        long gc = gcMs(), start = System.nanoTime();
        LogUtils.getLogger().info("BOOTOPTIM_TRIAL stage=requested index={} feature={} mask={} pair={} measured={} kind={}",
                stepIndex, step.feature(), step.mask(), step.pair(), step.measured(), step.kind());
        try {
            client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                long elapsed = System.nanoTime() - start;
                TrialBakeMetrics.Result bake = TrialBakeMetrics.result();
                boolean valid = failure == null && TrialBakeMetrics.calls() == 1 && bake != null
                        && bake.success() && bake.cpuNs() >= 0 && bake.mask() == step.mask();
                LogUtils.getLogger().info("BOOTOPTIM_TRIAL stage=complete index={} success={} feature={} mask={} pair={} measured={} reload_wall_ns={} bake_wall_ns={} bake_cpu_ns={} gc_ms={} heap_used_bytes={}",
                        stepIndex, valid, step.feature(), step.mask(), step.pair(), step.measured(), elapsed,
                        bake == null ? -1 : bake.wallNs(), bake == null ? -1 : bake.cpuNs(), gcMs()-gc,
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                failed = !valid; busy = false;
            });
        } catch (Throwable failure) { failed = true; busy = false; }
    }
}
