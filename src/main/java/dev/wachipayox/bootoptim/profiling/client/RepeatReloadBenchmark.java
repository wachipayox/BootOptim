package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Opt-in hosted diagnostic: stock resource reloads after the startup endpoint. */
final class RepeatReloadBenchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COUNT = Integer.getInteger("boot_optim.benchmark.repeatReloads", 0);
    private static boolean started;
    private static boolean finished;
    private static int generation;
    private static long readySince;
    private static long phaseOrigin;
    private static boolean welcomeDismissed;
    private static boolean pending;
    private static volatile Completion completion;

    private RepeatReloadBenchmark() {}

    static boolean start() {
        // Never automate reloads or exit a normal interactive session.
        if (!StartupProfiler.shouldExitOnTitle() || COUNT < 1 || COUNT > 3) return false;
        if (started) return true;
        started = true;
        phaseOrigin = System.nanoTime();
        LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=armed count={} origin=reload_invocation endpoint=future_completion world=none", COUNT);
        NeoForge.EVENT_BUS.addListener(RepeatReloadBenchmark::frame);
        return true;
    }

    private static void frame(RenderFrameEvent.Post event) {
        if (finished) return;
        Minecraft client = Minecraft.getInstance();
        long now = System.nanoTime();
        if (client.level != null) {
            finish(client, false);
            return;
        }
        Completion observed = completion;
        if (observed != null) {
            completion = null;
            pending = false;
            phaseOrigin = now;
            LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=end generation={} success={} wall_ns={}",
                    generation, observed.failure == null, observed.elapsedNs);
            if (observed.failure != null) {
                LOGGER.error("Hosted repeat reload failed", observed.failure);
                finish(client, false);
                return;
            }
        }
        // The pinned hosted fixture lacks optional Lavaplayer native binaries.
        // Its decline button and onClose both return to lastScreen without a download
        // or configuration mutation (AnalogAudio 0.1.0 bytecode verified).
        if (!pending && client.getOverlay() == null && !welcomeDismissed && client.screen != null
                && client.screen.getClass().getName().equals("com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) {
            welcomeDismissed = true;
            LOGGER.info("BOOTOPTIM_REPEAT_RELOAD_READY action=decline_optional_lavaplayer");
            client.screen.onClose();
            readySince = 0;
            phaseOrigin = now;
            return;
        }
        if (now - phaseOrigin > 180_000_000_000L) {
            LOGGER.error("Hosted repeat reload timed out: pending={} screen={} overlay={}", pending,
                    client.screen == null ? "none" : client.screen.getClass().getName(),
                    client.getOverlay() == null ? "none" : client.getOverlay().getClass().getName());
            finish(client, false);
            return;
        }
        if (pending || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) {
            readySince = 0;
            return;
        }
        // Allow the previous stock overlay/fade and menu initialization to settle.
        if (readySince == 0) readySince = now;
        if (now - readySince < 2_000_000_000L) return;
        readySince = 0;
        if (generation == COUNT) {
            finish(client, true);
            return;
        }
        generation++;
        pending = true;
        LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=begin generation={}", generation);
        long origin = System.nanoTime();
        phaseOrigin = origin;
        try {
            client.reloadResourcePacks().whenComplete((ignored, failure) ->
                    completion = new Completion(System.nanoTime() - origin, failure));
        } catch (RuntimeException failure) {
            completion = new Completion(System.nanoTime() - origin, failure);
        }
    }

    private static void finish(Minecraft client, boolean success) {
        finished = true;
        LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=done requested={} success={}", COUNT, success);
        client.stop();
    }

    private record Completion(long elapsedNs, Throwable failure) {}
}
