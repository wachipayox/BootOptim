package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.IndexedBlockStateVariantMatcher;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;
import java.util.stream.Stream;

/** Experimental indexed replacement for the variant Stream.filter inside 1.21.1 BlockStateModelLoader. */
@Mixin(BlockStateModelLoader.class)
abstract class BlockStateModelLoaderIndexedVariantMixin {
    @Inject(method = "loadAllBlockStates", at = @At("HEAD"), require = 0)
    private void bootoptim$beginIndexedMatching(CallbackInfo ci) {
        IndexedBlockStateVariantMatcher.beginLoadAll();
    }

    @Inject(method = "loadAllBlockStates", at = @At("RETURN"), require = 0)
    private void bootoptim$finishIndexedMatching(CallbackInfo ci) {
        IndexedBlockStateVariantMatcher.finishLoadAll();
    }

    @Inject(method = "predicate", at = @At("RETURN"), cancellable = true, require = 0)
    private static void bootoptim$wrapValidatedPredicate(
            StateDefinition<Block, BlockState> definition,
            String variant,
            CallbackInfoReturnable<Predicate<BlockState>> cir) {
        Predicate<BlockState> stock = cir.getReturnValue();
        if (stock != null) {
            cir.setReturnValue(IndexedBlockStateVariantMatcher.wrapValidatedPredicate(definition, variant, stock));
        }
    }

    @Redirect(
            method = "lambda$loadBlockStateDefinitions$8",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"),
            require = 0)
    private Stream<BlockState> bootoptim$useIndexedMatches(
            Stream<BlockState> stockStream,
            Predicate<BlockState> predicate) {
        return IndexedBlockStateVariantMatcher.filterVariantStates(stockStream, predicate);
    }
}
