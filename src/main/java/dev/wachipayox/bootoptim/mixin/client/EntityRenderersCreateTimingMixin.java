package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.RendererLayerRebakeProfiler;
import java.util.Map;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only split of entity and player provider-construction loops during dispatcher reload. */
@Mixin(EntityRenderers.class)
abstract class EntityRenderersCreateTimingMixin {
    @Inject(method = "createEntityRenderers", at = @At("HEAD"), require = 1)
    private static void bootoptim$beginEntityCreate(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<EntityType<?>, EntityRenderer<?>>> cir) {
        RendererLayerRebakeProfiler.beginPhase(RendererLayerRebakeProfiler.Phase.ENTITY_CREATE);
    }

    @Inject(method = "createEntityRenderers", at = @At("RETURN"), require = 1)
    private static void bootoptim$finishEntityCreate(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<EntityType<?>, EntityRenderer<?>>> cir) {
        RendererLayerRebakeProfiler.endPhase(RendererLayerRebakeProfiler.Phase.ENTITY_CREATE);
    }

    @Inject(method = "createPlayerRenderers", at = @At("HEAD"), require = 1)
    private static void bootoptim$beginPlayerCreate(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<PlayerSkin.Model, EntityRenderer<? extends Player>>> cir) {
        RendererLayerRebakeProfiler.beginPhase(RendererLayerRebakeProfiler.Phase.PLAYER_CREATE);
    }

    @Inject(method = "createPlayerRenderers", at = @At("RETURN"), require = 1)
    private static void bootoptim$finishPlayerCreate(
            EntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<PlayerSkin.Model, EntityRenderer<? extends Player>>> cir) {
        RendererLayerRebakeProfiler.endPhase(RendererLayerRebakeProfiler.Phase.PLAYER_CREATE);
    }
}
