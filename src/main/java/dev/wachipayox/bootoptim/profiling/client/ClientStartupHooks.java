package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only startup probes, installed only while startup profiling is enabled. */
public final class ClientStartupHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ScreenProbe");
    private static final boolean SCREEN_PROBE = Boolean.getBoolean("boot_optim.benchmark.screenProbe");
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
        if (SCREEN_PROBE) {
            var screen = event.getNewScreen();
            LOGGER.info("BOOTOPTIM_SCREEN event=opening class={} title_screen={}",
                    screen == null ? "null" : screen.getClass().getName(),
                    screen instanceof TitleScreen);
        }
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        if (StartupProfiler.markMainMenu() && StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
