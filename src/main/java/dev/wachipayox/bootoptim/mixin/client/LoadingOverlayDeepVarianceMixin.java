package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.LoadingOverlayDeepProfiler;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe manual reload overlay frames, stock isDone/fade transition and render gaps. */
@Mixin(LoadingOverlay.class)
abstract class LoadingOverlayDeepVarianceMixin {
    @Shadow private ReloadInstance reload;
    @Shadow private long fadeOutStart;

    @Inject(method = "render", at = @At("HEAD"))
    private void bootoptim$overlayRenderBegin(GuiGraphics graphics, int x, int y, float partialTick, CallbackInfo ci) {
        if (!VarianceProbe.enabled()) return;
        LoadingOverlayDeepProfiler.renderBegin((LoadingOverlay) (Object) this, reload.isDone(), fadeOutStart);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void bootoptim$overlayRenderEnd(GuiGraphics graphics, int x, int y, float partialTick, CallbackInfo ci) {
        if (!VarianceProbe.enabled()) return;
        LoadingOverlayDeepProfiler.renderEnd((LoadingOverlay) (Object) this, fadeOutStart);
    }
}
