package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler;
import java.util.function.Function;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact attribution for generic immutable per-state shape tables built during block construction. */
@Mixin(Block.class)
abstract class BlockShapeTableProfilingMixin {
    @Inject(method = "getShapeForEachState", at = @At("HEAD"))
    private void bootoptim$beginShapeTable(
            Function<BlockState, VoxelShape> shapeGetter,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.beginStateShapeTable((Block) (Object) this);
    }

    @Inject(method = "getShapeForEachState", at = @At("RETURN"))
    private void bootoptim$endShapeTable(
            Function<BlockState, VoxelShape> shapeGetter,
            CallbackInfoReturnable<?> cir) {
        VoxelShapeStartupProfiler.endStateShapeTable(cir.getReturnValue());
    }
}
