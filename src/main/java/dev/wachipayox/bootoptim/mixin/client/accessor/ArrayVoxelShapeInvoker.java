package dev.wachipayox.bootoptim.mixin.client.accessor;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ArrayVoxelShape.class)
public interface ArrayVoxelShapeInvoker {
    @Invoker("<init>")
    static ArrayVoxelShape bootoptim$create(
            DiscreteVoxelShape shape, DoubleList x, DoubleList y, DoubleList z) {
        throw new AssertionError();
    }
}
