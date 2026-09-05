package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.RendererLayerRebakeProfiler;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only timing around EntityModelSet.bakeLayer while a renderer reload scope is active. */
@Mixin(EntityModelSet.class)
abstract class EntityModelSetBakeTimingMixin {
    @Inject(method = "bakeLayer", at = @At("HEAD"), require = 1)
    private void bootoptim$beginLayerBake(
            ModelLayerLocation layer,
            CallbackInfoReturnable<ModelPart> cir) {
        RendererLayerRebakeProfiler.beginLayer(layer);
    }

    @Inject(method = "bakeLayer", at = @At("RETURN"), require = 1)
    private void bootoptim$finishLayerBake(
            ModelLayerLocation layer,
            CallbackInfoReturnable<ModelPart> cir) {
        RendererLayerRebakeProfiler.endLayer();
    }
}
