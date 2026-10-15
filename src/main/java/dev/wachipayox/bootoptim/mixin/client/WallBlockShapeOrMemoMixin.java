package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.BlockShapeOrMemo;
import java.util.Map;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replays {@code Shapes.or} results of {@code WallBlock.makeShapes}; see {@link BlockShapeOrMemo}. */
@Mixin(WallBlock.class)
abstract class WallBlockShapeOrMemoMixin {
    private static final String SHAPES_OR = "Lnet/minecraft/world/phys/shapes/Shapes;or("
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)"
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;";

    @Inject(method = "makeShapes", at = @At("HEAD"), require = 0)
    private void bootoptim$enterShapes(float a, float b, float c, float d, float e, float f,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        BlockShapeOrMemo.enter("wall", this, Float.floatToIntBits(a), Float.floatToIntBits(b), Float.floatToIntBits(c),
                Float.floatToIntBits(d), Float.floatToIntBits(e), Float.floatToIntBits(f));
    }

    @Inject(method = "makeShapes", at = @At("RETURN"), require = 0)
    private void bootoptim$exitShapes(float a, float b, float c, float d, float e, float f,
            CallbackInfoReturnable<Map<BlockState, VoxelShape>> cir) {
        BlockShapeOrMemo.exit();
    }

    @WrapOperation(method = "makeShapes", at = @At(value = "INVOKE", target = SHAPES_OR), require = 0)
    private VoxelShape bootoptim$orInMake(VoxelShape a, VoxelShape b, Operation<VoxelShape> original) {
        return BlockShapeOrMemo.or(a, b, original);
    }

    @WrapOperation(method = "applyWallShape", at = @At(value = "INVOKE", target = SHAPES_OR), require = 0)
    private static VoxelShape bootoptim$orInApply(VoxelShape a, VoxelShape b, Operation<VoxelShape> original) {
        return BlockShapeOrMemo.or(a, b, original);
    }
}
