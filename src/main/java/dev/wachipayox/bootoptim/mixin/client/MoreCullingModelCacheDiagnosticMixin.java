package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.MoreCullingStartupDiagnostics;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes MoreCulling's stock-model translucency-cache rebuilds.
 *
 * <p>Coverage is reported against the non-occluding-state count from the preceding MoreCulling
 * shape-cache pass. Custom BakedModel implementations are intentionally not treated as covered.</p>
 */
@Mixin(
        value = {SimpleBakedModel.class, MultiPartBakedModel.class, WeightedBakedModel.class, BuiltInModel.class},
        priority = 900)
abstract class MoreCullingModelCacheDiagnosticMixin implements BakedModel {
    @Inject(method = "moreculling$resetTranslucencyCache", at = @At("HEAD"), remap = false, require = 0)
    private void bootoptim$moreCullingTranslucencyCacheStart(BlockState state, CallbackInfo ci) {
        MoreCullingStartupDiagnostics.onModelTranslucencyStateStart();
    }

    @Inject(method = "moreculling$resetTranslucencyCache", at = @At("RETURN"), remap = false, require = 0)
    private void bootoptim$moreCullingTranslucencyCacheEnd(BlockState state, CallbackInfo ci) {
        MoreCullingStartupDiagnostics.onModelTranslucencyStateEnd();
    }
}
