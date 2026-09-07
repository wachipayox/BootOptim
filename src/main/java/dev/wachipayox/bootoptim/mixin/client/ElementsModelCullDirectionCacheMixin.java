package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import com.mojang.math.OctahedralGroup;
import dev.wachipayox.bootoptim.optimization.client.TransformationDirectionCacheAccess;
import dev.wachipayox.bootoptim.profiling.StartupReport;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.BlockModelRotation;
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
 * transform once per used direction instead of once per repeated culled face.
 * The stock loop obtains the same immutable ModelState rotation for every face,
 * so this keeps the bake and builder callbacks unchanged while removing
 * repeated direction mapping work without paying for unused directions.
 */
@Mixin(ElementsModel.class)
abstract class ElementsModelCullDirectionCacheMixin {
    @Unique
    private static final boolean BOOTOPTIM_CACHE_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionCache", "false"));
    @Unique
    private static final boolean BOOTOPTIM_CACHE_FIELD_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionFieldCache", "false"));
    @Unique
    private static final String BOOTOPTIM_CACHE_RAW_PROPERTY = System.getProperty(
            "boot_optim.elementsCullDirectionCache", "<unset>");
    @Unique
    private static final String BOOTOPTIM_CACHE_RAW_FIELD_PROPERTY = System.getProperty(
            "boot_optim.elementsCullDirectionFieldCache", "<unset>");
    @Unique
    private static final AtomicBoolean BOOTOPTIM_CACHE_REPORTED = new AtomicBoolean();
    @Unique
    private static final AtomicBoolean BOOTOPTIM_CACHE_FIELD_REPORTED = new AtomicBoolean();
    @Unique
    private static final AtomicBoolean BOOTOPTIM_CACHE_FLAGS_REPORTED = new AtomicBoolean();

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
        if (BOOTOPTIM_CACHE_FLAGS_REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization(
                    "elements_cull_direction_flags",
                    BOOTOPTIM_CACHE_ENABLED,
                    "cache=" + BOOTOPTIM_CACHE_ENABLED + ";field=" + BOOTOPTIM_CACHE_FIELD_ENABLED
                            + ";rawCache=" + BOOTOPTIM_CACHE_RAW_PROPERTY
                            + ";rawField=" + BOOTOPTIM_CACHE_RAW_FIELD_PROPERTY);
        }
        if (!BOOTOPTIM_CACHE_ENABLED) {
            return;
        }

        Transformation rootTransform = context.getRootTransform();
        if (!rootTransform.isIdentity()) {
            modelState = UnbakedGeometryHelper.composeRootTransformIntoModelState(modelState, rootTransform);
        }

        Transformation rotation = modelState.getRotation();
        TransformationDirectionCacheAccess fieldCache = BOOTOPTIM_CACHE_FIELD_ENABLED
                && ((Object) rotation) instanceof TransformationDirectionCacheAccess access
                ? access
                : null;
        OctahedralGroup discreteRotation = modelState instanceof BlockModelRotation blockModelRotation
                ? blockModelRotation.actualRotation()
                : null;
        boolean identityRotation = discreteRotation == null && rotation.isIdentity();
        Direction[] rotatedDirections = identityRotation
                ? null
                : new Direction[Direction.values().length];
        boolean usedFieldCache = false;

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
                } else if (discreteRotation != null) {
                    builder.addCulledFace(discreteRotation.rotate(cullDirection), quad);
                } else if (fieldCache != null) {
                    usedFieldCache = true;
                    builder.addCulledFace(fieldCache.bootoptim$getCachedDirection(cullDirection), quad);
                } else if (identityRotation) {
                    builder.addCulledFace(cullDirection, quad);
                } else {
                    int ordinal = cullDirection.ordinal();
                    Direction rotated = rotatedDirections[ordinal];
                    if (rotated == null) {
                        rotated = rotation.rotateTransform(cullDirection);
                        rotatedDirections[ordinal] = rotated;
                    }
                    builder.addCulledFace(rotated, quad);
                }
            }
        }

        if (usedFieldCache && BOOTOPTIM_CACHE_FIELD_REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization(
                    "elements_cull_direction_field_cache", true,
                    "per_transformation_lazy_direction_map");
        }
        if (BOOTOPTIM_CACHE_REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization(
                    "elements_cull_direction_cache", true,
                    discreteRotation != null
                            ? "block_model_discrete_rotation"
                            : identityRotation
                            ? "identity_rotation"
                            : usedFieldCache
                            ? "per_transformation_lazy_direction_map"
                            : "per_model_state_direction_map");
        }
        ci.cancel();
    }
}
