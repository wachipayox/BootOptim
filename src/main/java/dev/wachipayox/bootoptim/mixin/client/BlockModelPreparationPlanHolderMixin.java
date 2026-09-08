package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.ModelPreparationPlanHolder;
import dev.wachipayox.bootoptim.optimization.client.ModelPreparationPlanVerifier;
import net.minecraft.client.renderer.block.model.BlockModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Private verifier state only; the stock BlockModel API and publication remain untouched. */
@Mixin(BlockModel.class)
abstract class BlockModelPreparationPlanHolderMixin implements ModelPreparationPlanHolder {
    @Unique private boolean bootoptim$modelPreparationPlanCompiled;
    @Unique private boolean bootoptim$modelPreparationPlanPoisoned;
    @Unique private ModelPreparationPlanVerifier.Plan bootoptim$modelPreparationPlan;

    @Override
    public boolean bootoptim$modelPreparationPlanCompiled() {
        return bootoptim$modelPreparationPlanCompiled;
    }

    @Override
    public ModelPreparationPlanVerifier.Plan bootoptim$modelPreparationPlan() {
        return bootoptim$modelPreparationPlan;
    }

    @Override
    public void bootoptim$setModelPreparationPlan(ModelPreparationPlanVerifier.Plan plan) {
        bootoptim$modelPreparationPlan = plan;
    }

    @Override
    public void bootoptim$markModelPreparationPlanCompiled() {
        bootoptim$modelPreparationPlanCompiled = true;
    }

    @Override
    public boolean bootoptim$modelPreparationPlanPoisoned() {
        return bootoptim$modelPreparationPlanPoisoned;
    }

    @Override
    public void bootoptim$poisonModelPreparationPlan() {
        bootoptim$modelPreparationPlanPoisoned = true;
    }
}
