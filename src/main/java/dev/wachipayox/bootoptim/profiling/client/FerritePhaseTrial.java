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

/** Four measured stock menu reloads, same-mode primers separately declared. Never enters a world. */
public final class FerritePhaseTrial {
    private static final int[] MASKS = {0, 2, 2, 0};
    private static int step;
    private static boolean busy, failed, finished, menuSeen;
    private static final AtomicBoolean INITIAL_SEEN = new AtomicBoolean();
    private static volatile boolean initialComplete, initialFailed;
    private static long stableSince;
    private FerritePhaseTrial() {}

    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!TrialFeatureGate.ENABLED || Minecraft.getInstance() == null
                || Minecraft.getInstance().getResourceManager() != manager
                || !INITIAL_SEEN.compareAndSet(false, true)) return;
        reload.done().whenComplete((ignored, failure) -> {
            initialFailed = failure != null;
            LogUtils.getLogger().info("BOOTOPTIM_FERRITE_PHASE stage=initial_complete success={}", failure == null);
            initialComplete = true;
        });
    }

    public static void install() {
        if (!TrialFeatureGate.ENABLED) return;
        if (!Boolean.getBoolean("boot_optim.ferriteCoreQuadCapacity"))
            throw new IllegalStateException("Ferrite capacity property required in both arms");
        NeoForge.EVENT_BUS.addListener(FerritePhaseTrial::frame);
    }
    private static long gc() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }
    private static void frame(ClientTickEvent.Post event) {
        if (finished || busy) return;
        Minecraft client = Minecraft.getInstance();
        if (initialFailed) { finished = true; LogUtils.getLogger().error("BOOTOPTIM_FERRITE_PHASE stage=invalid"); client.stop(); return; }
        if (client.level != null) { failed = true; finished = true; client.stop(); return; }
        if (Boolean.getBoolean("boot_optim.benchmark.expressDeclineOptionalWelcome")
                && client.getOverlay() == null && client.screen != null && client.screen.getClass().getName().equals(
                "com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) {
            client.screen.onClose(); return;
        }
        if (!initialComplete || !(client.screen instanceof TitleScreen) || client.getOverlay() != null) { stableSince = 0; return; }
        if (!menuSeen) {
            menuSeen = true;
            LogUtils.getLogger().info("BOOTOPTIM_FERRITE_PHASE stage=initial_menu origin=jvm_uptime uptime_ms={}",
                    ManagementFactory.getRuntimeMXBean().getUptime());
        }
        if (failed) {
            finished = true;
            LogUtils.getLogger().error("BOOTOPTIM_FERRITE_PHASE stage=invalid"); client.stop(); return;
        }
        long now = System.nanoTime();
        if (stableSince == 0) { stableSince = now; return; }
        if (now - stableSince < 2_000_000_000L) return;
        if (step == MASKS.length * 2) {
            finished = true;
            LogUtils.getLogger().info("BOOTOPTIM_FERRITE_PHASE stage=finished observations=4 controls=2 candidates=2 primers=4");
            client.stop(); return;
        }
        MeasuredQuadSet<?> table = MeasuredQuadSet.instance();
        if (table == null) { failed = true; return; }
        int index = step++, mask = MASKS[index / 2];
        boolean measured = index % 2 == 1;
        TrialFeatureGate.select(mask);
        table.resetMetrics();
        long begin = System.nanoTime(), beginGc = gc();
        busy = true; stableSince = 0;
        LogUtils.getLogger().info("BOOTOPTIM_FERRITE_PHASE stage=requested index={} mask={} measured={}", index, mask, measured);
        try {
            client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                var m = table.snapshot();
                boolean valid = failure == null && m.valid() && m.clears() == 1 && m.trims() == 1
                        && m.previousUnique() > 0 && m.size() == 0
                        && (mask == 0 ? m.slots() == 1 : m.slots() <= 1 << 21 && m.slots() > 1);
                LogUtils.getLogger().info("BOOTOPTIM_FERRITE_PHASE stage=complete index={} mask={} measured={} success={} growth_cpu_ns={} growth_wall_ns={} growth_calls={} clear_cpu_ns={} clear_wall_ns={} trim_cpu_ns={} trim_wall_ns={} previous_unique={} empty_slots={} reload_wall_ns={} gc_ms={} heap_used_bytes={}",
                        index, mask, measured, valid, m.growthCpu(), m.growthWall(), m.growths(), m.clearCpu(), m.clearWall(),
                        m.trimCpu(), m.trimWall(), m.previousUnique(), m.slots(), System.nanoTime()-begin, gc()-beginGc,
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                failed = !valid; busy = false;
            });
        } catch (Throwable failure) { failed = true; busy = false; }
    }
}
