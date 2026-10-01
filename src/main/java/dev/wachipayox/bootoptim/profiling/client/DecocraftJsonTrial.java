package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.client.DecocraftModelArchiveBatch;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Reuses the existing finite menu-only trial protocol: C/B/B/C with same-mode primers. */
public final class DecocraftJsonTrial {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.benchmark.decocraftJsonTrials");
    private static final boolean[] MODES = {false, true, true, false};
    private static volatile boolean candidate;
    private static final AtomicBoolean INITIAL = new AtomicBoolean();
    private static volatile boolean initialComplete, initialFailed, busy, failed;
    private static boolean finished, menuSeen;
    private static int step;
    private static long stableSince;
    private DecocraftJsonTrial() {}
    public static boolean candidateEnabled() { return !ENABLED || candidate; }
    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!ENABLED || Minecraft.getInstance() == null || Minecraft.getInstance().getResourceManager() != manager
                || !INITIAL.compareAndSet(false, true)) return;
        reload.done().whenComplete((ignored, failure) -> {
            initialFailed = failure != null;
            LogUtils.getLogger().info("BOOTOPTIM_JSON_TRIAL stage=initial_complete success={}", failure == null);
            initialComplete = true;
        });
    }
    public static void install() {
        if (!ENABLED) return;
        if (!DecocraftJsonMetrics.ENABLED || !Boolean.getBoolean("boot_optim.experimentDecocraftModelArchiveBatch")
                || Boolean.getBoolean("boot_optim.experimentDecocraftModelArchiveBatchVerify"))
            throw new IllegalStateException("JSON trial requires owner clocks and batch property; verification must be off");
        NeoForge.EVENT_BUS.addListener(DecocraftJsonTrial::frame);
    }
    private static long gc() { return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
    private static void frame(ClientTickEvent.Post event) {
        if (finished || busy) return;
        Minecraft client = Minecraft.getInstance();
        if (initialFailed || client.level != null || failed) {
            finished = true;
            LogUtils.getLogger().error("BOOTOPTIM_JSON_TRIAL stage=invalid"); client.stop(); return;
        }
        if (Boolean.getBoolean("boot_optim.benchmark.expressDeclineOptionalWelcome") && client.getOverlay() == null
                && client.screen != null && client.screen.getClass().getName().equals(
                "com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) { client.screen.onClose(); return; }
        if (!initialComplete || !(client.screen instanceof TitleScreen) || client.getOverlay() != null) {
            stableSince = 0; return;
        }
        if (!menuSeen) {
            menuSeen = true;
            LogUtils.getLogger().info("BOOTOPTIM_JSON_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms={}",
                    ManagementFactory.getRuntimeMXBean().getUptime());
        }
        long now = System.nanoTime();
        if (stableSince == 0) { stableSince = now; return; }
        if (now - stableSince < 2_000_000_000L) return;
        if (step == 8) {
            finished = true;
            LogUtils.getLogger().info("BOOTOPTIM_JSON_TRIAL stage=finished observations=4 controls=2 candidates=2 primers=4");
            client.stop(); return;
        }
        int index = step++;
        candidate = MODES[index / 2];
        boolean measured = index % 2 == 1;
        long begin = System.nanoTime(), beginGc = gc();
        busy = true; stableSince = 0;
        LogUtils.getLogger().info("BOOTOPTIM_JSON_TRIAL stage=requested index={} candidate={} measured={}", index, candidate, measured);
        try {
            client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                var metrics = DecocraftJsonMetrics.snapshot();
                boolean valid = failure == null && metrics.valid() && metrics.tasks() == 10809
                        && metrics.opens() == 10809 && DecocraftModelArchiveBatch.generationValid()
                        && (candidate ? DecocraftModelArchiveBatch.generationHits() == 10809 : DecocraftModelArchiveBatch.generationHits() == 0);
                LogUtils.getLogger().info("BOOTOPTIM_JSON_TRIAL stage=complete index={} candidate={} measured={} success={} task_cpu_ns={} task_wall_ns={} tasks={} open_cpu_ns={} open_wall_ns={} opens={} batches={} batch_cpu_ns={} batch_wall_ns={} hits={} retained_bytes={} reload_wall_ns={} gc_ms={} heap_used_bytes={}",
                        index, candidate, measured, valid, metrics.taskCpu(), metrics.taskWall(), metrics.tasks(),
                        metrics.openCpu(), metrics.openWall(), metrics.opens(), metrics.batches(), metrics.batchCpu(),
                        metrics.batchWall(), DecocraftModelArchiveBatch.generationHits(), DecocraftModelArchiveBatch.retainedBytes(),
                        System.nanoTime()-begin, gc()-beginGc, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                failed = !valid; busy = false;
            });
        } catch (Throwable failure) {
            LogUtils.getLogger().error("BOOTOPTIM_JSON_TRIAL stage=invalid reason=request_failed", failure);
            failed = true; busy = false;
        }
    }
}
