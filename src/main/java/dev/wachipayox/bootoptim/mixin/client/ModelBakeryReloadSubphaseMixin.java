package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DeepModelBakeProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelReloadSubphaseProfiler;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.BiConsumer;

/** Diagnostic-only timings for ModelBakery dependency construction and the actual bake pass. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryReloadSubphaseMixin {
    @Unique
    private static final ThreadLocal<Long> bootoptim$constructorStart = ThreadLocal.withInitial(() -> -1L);
    @Unique
    private long bootoptim$bakeStart = -1L;

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$startConstructor(CallbackInfo ci) {
        if (ModelReloadSubphaseProfiler.enabled()) {
            bootoptim$constructorStart.set(ModelReloadSubphaseProfiler.start());
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$endConstructor(CallbackInfo ci) {
        long started = bootoptim$constructorStart.get();
        bootoptim$constructorStart.remove();
        ModelReloadSubphaseProfiler.endSync("model_bakery_init", started);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$startBakeModels(CallbackInfo ci) {
        if (ModelReloadSubphaseProfiler.enabled()) {
            bootoptim$bakeStart = ModelReloadSubphaseProfiler.start();
            DeepModelBakeProfiler.begin();
        }
    }

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void bootoptim$profileTopLevelBakeLoop(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> bakeAction) {
        DeepModelBakeProfiler.profileTopLevelLoop(models, bakeAction);
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$endBakeModels(CallbackInfo ci) {
        DeepModelBakeProfiler.finish();
        long started = bootoptim$bakeStart;
        bootoptim$bakeStart = -1L;
        ModelReloadSubphaseProfiler.endSync("bake_models", started);
    }
}
