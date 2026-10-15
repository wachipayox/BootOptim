package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.ShapeJoinDiagnostics;
import java.util.Arrays;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: scopes {@code PipeBlock.makeShapes}; never changes its result. */
@Mixin(PipeBlock.class)
abstract class PipeBlockShapeDiagnosticMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"), require = 0)
    private void bootoptim$enter(float apothem, CallbackInfoReturnable<VoxelShape[]> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.scopeEnter("pipe", this, Float.floatToIntBits(apothem));
        }
    }

    @Inject(method = "makeShapes", at = @At("RETURN"), require = 0)
    private void bootoptim$exit(float apothem, CallbackInfoReturnable<VoxelShape[]> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.scopeExit(Arrays.asList(cir.getReturnValue()));
        }
    }
}
