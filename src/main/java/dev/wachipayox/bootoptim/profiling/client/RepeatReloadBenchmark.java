package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Opt-in hosted diagnostic: stock resource reloads after the startup endpoint. */
final class RepeatReloadBenchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COUNT = Integer.getInteger("boot_optim.benchmark.repeatReloads", 0);
    private static boolean started;
    private static boolean finished;
    private static int generation;
    private static int readyTicks;
    private static boolean pending;
    private static volatile Completion completion;

    private RepeatReloadBenchmark() {}

    static boolean start() {
        // Never automate reloads or exit a normal interactive session.
        if (!StartupProfiler.shouldExitOnTitle() || COUNT < 1 || COUNT > 3) return false;
        if (started) return true;
        started = true;
        LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=armed count={} origin=reload_invocation endpoint=future_completion world=none", COUNT);
        NeoForge.EVENT_BUS.addListener(RepeatReloadBenchmark::tick);
        return true;
    }

    private static void tick(ClientTickEvent.Post event) {
        if (finished) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            finish(client, false);
            return;
        }
        Completion observed = completion;
        if (observed != null) {
            completion = null;
            pending = false;
            LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=end generation={} success={} wall_ns={}",
                    generation, observed.failure == null, observed.elapsedNs);
            if (observed.failure != null) {
                LOGGER.error("Hosted repeat reload failed", observed.failure);
                finish(client, false);
                return;
            }
        }
        if (pending || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) {
            readyTicks = 0;
            return;
        }
        // Allow the previous stock overlay/fade and menu initialization to settle.
        if (++readyTicks < 40) return;
        readyTicks = 0;
        if (generation == COUNT) {
            finish(client, true);
            return;
        }
        generation++;
        pending = true;
        LOGGER.info("BOOTOPTIM_REPEAT_RELOAD stage=begin generation={}", generation);
        long origin = System.nanoTime();
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
