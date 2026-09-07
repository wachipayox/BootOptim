package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.StrictElementsBakePlan;
import dev.wachipayox.bootoptim.optimization.client.StrictElementsBakePlanHolder;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;

@Mixin(ElementsModel.class)
abstract class ElementsModelStrictPlanMixin {
    @Shadow
    private List<?> elements;

    @Inject(method = "addQuads", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$applyStrictPlan(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        if (!StrictElementsBakePlan.enabled()
                || !(context instanceof BlockGeometryBakingContext blockContext)
                || blockContext.hasCustomGeometry()
                || blockContext.getRenderTypeHint() != null
                || !blockContext.getRootTransform().isIdentity()
                || blockContext.owner.getElements() != elements
                || !(blockContext.owner instanceof StrictElementsBakePlanHolder holder)) {
            return;
        }
        StrictElementsBakePlan plan = holder.bootoptim$getStrictElementsBakePlan();
        if (plan == null) {
            return;
        }
        plan.addQuads(context, modelBuilder, baker, spriteGetter, modelState);
        StrictElementsBakePlan.reportActive();
        ci.cancel();
    }
}
