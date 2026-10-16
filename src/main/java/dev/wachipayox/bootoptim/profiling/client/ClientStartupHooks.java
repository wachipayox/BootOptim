package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes, installed only while startup profiling is enabled. */
public final class ClientStartupHooks {
    /** Optional: milliseconds to stay on the title screen before exitOnTitle stops the game (default 0). */
    private static final String EXIT_DELAY_PROPERTY = "boot_optim.benchmark.exitOnTitleDelayMs";
    private static boolean installed;

    private ClientStartupHooks() {
    }

    public static void install() {
        if (!StartupProfiler.isEnabled() || installed) {
            return;
        }

        installed = true;
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        if (StartupProfiler.markMainMenu() && StartupProfiler.shouldExitOnTitle()) {
            long delayMs = Long.getLong(EXIT_DELAY_PROPERTY, 0L);
            if (delayMs <= 0L) {
                Minecraft.getInstance().stop();
                return;
            }
            // Benchmark harness only: stay on the title screen so post-menu steps (no overlay, first tick) can run.
            Thread exit = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Minecraft client = Minecraft.getInstance();
                client.execute(client::stop);
            }, "BootOptim-bench-exit");
            exit.setDaemon(true);
            exit.start();
        }
    }
}
