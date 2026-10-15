package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.BlockShapeOrMemo;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replays {@code Shapes.or} results of {@code PipeBlock.makeShapes}; see {@link BlockShapeOrMemo}. */
@Mixin(PipeBlock.class)
abstract class PipeBlockShapeOrMemoMixin {
    @Inject(method = "makeShapes", at = @At("HEAD"), require = 0)
    private void bootoptim$enterShapes(float apothem, CallbackInfoReturnable<VoxelShape[]> cir) {
        BlockShapeOrMemo.enter("pipe", this, Float.floatToIntBits(apothem));
    }

    @Inject(method = "makeShapes", at = @At("RETURN"), require = 0)
    private void bootoptim$exitShapes(float apothem, CallbackInfoReturnable<VoxelShape[]> cir) {
        BlockShapeOrMemo.exit();
    }

    @WrapOperation(method = "makeShapes", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;or("
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)"
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"), require = 0)
    private VoxelShape bootoptim$orInMake(VoxelShape a, VoxelShape b, Operation<VoxelShape> original) {
        return BlockShapeOrMemo.or(a, b, original);
    }
}
