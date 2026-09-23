package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.Map;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.IModBusEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuses #190's three stock callsites, without provider inventory or stack sampling. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRendererVarianceSplitMixin {
    @Redirect(method = "onResourceManagerReload", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createEntityRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"))
    private Map<EntityType<?>, EntityRenderer<?>> bootoptim$entities(EntityRendererProvider.Context context) {
        var stamp = VarianceProbe.start("entity_provider_create");
        try { return EntityRenderers.createEntityRenderers(context); }
        finally { VarianceProbe.finish("entity_provider_create", stamp); }
    }

    @Redirect(method = "onResourceManagerReload", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createPlayerRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"))
    private Map<PlayerSkin.Model, EntityRenderer<? extends Player>> bootoptim$players(EntityRendererProvider.Context context) {
        var stamp = VarianceProbe.start("player_provider_create");
        try { return EntityRenderers.createPlayerRenderers(context); }
        finally { VarianceProbe.finish("player_provider_create", stamp); }
    }

    @Redirect(method = "onResourceManagerReload", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/ModLoader;postEvent(Lnet/neoforged/bus/api/Event;)V", remap = false))
    private void bootoptim$addLayers(Event event) {
        var stamp = VarianceProbe.start("entity_add_layers_post");
        try { ModLoader.postEvent((Event & IModBusEvent) event); }
        finally { VarianceProbe.finish("entity_add_layers_post", stamp); }
    }
}
