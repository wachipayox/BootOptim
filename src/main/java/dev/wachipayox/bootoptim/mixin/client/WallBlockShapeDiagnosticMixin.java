package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.ShapeJoinDiagnostics;
import java.util.Map;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DIAGNOSTIC ONLY: scopes {@code WallBlock.makeShapes}; never changes its result. */
@Mixin(WallBlock.class)
abstract class WallBlockShapeDiagnosticMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"), require = 0)
    private void bootoptim$enter(float a, float b, float c, float d, float e, float f,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            long bits = 17L;
            for (float v : new float[] {a, b, c, d, e, f}) {
                bits = bits * 1_000_003L + Float.floatToIntBits(v);
            }
            ShapeJoinDiagnostics.scopeEnter("wall", this, bits);
        }
    }

    @Inject(method = "makeShapes", at = @At("RETURN"), require = 0)
    private void bootoptim$exit(float a, float b, float c, float d, float e, float f,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        if (ShapeJoinDiagnostics.ENABLED) {
            ShapeJoinDiagnostics.scopeExit(cir.getReturnValue().values());
        }
    }
}
