package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import dev.wachipayox.bootoptim.profiling.client.ShortScopeMaterialCacheExperiment;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Diagnostic-only timing of NeoForge's vanilla-elements geometry path. */
@Mixin(ElementsModel.class)
abstract class ElementsModelResidualProfilingMixin {
    @WrapMethod(method = "addQuads")
    private void bootoptim$shortScopeMaterialCache(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            Operation<Void> original) {
        ShortScopeMaterialCacheExperiment.beginElements();
        try {
            original.call(context, modelBuilder, baker, spriteGetter, modelState);
        } finally {
            ShortScopeMaterialCacheExperiment.endScope();
        }
    }

    @Inject(method = "addQuads", at = @At("HEAD"))
    private void bootoptim$beginElements(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        ModelElementResidualProfiler.beginElements();
    }

    @Inject(method = "addQuads", at = @At("RETURN"))
    private void bootoptim$endElements(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        ModelElementResidualProfiler.endElements();
    }

    @Redirect(
            method = "addQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/model/geometry/IGeometryBakingContext;getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;"))
    private Material bootoptim$cacheElementMaterial(IGeometryBakingContext context, String name) {
        return ShortScopeMaterialCacheExperiment.resolve(context, name);
    }

    @Redirect(
            method = "addQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;"))
    private BakedQuad bootoptim$profileElementFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state) {
        return ModelElementResidualProfiler.profileElementsFace(element, face, sprite, direction, state);
    }
}
