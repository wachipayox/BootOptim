package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CampaignModelResidualProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelReloadSubphaseProfiler;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Coarse ModelBakery phase timings for the low-overhead startup scaling campaign. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryCampaignSubphaseMixin {
    @Unique
    private static final ThreadLocal<long[]> BOOTOPTIM$CONSTRUCTOR_START = ThreadLocal.withInitial(() -> new long[1]);
    @Unique
    private long bootoptim$bakeStart;

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$startConstructor(CallbackInfo ci) {
        if (ModelReloadSubphaseProfiler.enabled()) {
            BOOTOPTIM$CONSTRUCTOR_START.get()[0] = ModelReloadSubphaseProfiler.start();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$endConstructor(CallbackInfo ci) {
        long[] holder = BOOTOPTIM$CONSTRUCTOR_START.get();
        long started = holder[0];
        holder[0] = 0L;
        ModelReloadSubphaseProfiler.endSync("model_bakery_init", started);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$startBakeModels(CallbackInfo ci) {
        if (!ModelReloadSubphaseProfiler.enabled()) {
            return;
        }
        bootoptim$bakeStart = ModelReloadSubphaseProfiler.start();
        CampaignModelResidualProfiler.beginModelBake();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$endBakeModels(CallbackInfo ci) {
        CampaignModelResidualProfiler.finishModelBake();
        long started = bootoptim$bakeStart;
        bootoptim$bakeStart = 0L;
        ModelReloadSubphaseProfiler.endSync("bake_models", started);
    }
}
