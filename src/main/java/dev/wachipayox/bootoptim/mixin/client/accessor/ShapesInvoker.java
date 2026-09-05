package dev.wachipayox.bootoptim.mixin.client.accessor;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.IndexMerger;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Shapes.class)
public interface ShapesInvoker {
    @Invoker("createIndexMerger")
    static IndexMerger bootoptim$createIndexMerger(
            int size,
            DoubleList first,
            DoubleList second,
            boolean includeFirstOnly,
            boolean includeSecondOnly) {
        throw new AssertionError();
    }
}
