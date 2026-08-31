package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBaker;
import dev.wachipayox.bootoptim.optimization.client.GeneratedItemBakeRouteProbe;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diverts only NeoForge's strict vanilla generation-marker path. Custom geometries, block-entity marker models and
 * normal element models continue through UnbakedGeometryHelper unchanged.
 */
@Mixin(UnbakedGeometryHelper.class)
abstract class UnbakedGeometryHelperGeneratedItemDirectMixin {
    @Inject(method = "bake", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$directGeneratedItemBake(
            BlockModel blockModel,
            ModelBaker modelBaker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        if (!GeneratedItemBakeRouteProbe.recordAndIsGenerated(blockModel)) {
            return;
        }

        BakedModel result = DirectGeneratedItemBaker.tryBake(
                blockModel,
                modelBaker,
                spriteGetter,
                modelState,
                guiLight3d);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
