package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBaker;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the experiment's stock verifier and empty metadata skeleton on the exact route used by
 * ModelBakery.ModelBakerImpl: generated BlockModel#bake, not bakeVanilla.
 *
 * <p>This is intentionally isolated as a mixin while the direct-bake experiment is still draft; if promoted, the
 * two call sites should be folded directly into DirectGeneratedItemBaker.</p>
 */
@Mixin(DirectGeneratedItemBaker.class)
abstract class DirectGeneratedItemBakerStockRouteMixin {
    @Redirect(
            method = {"tryBake", "bakeCandidate"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeVanilla(Lnet/minecraft/client/resources/model/ModelBaker;Lnet/minecraft/client/renderer/block/model/BlockModel;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;Z)Lnet/minecraft/client/resources/model/BakedModel;"),
            require = 0)
    private static BakedModel bootoptim$useExactStockGeneratedBake(
            BlockModel generated,
            ModelBaker modelBaker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d) {
        return generated.bake(modelBaker, owner, spriteGetter, modelState, guiLight3d);
    }
}
