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
        if (!BOOTOPTIM_AXIS_FLAGS_REQUESTED || face == null || quad == null || quad.getClass() != BakedQuad.class
                || !(quad instanceof QuadCoordinateView view) || !SodiumQuadFlagSupport.supported()) {
            return;
        }
        result.setReturnValue(SodiumQuadFlagClassifier.classify(view, face.ordinal()));
    }

}
