package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only sampled caller attribution around the existing vanilla/Lithium join path. */
@Mixin(Shapes.class)
abstract class ShapesVoxelShapeProfilingMixin {
    @Inject(method = "joinUnoptimized", at = @At("HEAD"))
    private static void bootoptim$beginJoin(
            VoxelShape first,
            VoxelShape second,
            BooleanOp operator,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShapeStartupProfiler.beginJoin(first, second, operator);
    }

    @Inject(method = "joinUnoptimized", at = @At("RETURN"))
    private static void bootoptim$endJoin(
            VoxelShape first,
            VoxelShape second,
            BooleanOp operator,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShapeStartupProfiler.endJoin(first, second, cir.getReturnValue());
    }
}
