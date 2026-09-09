package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftQuaternionTrigFastPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.razz.decocraft.models.libgdx.Quaternion", remap = false)
abstract class DecocraftQuaternionTrigFastPathMixin {
    @Redirect(
        method = "setFromAxisRad(FFFF)Lcom/razz/decocraft/models/libgdx/Quaternion;",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;sin(D)D", remap = false),
        require = 0,
        remap = false
    )
    private double bootoptim$reuseKnownSin(double input) {
        return DecocraftQuaternionTrigFastPath.sin(input);
    }

    @Redirect(
        method = "setFromAxisRad(FFFF)Lcom/razz/decocraft/models/libgdx/Quaternion;",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;cos(D)D", remap = false),
        require = 0,
        remap = false
    )
    private double bootoptim$reuseKnownCos(double input) {
        return DecocraftQuaternionTrigFastPath.cos(input);
    }
}
