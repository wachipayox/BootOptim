package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.CompiledElementsBakePlan;
import dev.wachipayox.bootoptim.optimization.client.CompiledElementsPlanHolder;
import net.minecraft.client.renderer.block.model.BlockModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Experimental storage only; BlockModel lifetime naturally scopes the plan to one model reload. */
@Mixin(BlockModel.class)
public abstract class BlockModelCompiledElementsPlanMixin implements CompiledElementsPlanHolder {
    @Unique
    private volatile CompiledElementsBakePlan bootoptim$compiledElementsPlan;

    @Unique
    private boolean bootoptim$elementsBakeSeen;

    @Unique
    @Override
    public CompiledElementsBakePlan bootoptim$getCompiledElementsPlan() {
        return bootoptim$compiledElementsPlan;
    }

    @Unique
    @Override
    public void bootoptim$setCompiledElementsPlan(CompiledElementsBakePlan plan) {
        bootoptim$compiledElementsPlan = plan;
    }

    @Unique
    @Override
    public boolean bootoptim$hasSeenElementsBake() {
        return bootoptim$elementsBakeSeen;
    }

    @Unique
    @Override
    public void bootoptim$markElementsBakeSeen() {
        bootoptim$elementsBakeSeen = true;
    }
}
