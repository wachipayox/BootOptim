package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.optimization.client.RendererReloadCoordinator;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes, installed only while startup profiling is enabled. */
public final class ClientStartupHooks {
    private static final boolean RENDERER_FORCE_AFTER_TITLE_SMOKE = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentRendererForceAfterTitleSmoke", "false"));

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

        boolean firstTitle = StartupProfiler.markMainMenu();
        if (!firstTitle) {
            return;
        }

        // Diagnostic-only gate: main_menu is already timestamped, so this deliberately validates
        // the deferred stock/NeoForge reload without changing the TTMM metric.
        if (RENDERER_FORCE_AFTER_TITLE_SMOKE) {
            RendererReloadCoordinator.forcePending("after_title_smoke");
        }

        if (StartupProfiler.shouldExitOnTitle()) {
            Minecraft.getInstance().stop();
        }
    }
}
