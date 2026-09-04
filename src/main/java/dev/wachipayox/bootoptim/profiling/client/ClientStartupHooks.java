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
        if (!firstMainMenu) {
            return;
        }

        // Dump aggregate resource/model attribution after the semantic main-menu marker. The reported
        // startup wall time therefore excludes summary formatting/logging. Keep the JFR running until
        // after the dump so any unexpectedly expensive diagnostic overhead remains visible in the trace.
        ResourcePipelineProfiler.dump();

        // Stop the broad startup campaign recording at the same semantic boundary used by the
        // lightweight report and CI. This remains a no-op unless profileStartup is enabled.
        StartupFlightRecorderFinisher.finishAtMainMenu();

        if (StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
