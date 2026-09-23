package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the stock resource-pack UI reload request, returned future and loading-overlay exit. */
@Mixin(Minecraft.class)
abstract class MinecraftManualReloadVarianceMixin {
    @Shadow private Overlay overlay;
    @Unique private VarianceProbe.Stamp bootoptim$manualReloadStart;

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void bootoptim$manualReloadStart(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        bootoptim$manualReloadStart = VarianceProbe.start("manual_resource_pack_reload");
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void bootoptim$manualReloadReturned(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        VarianceProbe.Stamp started = bootoptim$manualReloadStart;
        bootoptim$manualReloadStart = null;
        ResourceReloadBoundaryProfiler.observeFuture("manual_resource_pack_reload", started, cir.getReturnValue());
    }

    @Inject(method = "setOverlay", at = @At("HEAD"))
    private void bootoptim$loadingOverlayExit(Overlay next, CallbackInfo ci) {
        if (VarianceProbe.enabled() && overlay instanceof LoadingOverlay && !(next instanceof LoadingOverlay)) {
            VarianceProbe.point("loading_overlay_exit_call", next == null ? "none" : next.getClass().getName());
        }
    }
}
