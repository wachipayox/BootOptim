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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Times the actual custom-geometry bake call, below BlockModel/top-level aliases. */
@Mixin(BlockGeometryBakingContext.class)
abstract class BlockGeometryBakingContextProfilingMixin {
    @Redirect(
            method = "bake",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/model/geometry/IUnbakedGeometry;bake(Lnet/neoforged/neoforge/client/model/geometry/IGeometryBakingContext;Lnet/minecraft/client/resources/model/ModelBaker;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;Lnet/minecraft/client/renderer/block/model/ItemOverrides;)Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel bootoptim$profileCustomGeometryBake(
            IUnbakedGeometry<?> geometry,
            IGeometryBakingContext context,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            ItemOverrides overrides) {
        BlockGeometryBakingContext blockContext = (BlockGeometryBakingContext) context;
        return CustomGeometryBakeProfiler.profile(
                blockContext,
                geometry,
                modelState,
                () -> geometry.bake(context, baker, spriteGetter, modelState, overrides));
    }
}
