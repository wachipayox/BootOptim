package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.BakePlanCensusProfiler;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds root identity around the unchanged serial top-level bake traversal. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryBakePlanCensusMixin {
    @Inject(method = "bakeModels", at = @At("HEAD"), require = 0)
    private void bootoptim$bakePlanCensusHead(CallbackInfo ci) {
        BakePlanCensusProfiler.beginBakeModels();
    }

    @Redirect(
            method = "bakeModels",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"),
            require = 0)
    private void bootoptim$observeTopLevelRoots(
            Map<ModelResourceLocation, UnbakedModel> models,
            BiConsumer<ModelResourceLocation, UnbakedModel> action) {
        if (!BakePlanCensusProfiler.enabled()) {
            models.forEach(action);
            return;
        }
        models.forEach((location, model) -> {
            BakePlanCensusProfiler.beginRoot(location);
            try {
                action.accept(location, model);
            } finally {
                BakePlanCensusProfiler.endRoot();
            }
        });
    }

    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void bootoptim$bakePlanCensusReturn(CallbackInfo ci) {
        BakePlanCensusProfiler.finishBakeModels();
    }
}
