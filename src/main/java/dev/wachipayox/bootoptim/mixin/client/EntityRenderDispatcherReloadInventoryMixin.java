package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EntityRendererReloadProfiler;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only decomposition of EntityRenderDispatcher.onResourceManagerReload. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherReloadInventoryMixin {
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), require = 1)
    private void bootoptim$beginEntityRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        EntityRendererReloadProfiler.beginReload();
        if (EntityRendererReloadProfiler.enabled()) {
            EntityRendererReloadProfiler.snapshotProviders(
                    EntityRenderersAccessor.bootoptim$getProviders(),
                    EntityRenderersAccessor.bootoptim$getPlayerProviders());
        }
    }

    @Redirect(
            method = "onResourceManagerReload",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createEntityRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"),
            require = 1)
    private Map<EntityType<?>, EntityRenderer<?>> bootoptim$timeEntityProviders(EntityRendererProvider.Context context) {
        return EntityRendererReloadProfiler.time("entity_provider_create", () -> EntityRenderers.createEntityRenderers(context));
    }

    @Redirect(
            method = "onResourceManagerReload",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createPlayerRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"),
            require = 1)
    private Map<PlayerSkin.Model, EntityRenderer<? extends Player>> bootoptim$timePlayerProviders(EntityRendererProvider.Context context) {
        return EntityRendererReloadProfiler.time("player_provider_create", () -> EntityRenderers.createPlayerRenderers(context));
    }

    @Redirect(
            method = "onResourceManagerReload",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/ModLoader;postEvent(Lnet/neoforged/bus/api/Event;)V", remap = false),
            require = 0)
    private void bootoptim$timeAddLayersPost(Event event) {
        EntityRendererReloadProfiler.timeVoid("add_layers_post", () -> ModLoader.postEvent(event));
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 1)
    private void bootoptim$finishEntityRendererReload(ResourceManager resourceManager, CallbackInfo ci) {
        EntityRendererReloadProfiler.finishReload();
    }
}
