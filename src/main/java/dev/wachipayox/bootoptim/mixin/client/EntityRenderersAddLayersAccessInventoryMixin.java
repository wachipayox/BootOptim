package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EntityRendererReloadProfiler;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-only attribution of mutable/context state actually requested by AddLayers subscribers. */
@Mixin(value = EntityRenderersEvent.AddLayers.class, remap = false)
abstract class EntityRenderersAddLayersAccessInventoryMixin {
    @Inject(method = "getSkins", at = @At("HEAD"), require = 0)
    private void bootoptim$getSkins(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("skins");
    }

    @Inject(method = "getSkin", at = @At("HEAD"), require = 0)
    private void bootoptim$getSkin(PlayerSkin.Model model, CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("skin_renderer");
    }

    @Inject(method = "getEntityTypes", at = @At("HEAD"), require = 0)
    private void bootoptim$getEntityTypes(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("entity_types");
    }

    @Inject(method = "getRenderer", at = @At("HEAD"), require = 0)
    private void bootoptim$getRenderer(EntityType<?> type, CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("entity_renderer");
    }

    @Inject(method = "getEntityModels", at = @At("HEAD"), require = 0)
    private void bootoptim$getEntityModels(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("entity_models");
    }

    @Inject(method = "getContext", at = @At("HEAD"), require = 0)
    private void bootoptim$getContext(CallbackInfoReturnable<?> cir) {
        EntityRendererReloadProfiler.addLayersAccess("context");
    }
}
