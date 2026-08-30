package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CustomGeometryBakeProfiler;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Times the custom-geometry invocation used by BlockModel baking in NeoForge 1.21.1. */
@Mixin(UnbakedGeometryHelper.class)
abstract class UnbakedGeometryHelperProfilingMixin {
    @Redirect(
            method = "bake",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/model/geometry/IUnbakedGeometry;bake(Lnet/neoforged/neoforge/client/model/geometry/IGeometryBakingContext;Lnet/minecraft/client/resources/model/ModelBaker;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;Lnet/minecraft/client/renderer/block/model/ItemOverrides;)Lnet/minecraft/client/resources/model/BakedModel;"))
    private static BakedModel bootoptim$profileCustomGeometryBake(
            IUnbakedGeometry<?> geometry,
            IGeometryBakingContext context,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            ItemOverrides overrides) {
        return CustomGeometryBakeProfiler.profile(
                (BlockGeometryBakingContext) context,
                geometry,
                modelState,
                () -> geometry.bake(context, baker, spriteGetter, modelState, overrides));
    }
}
