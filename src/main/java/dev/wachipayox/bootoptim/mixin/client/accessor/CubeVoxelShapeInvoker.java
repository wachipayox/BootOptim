package dev.wachipayox.bootoptim.mixin.client.accessor;

import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CubeVoxelShape.class)
public interface CubeVoxelShapeInvoker {
    @Invoker("<init>")
    static CubeVoxelShape bootoptim$create(DiscreteVoxelShape shape) {
        throw new AssertionError();
    }
}
