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

/** Intercepts ModelBakery's strict vanilla generated-item shortcut and fails open to stock. */
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
