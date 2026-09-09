package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.FlipFrameEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only startup probes for the legacy profiler and structured startup trace. */
public final class ClientStartupHooks {
    private static boolean installed;

    private ClientStartupHooks() {
    }

    public static void install() {
        if ((!StartupProfiler.isEnabled() && !ResourceReloadDagTrace.enabled()
                && !PostReloadMenuTrace.awaitPresentedEndpoint()) || installed) {
            return;
        }

        installed = true;
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onRenderFramePost);
        NeoForge.EVENT_BUS.addListener(ClientStartupHooks::onFlipFrame);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }

        boolean awaitPresented = PostReloadMenuTrace.awaitPresentedEndpoint();
        if (awaitPresented) {
            PostReloadMenuTrace.markTitleOpening();
        } else {
            ResourceReloadDagTrace.markMainMenuEndpoint();
        }

        if (StartupProfiler.isEnabled()
                && StartupProfiler.markMainMenu()
                && StartupProfiler.shouldExitOnTitle()
                && !awaitPresented) {
            Minecraft.getInstance().stop();
        }
    }

    private static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            PostReloadMenuTrace.markTitleInitPost();
        }
    }

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (Minecraft.getInstance().screen instanceof TitleScreen) {
            PostReloadMenuTrace.markTitleRenderReturn();
        }
    }

    private static void onFlipFrame(FlipFrameEvent event) {
        PostReloadMenuTrace.markPresentedAfterFlip();
    }
}
