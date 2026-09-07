package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes, installed only while startup profiling is enabled. */
public final class ClientStartupHooks {
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

        boolean firstMainMenu = StartupProfiler.markMainMenu();
        if (firstMainMenu) {
            // Dump only after the semantic main-menu marker so profiler reporting cannot inflate time-to-menu.
            ResourceOpenPhysicalProfiler.dump();
        }
        if (firstMainMenu && StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
