package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.EmfAnimationText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EMF animation key/expression clean-up: skip the {@code this}/{@code part} regexes when the words are absent. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.jem_objects.EMFJemData", remap = false)
abstract class EmfAnimationTextMixin {
    @Inject(method = "processAnimAndKeyString", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void bootoptim$prepared(@Coerce Object part, String anim, CallbackInfoReturnable<String> cir) {
        String fast = EmfAnimationText.process(part, anim);
        if (fast != null) {
            cir.setReturnValue(fast);
        }
    }

    @Inject(method = "processAnimAndKeyString", at = @At("RETURN"), require = 0, remap = false)
    private static void bootoptim$check(@Coerce Object part, String anim, CallbackInfoReturnable<String> cir) {
        EmfAnimationText.check(cir.getReturnValue());
    }
}
