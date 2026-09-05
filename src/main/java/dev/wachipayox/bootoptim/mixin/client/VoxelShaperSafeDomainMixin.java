package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShaperSafeDomainDiagnostic;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only hook for Create 6.0.10 / Ponder 1.0.82 VoxelShaper. Always returns stock. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.math.VoxelShaper", remap = false)
abstract class VoxelShaperSafeDomainMixin {
    @Inject(method = "rotatedCopy", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginSafeDomain(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShaperSafeDomainDiagnostic.begin(source, rotation);
    }

    @Redirect(
            method = "lambda$rotatedCopy$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;or(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    remap = false),
            require = 0)
    private static VoxelShape bootoptim$observeStockFold(
            VoxelShape accumulator,
            VoxelShape rotatedBox) {
        return VoxelShaperSafeDomainDiagnostic.fold(accumulator, rotatedBox);
    }

    @Inject(method = "rotatedCopy", at = @At("RETURN"), require = 0)
    private static void bootoptim$finishSafeDomain(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShaperSafeDomainDiagnostic.finish(cir.getReturnValue());
    }
}
