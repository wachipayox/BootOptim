package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadSubphaseProfiler;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only timings for ModelBakery dependency construction and the actual bake pass. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryReloadSubphaseMixin {
    @Unique
    private long bootoptim$constructorStart = -1L;
    @Unique
    private long bootoptim$bakeStart = -1L;

    @Inject(method = "<init>", at = @At("HEAD"))
    private void bootoptim$startConstructor(CallbackInfo ci) {
        if (ModelReloadSubphaseProfiler.enabled()) {
            bootoptim$constructorStart = ModelReloadSubphaseProfiler.start();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$endConstructor(CallbackInfo ci) {
        long started = bootoptim$constructorStart;
        bootoptim$constructorStart = -1L;
        ModelReloadSubphaseProfiler.endSync("model_bakery_init", started);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$startBakeModels(CallbackInfo ci) {
        if (ModelReloadSubphaseProfiler.enabled()) {
            bootoptim$bakeStart = ModelReloadSubphaseProfiler.start();
        }
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$endBakeModels(CallbackInfo ci) {
        long started = bootoptim$bakeStart;
        bootoptim$bakeStart = -1L;
        ModelReloadSubphaseProfiler.endSync("bake_models", started);
    }
}
