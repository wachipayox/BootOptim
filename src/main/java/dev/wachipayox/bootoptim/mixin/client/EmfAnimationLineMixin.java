package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.EmfAnimationLineIndex;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EMF {@code getLine} scans every animation line by key; inside the binding scope, use an index. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationHandler", remap = false)
abstract class EmfAnimationLineMixin {
    @Shadow(remap = false) @Final private List<?> animLineDataList;

    @Inject(method = "getLine", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bootoptim$indexedLine(String key, CallbackInfoReturnable<Object> cir) {
        EmfAnimationLineIndex.Lookup lookup = EmfAnimationLineIndex.lookup(this, animLineDataList, key);
        if (lookup != null) {
            cir.setReturnValue(lookup.line());
        }
    }

    @Inject(method = "getLine", at = @At("RETURN"), require = 0, remap = false)
    private void bootoptim$checkLine(String key, CallbackInfoReturnable<Object> cir) {
        EmfAnimationLineIndex.check(cir.getReturnValue());
    }
}
