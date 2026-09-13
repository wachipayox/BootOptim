package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.gui.LoadingErrorScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only startup probes, installed only while startup profiling is enabled. */
public final class ClientStartupHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ScreenProbe");
    private static final boolean SCREEN_PROBE = Boolean.getBoolean("boot_optim.benchmark.screenProbe");
    private static final boolean EXIT_ON_LOADING_ERROR = Boolean.getBoolean("boot_optim.benchmark.exitOnLoadingError");
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
        var screen = event.getNewScreen();
        if (SCREEN_PROBE || (EXIT_ON_LOADING_ERROR && screen instanceof LoadingErrorScreen)) {
            LOGGER.info("BOOTOPTIM_SCREEN event=opening class={} title_screen={}",
                    screen == null ? "null" : screen.getClass().getName(),
                    screen instanceof TitleScreen);
        }
        if (EXIT_ON_LOADING_ERROR && screen instanceof LoadingErrorScreen) {
            LOGGER.error("BOOTOPTIM_SCREEN status=loading_error_screen exit=true");
            Minecraft.getInstance().stop();
            return;
        }
        if (!(screen instanceof TitleScreen)) {
            return;
        }

        if (StartupProfiler.markMainMenu() && StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
