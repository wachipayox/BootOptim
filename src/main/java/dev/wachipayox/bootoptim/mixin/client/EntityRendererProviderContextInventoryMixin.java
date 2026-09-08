package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EntityRendererReloadProfiler;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts Context dependencies used by provider construction and AddLayers subscribers. */
@Mixin(EntityRendererProvider.Context.class)
abstract class EntityRendererProviderContextInventoryMixin {
    @Inject(method = "getResourceManager", at = @At("HEAD"), require = 0)
    private void bootoptim$resourceManager(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("resource_manager");
    }

    @Inject(method = "getModelManager", at = @At("HEAD"), require = 0)
    private void bootoptim$modelManager(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("model_manager");
    }

    @Inject(method = "getModelSet", at = @At("HEAD"), require = 0)
    private void bootoptim$modelSet(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("model_set");
    }

    @Inject(method = "bakeLayer", at = @At("HEAD"), require = 0)
    private void bootoptim$bakeLayer(ModelLayerLocation layer, CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("bake_layer");
    }

    @Inject(method = "getEntityRenderDispatcher", at = @At("HEAD"), require = 0)
    private void bootoptim$entityDispatcher(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("entity_dispatcher");
    }

    @Inject(method = "getItemRenderer", at = @At("HEAD"), require = 0)
    private void bootoptim$itemRenderer(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("item_renderer");
    }

    @Inject(method = "getBlockRenderDispatcher", at = @At("HEAD"), require = 0)
    private void bootoptim$blockDispatcher(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("block_dispatcher");
    }

    @Inject(method = "getItemInHandRenderer", at = @At("HEAD"), require = 0)
    private void bootoptim$itemInHandRenderer(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("item_in_hand_renderer");
    }

    @Inject(method = "getFont", at = @At("HEAD"), require = 0)
    private void bootoptim$font(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.contextAccess("font");
    }
}
