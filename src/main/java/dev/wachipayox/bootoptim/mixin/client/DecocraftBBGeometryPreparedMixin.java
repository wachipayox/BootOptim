package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftPreparedGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BBModelGeometryLoader$BBGeometry", remap = false)
abstract class DecocraftBBGeometryPreparedMixin {
    @Unique
    private Object bootoptim$preparedGeometry;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$prepare(CallbackInfo ci) {
        bootoptim$preparedGeometry = DecocraftPreparedGeometry.prepare(this);
    }

    @Inject(method = "bake", at = @At("HEAD"), require = 0)
    private void bootoptim$offerPrepared(CallbackInfoReturnable<Object> cir) {
        DecocraftPreparedGeometry.offer(bootoptim$preparedGeometry);
    }

    @Inject(method = "bake", at = @At("RETURN"), require = 0)
    private void bootoptim$clearPrepared(CallbackInfoReturnable<Object> cir) {
        DecocraftPreparedGeometry.clearHandoff();
    }
}
