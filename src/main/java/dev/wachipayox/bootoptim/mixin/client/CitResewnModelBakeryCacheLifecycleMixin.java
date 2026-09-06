package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.CitResewnItemModelCache;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Reload boundary for the optional CITResewn base-model cache. */
@Mixin(ModelBakery.class)
abstract class CitResewnModelBakeryCacheLifecycleMixin {
    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginCitResewnCache(BlockColors blockColors, ProfilerFiller profiler, Map<?, ?> blockModels, Map<?, ?> blockStates, CallbackInfo ci) {
        CitResewnItemModelCache.beginReload();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private static void bootoptim$reportCitResewnCache(BlockColors blockColors, ProfilerFiller profiler, Map<?, ?> blockModels, Map<?, ?> blockStates, CallbackInfo ci) {
        CitResewnItemModelCache.report();
    }
}
