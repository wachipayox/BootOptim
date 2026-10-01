package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.FerriteQuadHashArithmetic;
import dev.wachipayox.bootoptim.optimization.client.FerriteQuadHashTrial;
import dev.wachipayox.bootoptim.optimization.client.FerriteHashReplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.Deduplicator", remap = false)
public abstract class FerriteQuadHashArithmeticMixin {
    @Inject(method = "betterIntArrayHash([I)I", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$hash(int[] values, CallbackInfoReturnable<Integer> result) {
        FerriteHashReplay.capture(values);
        if (!FerriteQuadHashTrial.VERIFY && FerriteQuadHashTrial.eligible(values) && FerriteQuadHashTrial.selected())
            result.setReturnValue(FerriteQuadHashArithmetic.hash(values));
    }

    @Inject(method = "betterIntArrayHash([I)I", at = @At("RETURN"), require = 0)
    private static void bootoptim$verify(int[] values, CallbackInfoReturnable<Integer> result) {
        if (FerriteQuadHashTrial.VERIFY && FerriteQuadHashTrial.eligible(values))
            FerriteQuadHashTrial.verify(FerriteQuadHashArithmetic.hash(values), result.getReturnValueI());
    }
}
