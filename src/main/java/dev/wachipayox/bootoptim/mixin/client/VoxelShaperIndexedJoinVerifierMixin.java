package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShaperIndexedJoinVerifier;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Diagnostic-only verifier for Ponder 1.0.82 VoxelShaper per-box OR folds. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.math.VoxelShaper", remap = false)
abstract class VoxelShaperIndexedJoinVerifierMixin {
    @Redirect(
            method = "lambda$rotatedCopy$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;or(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    remap = false),
            require = 0)
    private static VoxelShape bootoptim$verifyIndexedJoin(VoxelShape accumulator, VoxelShape rotatedBox) {
        return VoxelShaperIndexedJoinVerifier.fold(accumulator, rotatedBox);
    }
}
