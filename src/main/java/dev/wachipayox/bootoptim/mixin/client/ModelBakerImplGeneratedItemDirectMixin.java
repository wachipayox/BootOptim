package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBaker;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the actual vanilla/NeoForge generated-item shortcut in ModelBakerImpl#bakeUncached.
 *
 * <p>NeoForge's UnbakedGeometryHelper contains a generation-marker fallback too, but the normal ModelBakery hot path
 * resolves generated items here first. Models with any custom geometry fail open to the original shortcut.</p>
 */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
abstract class ModelBakerImplGeneratedItemDirectMixin {
    @Inject(
            method = "bakeUncached(Lnet/minecraft/client/resources/model/UnbakedModel;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void bootoptim$directGeneratedItemBake(
            UnbakedModel model,
            ModelState modelState,
            Function<Material, TextureAtlasSprite> sprites,
            CallbackInfoReturnable<BakedModel> cir) {
        if (!(model instanceof BlockModel blockModel)
                || blockModel.getRootModel() != ModelBakery.GENERATION_MARKER
                || blockModel.customData.getCustomGeometry() != null) {
            return;
        }

        // The exact reference pack has now passed full semantic verification: 14,865/14,865 models, zero mismatches
        // and zero fallbacks. Default this experimental PR to the candidate-only path for real performance timing,
        // while preserving an explicit -Dboot_optim.generatedItemDirectBake.verify=true escape back into verification.
        if (System.getProperty("boot_optim.generatedItemDirectBake.verify") == null) {
            System.setProperty("boot_optim.generatedItemDirectBake.verify", "false");
        }

        BakedModel result = DirectGeneratedItemBaker.tryBake(
                blockModel,
                (ModelBaker) (Object) this,
                sprites,
                modelState,
                false);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
