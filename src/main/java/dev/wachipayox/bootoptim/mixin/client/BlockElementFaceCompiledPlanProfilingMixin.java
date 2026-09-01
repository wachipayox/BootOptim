package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CompiledElementsProfiler;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic attribution of NeoForge's inherited ExtraFaceData lookup. */
@Mixin(BlockElementFace.class)
abstract class BlockElementFaceCompiledPlanProfilingMixin {
    @Inject(method = "faceData", at = @At("HEAD"), require = 0)
    private void bootoptim$beginFaceData(CallbackInfoReturnable<ExtraFaceData> cir) {
        CompiledElementsProfiler.beginFaceData();
    }

    @Inject(method = "faceData", at = @At("RETURN"), require = 0)
    private void bootoptim$endFaceData(CallbackInfoReturnable<ExtraFaceData> cir) {
        CompiledElementsProfiler.endFaceData();
    }
}
