package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes for the legacy profiler and structured startup trace. */
public final class ClientStartupHooks {
    private static boolean installed;

    private ClientStartupHooks() {
    }

    public static void install() {
        if ((!StartupProfiler.isEnabled() && !ResourceReloadDagTrace.enabled()) || installed) {
            return;
        }

        installed = true;
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        ResourceReloadDagTrace.markMainMenuEndpoint();
        if (StartupProfiler.isEnabled()
                && StartupProfiler.markMainMenu()
                && StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
