package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.StrictElementsBakePlan;
import dev.wachipayox.bootoptim.optimization.client.StrictElementsBakePlanHolder;
import net.minecraft.client.renderer.block.model.BlockModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockModel.class)
abstract class BlockModelStrictElementsPlanMixin implements StrictElementsBakePlanHolder {
    @Unique
    private StrictElementsBakePlan bootoptim$strictElementsBakePlan;

    @Unique
    private boolean bootoptim$strictElementsPlanCompiled;

    @Override
    public StrictElementsBakePlan bootoptim$getStrictElementsBakePlan() {
        if (!bootoptim$strictElementsPlanCompiled) {
            bootoptim$strictElementsPlanCompiled = true;
            bootoptim$strictElementsBakePlan = StrictElementsBakePlan.compile((BlockModel) (Object) this);
        }
        return bootoptim$strictElementsBakePlan;
    }
}
