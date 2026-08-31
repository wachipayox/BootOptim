package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelStructureProfiler;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only wall timing for 1.21.1 BlockStateModelLoader. Variant counts move to the indexed matcher experiment. */
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
}
