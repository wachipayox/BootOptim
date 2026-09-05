package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShaperExactReuseProfiler;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only exact-input reuse audit for Ponder 1.0.82 VoxelShaper. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.math.VoxelShaper", remap = false)
abstract class VoxelShaperExactReuseProfilerMixin {
    @Inject(method = "rotatedCopy", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginExactReuseAudit(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShaperExactReuseProfiler.begin(source, rotation);
    }

    @Inject(method = "rotatedCopy", at = @At("RETURN"), require = 0)
    private static void bootoptim$finishExactReuseAudit(
            VoxelShape source,
            Vec3 rotation,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShaperExactReuseProfiler.finish(cir.getReturnValue());
    }
}
