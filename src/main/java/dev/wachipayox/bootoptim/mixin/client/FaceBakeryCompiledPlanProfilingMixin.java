package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CompiledElementsProfiler;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.FaceBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Times FaceBakery only while a sampled ElementsModel BlockModel.bakeFace call is active. */
@Mixin(FaceBakery.class)
abstract class FaceBakeryCompiledPlanProfilingMixin {
    @Inject(method = "bakeQuad", at = @At("HEAD"), require = 0)
    private void bootoptim$beginSampledFaceBakery(CallbackInfoReturnable<BakedQuad> cir) {
        CompiledElementsProfiler.beginFaceBakery();
    }

    @Inject(method = "bakeQuad", at = @At("RETURN"), require = 0)
    private void bootoptim$endSampledFaceBakery(CallbackInfoReturnable<BakedQuad> cir) {
        CompiledElementsProfiler.endFaceBakery();
    }
}
