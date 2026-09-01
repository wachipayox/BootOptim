package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CompiledElementsFlightRecorder;
import dev.wachipayox.bootoptim.profiling.client.CompiledElementsProfiler;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only lifecycle gate for the compiled-elements profiler. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryCompiledPlanProfilingMixin {
    @Inject(method = "bakeModels", at = @At("HEAD"), require = 0)
    private void bootoptim$beginCompiledElementsProfile(CallbackInfo ci) {
        CompiledElementsFlightRecorder.start();
        CompiledElementsProfiler.begin();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void bootoptim$finishCompiledElementsProfile(CallbackInfo ci) {
        CompiledElementsProfiler.finish();
        CompiledElementsFlightRecorder.finish();
    }
}
