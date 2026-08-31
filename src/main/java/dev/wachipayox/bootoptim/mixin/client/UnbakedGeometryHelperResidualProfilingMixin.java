package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Diagnostic-only scope marker around NeoForge's complete generated-item bake path. */
@Mixin(UnbakedGeometryHelper.class)
abstract class UnbakedGeometryHelperResidualProfilingMixin {
    @Inject(method = "bake", at = @At("HEAD"))
    private static void bootoptim$beginGeneratedGeometry(
            BlockModel blockModel,
            ModelBaker modelBaker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        if (blockModel.getRootModel() == ModelBakery.GENERATION_MARKER) {
            ModelElementResidualProfiler.beginGenerated();
        }
    }

    @Inject(method = "bake", at = @At("RETURN"))
    private static void bootoptim$endGeneratedGeometry(
            BlockModel blockModel,
            ModelBaker modelBaker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        if (blockModel.getRootModel() == ModelBakery.GENERATION_MARKER) {
            ModelElementResidualProfiler.endGenerated();
        }
    }
}
