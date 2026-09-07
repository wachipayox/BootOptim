package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import com.mojang.math.OctahedralGroup;
import dev.wachipayox.bootoptim.optimization.client.TransformationDirectionCacheAccess;
import dev.wachipayox.bootoptim.profiling.StartupReport;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surgical alternative to the full ElementsModel loop replacement. It keeps
 * NeoForge's stock face loop and only substitutes the direction mapping for
 * the sixteen immutable vanilla BlockModelRotation transformations.
 */
@Mixin(ElementsModel.class)
abstract class ElementsModelCullDirectionRedirectMixin {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionRedirectCache", "false"));
    private static final boolean FIELD_CACHE_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionFieldCache", "false"));
    private static final AtomicBoolean INVOCATION_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final Map<Transformation, Direction[]> VANILLA_ROTATIONS = createVanillaRotations();

    private static Map<Transformation, Direction[]> createVanillaRotations() {
        Map<Transformation, Direction[]> result = new IdentityHashMap<>();
        for (BlockModelRotation rotation : BlockModelRotation.values()) {
            OctahedralGroup discrete = rotation.actualRotation();
            Direction[] mapped = new Direction[Direction.values().length];
            for (Direction direction : Direction.values()) {
                mapped[direction.ordinal()] = discrete.rotate(direction);
            }
            result.put(rotation.getRotation(), mapped);
        }
        return result;
    }

    @Redirect(
            method = "addQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/math/Transformation;rotateTransform(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/Direction;"),
            require = 0)
    private Direction bootoptim$rotateCullDirection(Transformation transformation, Direction direction) {
        if (ENABLED) {
            if (INVOCATION_REPORTED.compareAndSet(false, true)) {
                StartupReport.optimization(
                        "elements_cull_direction_redirect_invoked", true, "transformation_rotate_transform");
            }
            Direction[] mapped = VANILLA_ROTATIONS.get(transformation);
            if (mapped != null) {
                if (REPORTED.compareAndSet(false, true)) {
                    StartupReport.optimization(
                            "elements_cull_direction_redirect_cache", true, "vanilla_block_model_rotation_table");
                }
                return mapped[direction.ordinal()];
            }
        }
        if (FIELD_CACHE_ENABLED && ((Object) transformation) instanceof TransformationDirectionCacheAccess cache) {
            if (REPORTED.compareAndSet(false, true)) {
                StartupReport.optimization(
                        "elements_cull_direction_field_cache", true, "per_transformation_lazy_direction_map");
            }
            return cache.bootoptim$getCachedDirection(direction);
        }
        return transformation.rotateTransform(direction);
    }
}
