package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** Diagnostic-only scope marker around NeoForge's generated-item bakeVanilla call. */
@Mixin(UnbakedGeometryHelper.class)
abstract class UnbakedGeometryHelperResidualProfilingMixin {
    @Redirect(
            method = "bake",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeVanilla(Lnet/minecraft/client/resources/model/ModelBaker;Lnet/minecraft/client/renderer/block/model/BlockModel;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;Z)Lnet/minecraft/client/resources/model/BakedModel;"),
            require = 0)
    private static BakedModel bootoptim$profileGeneratedBake(
            BlockModel generatedModel,
            ModelBaker modelBaker,
            BlockModel owner,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d) {
        ModelElementResidualProfiler.beginGenerated();
        try {
            return generatedModel.bakeVanilla(modelBaker, owner, spriteGetter, modelState, guiLight3d);
        } finally {
            ModelElementResidualProfiler.endGenerated();
        }
    }
}
