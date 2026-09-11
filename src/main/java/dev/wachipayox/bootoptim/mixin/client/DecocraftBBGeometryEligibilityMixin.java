package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DecocraftEligibilityProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only Decocraft 3.0.11 natural availability/first-consumption markers. */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BBModelGeometryLoader$BBGeometry", remap = false)
abstract class DecocraftBBGeometryEligibilityMixin {
    @Unique private boolean bootoptim$eligibilityConsumed;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$geometryAvailable(CallbackInfo ci) {
        DecocraftEligibilityProbe.geometryAvailable();
    }

    @Inject(method = "bake", at = @At("HEAD"), require = 0)
    private void bootoptim$firstBaseConsume(CallbackInfoReturnable<Object> cir) {
        if (!bootoptim$eligibilityConsumed) {
            bootoptim$eligibilityConsumed = true;
            DecocraftEligibilityProbe.firstBaseConsume();
        }
    }
}
