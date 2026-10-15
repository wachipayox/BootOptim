package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.ShapeJoinDiagnostics;
import java.util.Arrays;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: scopes {@code CrossCollisionBlock.makeShapes}; never changes its result. */
@Mixin(CrossCollisionBlock.class)
abstract class CrossCollisionBlockShapeDiagnosticMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"), require = 0)
    private void bootoptim$enter(float a, float b, float c, float d, float e, CallbackInfoReturnable<VoxelShape[]> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            long bits = 17L;
            for (float v : new float[] {a, b, c, d, e}) {
                bits = bits * 1_000_003L + Float.floatToIntBits(v);
            }
            ShapeJoinDiagnostics.scopeEnter("cross", this, bits);
        }
    }

    @Inject(method = "makeShapes", at = @At("RETURN"), require = 0)
    private void bootoptim$exit(float a, float b, float c, float d, float e, CallbackInfoReturnable<VoxelShape[]> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.scopeExit(Arrays.asList(cir.getReturnValue()));
        }
    }
}
