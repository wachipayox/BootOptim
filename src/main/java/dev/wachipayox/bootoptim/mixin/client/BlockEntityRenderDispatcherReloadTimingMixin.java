package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.RendererLayerRebakeProfiler;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only scope for the first block-entity renderer reconstruction during resource reload. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherReloadTimingMixin {
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), require = 1)
    private void bootoptim$beginRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        RendererLayerRebakeProfiler.begin(RendererLayerRebakeProfiler.Scope.BLOCK_ENTITY);
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 1)
    private void bootoptim$finishRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        RendererLayerRebakeProfiler.end(RendererLayerRebakeProfiler.Scope.BLOCK_ENTITY);
    }
}
