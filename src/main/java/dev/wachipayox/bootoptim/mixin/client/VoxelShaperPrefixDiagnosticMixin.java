package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VoxelShaperPrefixDiagnostics;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: opens/closes a per-thread fold session around Ponder's {@code VoxelShaper.rotatedCopy}. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.math.VoxelShaper", remap = false)
abstract class VoxelShaperPrefixDiagnosticMixin {
    @Inject(method = "rotatedCopy", at = @At("HEAD"), require = 0)
    private static void bootoptim$callEnter(VoxelShape shape, Vec3 rotation, CallbackInfoReturnable<VoxelShape> cir) {
        if (VoxelShaperPrefixDiagnostics.ENABLED) {
            VoxelShaperPrefixDiagnostics.callEnter(shape);
        }
    }

    @Inject(method = "rotatedCopy", at = @At("RETURN"), require = 0)
    private static void bootoptim$callExit(VoxelShape shape, Vec3 rotation, CallbackInfoReturnable<VoxelShape> cir) {
        if (VoxelShaperPrefixDiagnostics.ENABLED) {
            VoxelShaperPrefixDiagnostics.callExit();
        }
    }
}
