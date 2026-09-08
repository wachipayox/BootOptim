package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.ModelPreparationPlanVerifier;
import java.util.List;
import java.util.function.Function;
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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stage A verifier. Candidate output is recorded only; this mixin never cancels stock addQuads. */
@Mixin(ElementsModel.class)
abstract class ElementsModelPreparationPlanVerifierMixin {
    @Shadow @Final private List<BlockElement> elements;

    @Inject(method = "addQuads", at = @At("HEAD"), require = 0)
    private void bootoptim$beginPreparationPlanVerify(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        ModelPreparationPlanVerifier.beginElements(context, elements, baker, spriteGetter, modelState);
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;"),
            require = 0)
    private BakedQuad bootoptim$timeStockFaceBakery(
            BlockElement element, BlockElementFace face, TextureAtlasSprite sprite, Direction direction, ModelState modelState) {
        return ModelPreparationPlanVerifier.stockBakeFace(element, face, sprite, direction, modelState);
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addUnculledFace(Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 0)
    private IModelBuilder<?> bootoptim$recordStockUnculled(IModelBuilder<?> builder, BakedQuad quad) {
        ModelPreparationPlanVerifier.recordStockUnculled(quad);
        return builder.addUnculledFace(quad);
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addCulledFace(Lnet/minecraft/core/Direction;Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 0)
    private IModelBuilder<?> bootoptim$recordStockCulled(IModelBuilder<?> builder, Direction direction, BakedQuad quad) {
        ModelPreparationPlanVerifier.recordStockCulled(direction, quad);
        return builder.addCulledFace(direction, quad);
    }

    @Inject(method = "addQuads", at = @At("RETURN"), require = 0)
    private void bootoptim$finishPreparationPlanVerify(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        ModelPreparationPlanVerifier.endElements();
    }
}
