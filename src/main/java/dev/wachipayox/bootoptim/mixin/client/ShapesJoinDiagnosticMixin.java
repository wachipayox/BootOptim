package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.ShapeJoinDiagnostics;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: observes {@code Shapes.joinUnoptimized}; never changes its result. */
@Mixin(Shapes.class)
abstract class ShapesJoinDiagnosticMixin {
    @Inject(method = "joinUnoptimized", at = @At("HEAD"), require = 0)
    private static void bootoptim$joinEnter(VoxelShape a, VoxelShape b, BooleanOp op, CallbackInfoReturnable<VoxelShape> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.joinEnter(a, b, op);
        }
    }

    @Inject(method = "joinUnoptimized", at = @At("RETURN"), require = 0)
    private static void bootoptim$joinExit(VoxelShape a, VoxelShape b, BooleanOp op, CallbackInfoReturnable<VoxelShape> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.joinExit();
        }
    }
}
