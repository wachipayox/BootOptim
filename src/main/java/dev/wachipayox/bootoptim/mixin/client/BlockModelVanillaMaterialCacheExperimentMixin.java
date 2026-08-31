package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Diagnostic A/B experiment for repeated BlockModel#getMaterial calls inside one bakeVanilla invocation. */
@Mixin(BlockModel.class)
abstract class BlockModelVanillaMaterialCacheExperimentMixin {
    @Redirect(
            method = "bakeVanilla",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;"))
    private Material bootoptim$cacheVanillaMaterial(BlockModel model, String name) {
        return ShortScopeMaterialCacheExperiment.resolve(model, name);
    }
}
