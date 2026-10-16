package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.optimization.client.EmfAnimationLineIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Opens the line-index scope while EMF binds ASM variables to animation lines; no line is added then. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.asm.ASMAnimationHandler", remap = false)
abstract class EmfAnimationScopeMixin {
    @WrapMethod(method = "finishAndValidate", require = 0, remap = false)
    private boolean bootoptim$lineIndexScope(Operation<Boolean> original) {
        Object previous = EmfAnimationLineIndex.open(this);
        try {
            return original.call();
        } finally {
            EmfAnimationLineIndex.close(previous);
        }
    }
}
