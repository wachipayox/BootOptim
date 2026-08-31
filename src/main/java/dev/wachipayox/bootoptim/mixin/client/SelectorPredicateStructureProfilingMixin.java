package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelStructureProfiler;
import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/** Diagnostic-only timing for repeated multipart selector predicate construction. */
@Mixin(Selector.class)
abstract class SelectorPredicateStructureProfilingMixin {
    @Unique
    private long bootoptim$predicateStart = -1L;

    @Inject(method = "getPredicate", at = @At("HEAD"))
    private void bootoptim$startPredicate(
            StateDefinition<Block, BlockState> definition,
            CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (ModelStructureProfiler.selectorProfilingActive()) {
            bootoptim$predicateStart = System.nanoTime();
        }
    }

    @Inject(method = "getPredicate", at = @At("RETURN"))
    private void bootoptim$endPredicate(
            StateDefinition<Block, BlockState> definition,
            CallbackInfoReturnable<Predicate<BlockState>> cir) {
        long started = bootoptim$predicateStart;
        bootoptim$predicateStart = -1L;
        if (started > 0L) {
            ModelStructureProfiler.recordSelectorPredicate(System.nanoTime() - started);
        }
    }
}
