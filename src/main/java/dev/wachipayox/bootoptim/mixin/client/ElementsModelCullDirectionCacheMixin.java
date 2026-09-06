package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import dev.wachipayox.bootoptim.profiling.StartupReport;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Experimental ElementsModel loop that resolves the model-state cull-direction
 * transform once per direction instead of once per culled face. The stock loop
 * obtains the same immutable ModelState rotation for every face, so this keeps
 * the bake and builder callbacks unchanged while removing repeated direction
 * mapping work from the hot face loop.
 */
@Mixin(ElementsModel.class)
abstract class ElementsModelCullDirectionCacheMixin {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionCache", "false"));
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    @Shadow
    @Final
    private List<BlockElement> elements;

    @Inject(method = "addQuads", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$addQuads(
            IGeometryBakingContext context,
            IModelBuilder<?> builder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        if (!ENABLED) {
            return;
        }

        Transformation rootTransform = context.getRootTransform();
        if (!rootTransform.isIdentity()) {
            modelState = UnbakedGeometryHelper.composeRootTransformIntoModelState(modelState, rootTransform);
        }

        Direction[] directions = Direction.values();
        Direction[] rotatedDirections = new Direction[directions.length];
        Transformation rotation = modelState.getRotation();
        for (Direction direction : directions) {
            rotatedDirections[direction.ordinal()] = rotation.rotateTransform(direction);
        }

        for (BlockElement element : elements) {
            for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                BlockElementFace face = entry.getValue();
                TextureAtlasSprite sprite = spriteGetter.apply(
                        context.getMaterial(face.texture()));
                BakedQuad quad = BlockModel.bakeFace(
                        element, face, sprite, entry.getKey(), modelState);
                Direction cullDirection = face.cullForDirection();
                if (cullDirection == null) {
                    builder.addUnculledFace(quad);
                } else {
                    builder.addCulledFace(rotatedDirections[cullDirection.ordinal()], quad);
                }
            }
        }

        if (REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization(
                    "elements_cull_direction_cache", true, "per_model_state_direction_map");
        }
        ci.cancel();
    }
}
