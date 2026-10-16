package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.StateShapeMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Memoizes {@code getShape} per block state; see {@link StateShapeMemo}. */
@Pseudo
@Mixin(targets = "dev.averageanime.block.type.pie.PizzaBlock", remap = false)
abstract class PizzaShapeMemoMixin {
    private static final String GET_SHAPE = "getShape(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;";

    @Inject(method = GET_SHAPE, at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$memoized(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape cached = StateShapeMemo.PIZZA.lookup(state);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = GET_SHAPE, at = @At("RETURN"), require = 0)
    private void bootoptim$record(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir) {
        StateShapeMemo.PIZZA.record(state, cir.getReturnValue());
    }
}
