package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes, installed only while startup profiling or an explicit diagnostic is enabled. */
public final class ClientStartupHooks {
    private static boolean installed;

    private ClientStartupHooks() {
    }

    public static void install() {
        if ((!StartupProfiler.isEnabled() && !PostFancyMenuTailProfiler.isEnabled()) || installed) {
            return;
        }

        installed = true;
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenOpening);
        if (PostFancyMenuTailProfiler.isEnabled()) {
            NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenRendered);
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        boolean tailDiagnostic = PostFancyMenuTailProfiler.isEnabled();
        if (tailDiagnostic) {
            PostFancyMenuTailProfiler.markTitleOpen();
        }

        if (StartupProfiler.markMainMenu() && StartupProfiler.shouldExitOnTitle()) {
            if (tailDiagnostic && PostFancyMenuTailProfiler.hasActiveTrace()) {
                PostFancyMenuTailProfiler.requestExitAfterPresent();
            } else {
                Minecraft.getInstance().stop();
            }
        }
    }

    private static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            PostFancyMenuTailProfiler.markTitleRenderReturn();
        }
    }
}
