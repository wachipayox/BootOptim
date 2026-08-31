package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelStructureProfiler;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/** Diagnostic-only counts for the variant-to-state scan inside the 1.21.1 BlockStateModelLoader. */
@Mixin(BlockStateModelLoader.class)
abstract class BlockStateModelLoaderStructureProfilingMixin {
    @Inject(method = "loadAllBlockStates", at = @At("HEAD"))
    private void bootoptim$startBlockStateLoad(CallbackInfo ci) {
        ModelStructureProfiler.beginBlockStates();
    }

    @Inject(method = "loadAllBlockStates", at = @At("RETURN"))
    private void bootoptim$endBlockStateLoad(CallbackInfo ci) {
        ModelStructureProfiler.endBlockStates();
    }

    @Inject(method = "predicate", at = @At("RETURN"), cancellable = true)
    private static void bootoptim$countVariantStateTests(
            StateDefinition<Block, BlockState> definition,
            String variant,
            CallbackInfoReturnable<Predicate<BlockState>> cir) {
        if (!ModelStructureProfiler.constructorActive()) {
            return;
        }

        Predicate<BlockState> original = cir.getReturnValue();
        ModelStructureProfiler.VariantCounter counter = ModelStructureProfiler.registerVariantPredicate();
        if (original == null || counter == null) {
            return;
        }

        cir.setReturnValue(state -> ModelStructureProfiler.testVariant(counter, original, state));
    }
}
