package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** One bounded process, two controls/two candidates per owner, separate same-mode primers. */
public final class SegmentOwnerTrial {
    private static final int[] FEATURES = {1, 8, 16};
    private static final int[] ORDER = {0, 1, 1, 0};
    private static final AtomicBoolean INITIAL = new AtomicBoolean();
    private static volatile boolean initialComplete, failed, busy;
    private static boolean finished, menuSeen;
    private static long stableSince;
    private static int step;
    private SegmentOwnerTrial() {}
    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!SegmentOwnerMetrics.ENABLED || Minecraft.getInstance() == null
                || Minecraft.getInstance().getResourceManager() != manager
                || !INITIAL.compareAndSet(false, true)) return;
        reload.done().whenComplete((ignored, failure) -> {
            failed = failure != null;
            LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=initial_complete success={}", failure == null);
            initialComplete = true;
        });
    }
    public static void install() {
        if (!SegmentOwnerMetrics.ENABLED) return;
        for (String property : new String[]{"experimentalDecocraftCornerRotationReuseV2", "generatedItemLayerDeltaHoist", "multipartValidatedUnion"})
            if (!Boolean.getBoolean("boot_optim." + property)) throw new IllegalStateException("Missing trial property " + property);
        if (Boolean.getBoolean("boot_optim.verifyMultipartUnion") || Boolean.getBoolean("boot_optim.verifyDecocraftCornerRotationReuse"))
            throw new IllegalStateException("Semantic verification is not timed performance");
        NeoForge.EVENT_BUS.addListener(SegmentOwnerTrial::frame);
    }
    private static long gc() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }
    private static void frame(ClientTickEvent.Post event) {
        if (finished || busy) return;
        Minecraft client = Minecraft.getInstance();
        if (failed || client.level != null) {
            finished = true; LogUtils.getLogger().error("BOOTOPTIM_OWNER_TRIAL stage=invalid"); client.stop(); return;
        }
        if (Boolean.getBoolean("boot_optim.benchmark.expressDeclineOptionalWelcome") && client.getOverlay() == null
                && client.screen != null && client.screen.getClass().getName().equals("com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) {
            client.screen.onClose(); return;
        }
        if (!initialComplete || !(client.screen instanceof TitleScreen) || client.getOverlay() != null) { stableSince = 0; return; }
        if (!menuSeen) {
            menuSeen = true;
            LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms={}", ManagementFactory.getRuntimeMXBean().getUptime());
        }
        long now = System.nanoTime();
        if (stableSince == 0) { stableSince = now; return; }
        if (now - stableSince < 2_000_000_000L) return;
        if (step == 24) {
            finished = true;
            LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=finished observations=12 controls=6 candidates=6 primers=12");
            client.stop(); return;
        }
        int index = step++, observation = index / 2, owner = observation / 4;
        int mask = ORDER[observation % 4] * FEATURES[owner];
        boolean measured = index % 2 == 1;
        TrialFeatureGate.select(mask); SegmentOwnerMetrics.reset();
        long start = System.nanoTime(), gcStart = gc();
        busy = true; stableSince = 0;
        LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=requested index={} owner={} mask={} measured={}", index, SegmentOwnerMetrics.NAMES[owner], mask, measured);
        try {
            client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                boolean valid = failure == null;
                for (int o = 0; o < 3; o++) {
                    var row = SegmentOwnerMetrics.result(o); valid &= row.valid();
                    LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=owner index={} owner={} calls={} cpu_ns={} wall_ns={} work={} matching={} valid={}",
                            index, SegmentOwnerMetrics.NAMES[o], row.calls(), row.cpu(), row.wall(), row.work(), row.matching(), row.valid());
                }
                LogUtils.getLogger().info("BOOTOPTIM_OWNER_TRIAL stage=complete index={} owner={} mask={} measured={} success={} reload_wall_ns={} gc_ms={} heap_used_bytes={}",
                        index, SegmentOwnerMetrics.NAMES[owner], mask, measured, valid, System.nanoTime() - start, gc() - gcStart,
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                failed = !valid; busy = false;
            });
        } catch (Throwable failure) { failed = true; busy = false; }
    }
}
