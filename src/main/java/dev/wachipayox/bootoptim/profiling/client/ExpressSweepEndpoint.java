package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Fixed-marker physical diagnostic. Never enters a world or requests a reload. */
public final class ExpressSweepEndpoint {
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.benchmark.expressSweep");
    private static final AtomicBoolean FIRST_RELOAD = new AtomicBoolean();
    private static volatile boolean reloadComplete;
    private static volatile boolean reloadFailed;
    private static boolean installed;
    private static boolean finished;
    private static long presentedAt;
    private static boolean repeatStarted;
    private static volatile boolean repeatComplete;
    private static volatile boolean repeatFailed;
    private static final boolean MENU_REPEAT = Integer.getInteger("boot_optim.benchmark.expressMenuReloads", 0) == 1;
    private static final boolean DECLINE_WELCOME = Boolean.getBoolean("boot_optim.benchmark.expressDeclineOptionalWelcome");
    private static boolean welcomeDeclined;

    private ExpressSweepEndpoint() {}

    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!ENABLED) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getResourceManager() != manager
                || !FIRST_RELOAD.compareAndSet(false, true)) return;
        LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=initial_reload_created uptime_ms={}", uptime());
        reload.done().whenComplete((ignored, failure) -> {
            reloadFailed = failure != null;
            LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=initial_reload_complete success={} uptime_ms={}", failure == null, uptime());
            reloadComplete = true;
        });
    }

    public static void install() {
        if (!ENABLED || installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(ExpressSweepEndpoint::frame);
    }

    private static void frame(RenderFrameEvent.Post event) {
        if (finished) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null || reloadFailed || repeatFailed) {
            finished = true;
            LogUtils.getLogger().error("BOOTOPTIM_SWEEP stage=invalid reason={}", client.level != null ? "unexpected_world" : "initial_reload_failed");
            client.stop();
            return;
        }
        // Require the real initial generation future, even if RRLS exposes an early title.
        // Hosted-only opt-in uses the previously verified exact AnalogAudio decline route.
        if (DECLINE_WELCOME && !welcomeDeclined && client.getOverlay() == null && client.screen != null
                && client.screen.getClass().getName().equals("com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) {
            welcomeDeclined = true;
            client.screen.onClose();
            return;
        }
        if (!reloadComplete || client.getOverlay() != null || !(client.screen instanceof TitleScreen)
                || (repeatStarted && !repeatComplete)) {
            presentedAt = 0;
            return;
        }
        long now = System.nanoTime();
        if (presentedAt == 0) {
            presentedAt = now;
            if (!repeatStarted) LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=main_menu_presented origin=jvm_uptime uptime_ms={}", uptime());
        }
        if (now - presentedAt < 2_000_000_000L) return;
        if (MENU_REPEAT && !repeatStarted) {
            repeatStarted = true;
            presentedAt = 0;
            long origin = System.nanoTime();
            LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=menu_reload_requested origin=reload_invocation endpoint=future_completion");
            client.reloadResourcePacks().whenComplete((ignored, failure) -> {
                repeatFailed = failure != null;
                LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=menu_reload_complete success={} wall_ns={}", failure == null, System.nanoTime() - origin);
                repeatComplete = true;
            });
            return;
        }
        long gcCount = 0;
        long gcMs = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, bean.getCollectionCount());
            gcMs += Math.max(0, bean.getCollectionTime());
        }
        finished = true;
        LogUtils.getLogger().info("BOOTOPTIM_SWEEP stage=finished origin=jvm_uptime uptime_ms={} gc_count={} gc_ms={}", uptime(), gcCount, gcMs);
        client.stop();
    }

    private static long uptime() { return ManagementFactory.getRuntimeMXBean().getUptime(); }
}
