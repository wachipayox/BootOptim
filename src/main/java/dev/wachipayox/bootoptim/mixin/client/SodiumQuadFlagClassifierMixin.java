package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.QuadCoordinateView;
import dev.wachipayox.bootoptim.optimization.client.SodiumQuadFlagClassifier;
import dev.wachipayox.bootoptim.optimization.client.SodiumQuadFlagSupport;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags", remap = false)
public abstract class SodiumQuadFlagClassifierMixin {
    @Unique
    private static final boolean BOOTOPTIM_AXIS_FLAGS_REQUESTED = Boolean.parseBoolean(
            System.getProperty("boot_optim.sodiumAxisQuadFlags", "true"));

    @Inject(method = "getQuadFlags", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$classify(@Coerce Object quad, Direction face,
                                           CallbackInfoReturnable<Integer> result) {
        if (!bootoptim$eligible(quad, face) || !SodiumQuadFlagSupport.replaceThisCall()) {
            return;
        }
        result.setReturnValue(SodiumQuadFlagClassifier.classify((QuadCoordinateView) quad, face.ordinal()));
    }

    @Inject(method = "getQuadFlags", at = @At("RETURN"), require = 0)
    private static void bootoptim$verify(@Coerce Object quad, Direction face,
                                         CallbackInfoReturnable<Integer> result) {
        if (SodiumQuadFlagSupport.verifying() && bootoptim$eligible(quad, face)) {
            SodiumQuadFlagSupport.verify(result.getReturnValueI(),
                    SodiumQuadFlagClassifier.classify((QuadCoordinateView) quad, face.ordinal()), face.ordinal());
        }
    }

    @Unique
    private static boolean bootoptim$eligible(Object quad, Direction face) {
        return BOOTOPTIM_AXIS_FLAGS_REQUESTED && face != null && quad != null && quad.getClass() == BakedQuad.class
                && quad instanceof QuadCoordinateView;
    }
}
