package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.VoxelShaperBatchUnionExperiment;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional, experiment-only hook for Create 6.0.10 / Ponder 1.0.82 VoxelShaper. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.math.VoxelShaper", remap = false)
abstract class VoxelShaperBatchUnionMixin {
    @Inject(method = "rotatedCopy", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginBatchUnion(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShaperBatchUnionExperiment.begin(source, rotation);
    }

    @Redirect(
            method = "lambda$rotatedCopy$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;or(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    remap = false),
            require = 0)
    private static VoxelShape bootoptim$foldWithoutIntermediateOptimize(
            VoxelShape accumulator,
            VoxelShape rotatedBox) {
        return VoxelShaperBatchUnionExperiment.fold(accumulator, rotatedBox);
    }

    @Inject(method = "rotatedCopy", at = @At("RETURN"), cancellable = true, require = 0)
    private static void bootoptim$finishBatchUnion(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape original = cir.getReturnValue();
        VoxelShape result = VoxelShaperBatchUnionExperiment.finish(original);
        if (result != original) {
            cir.setReturnValue(result);
        }
    }
}
