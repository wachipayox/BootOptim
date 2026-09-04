package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.RendererLayerRebakeProfiler;
import java.util.Map;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only timing of the provider-construction loop used by BlockEntityRenderDispatcher reload. */
@Mixin(BlockEntityRenderers.class)
abstract class BlockEntityRenderersCreateTimingMixin {
    @Inject(method = "createEntityRenderers", at = @At("HEAD"), require = 1)
    private static void bootoptim$beginCreate(
            BlockEntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<BlockEntityType<?>, BlockEntityRenderer<?>>> cir) {
        RendererLayerRebakeProfiler.beginPhase(RendererLayerRebakeProfiler.Phase.BLOCK_ENTITY_CREATE);
    }

    @Inject(method = "createEntityRenderers", at = @At("RETURN"), require = 1)
    private static void bootoptim$finishCreate(
            BlockEntityRendererProvider.Context context,
            CallbackInfoReturnable<Map<BlockEntityType<?>, BlockEntityRenderer<?>>> cir) {
        RendererLayerRebakeProfiler.endPhase(RendererLayerRebakeProfiler.Phase.BLOCK_ENTITY_CREATE);
    }
}
