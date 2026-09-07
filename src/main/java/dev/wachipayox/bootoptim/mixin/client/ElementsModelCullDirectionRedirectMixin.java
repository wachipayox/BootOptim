package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import com.mojang.math.OctahedralGroup;
import dev.wachipayox.bootoptim.optimization.client.TransformationDirectionCacheAccess;
import dev.wachipayox.bootoptim.profiling.StartupReport;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Unique
    private static final boolean BOOTOPTIM_REDIRECT_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionRedirectCache", "false"));
    @Unique
    private static final boolean BOOTOPTIM_REDIRECT_FIELD_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.elementsCullDirectionFieldCache", "false"));
    @Unique
    private static final String BOOTOPTIM_REDIRECT_RAW_PROPERTY = System.getProperty(
            "boot_optim.elementsCullDirectionRedirectCache", "<unset>");
    @Unique
    private static final String BOOTOPTIM_REDIRECT_RAW_CACHE_PROPERTY = System.getProperty(
            "boot_optim.elementsCullDirectionCache", "<unset>");
    @Unique
    private static final String BOOTOPTIM_REDIRECT_RAW_FIELD_PROPERTY = System.getProperty(
            "boot_optim.elementsCullDirectionFieldCache", "<unset>");
    @Unique
    private static final AtomicBoolean BOOTOPTIM_REDIRECT_FLAGS_REPORTED = new AtomicBoolean();
    @Unique
    private static final AtomicBoolean BOOTOPTIM_REDIRECT_INVOCATION_REPORTED = new AtomicBoolean();
    @Unique
    private static final AtomicBoolean BOOTOPTIM_REDIRECT_REPORTED = new AtomicBoolean();
    @Unique
    private static final Map<Transformation, Direction[]> BOOTOPTIM_REDIRECT_VANILLA_ROTATIONS = createVanillaRotations();

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

    @Inject(method = "addQuads", at = @At("HEAD"), require = 0)
    private void bootoptim$reportFlags(CallbackInfo ci) {
        if (BOOTOPTIM_REDIRECT_FLAGS_REPORTED.compareAndSet(false, true)) {
            StartupReport.optimization(
                    "elements_cull_direction_redirect_flags",
                    BOOTOPTIM_REDIRECT_ENABLED,
                    "redirect=" + BOOTOPTIM_REDIRECT_ENABLED + ";cache=" + BOOTOPTIM_REDIRECT_RAW_CACHE_PROPERTY
                            + ";field=" + BOOTOPTIM_REDIRECT_RAW_FIELD_PROPERTY
                            + ";rawRedirect=" + BOOTOPTIM_REDIRECT_RAW_PROPERTY);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/math/Transformation;rotateTransform(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/Direction;"),
            require = 0)
    private Direction bootoptim$rotateCullDirection(Transformation transformation, Direction direction) {
        if (BOOTOPTIM_REDIRECT_ENABLED) {
            if (BOOTOPTIM_REDIRECT_INVOCATION_REPORTED.compareAndSet(false, true)) {
                StartupReport.optimization(
                        "elements_cull_direction_redirect_invoked", true, "transformation_rotate_transform");
            }
            Direction[] mapped = BOOTOPTIM_REDIRECT_VANILLA_ROTATIONS.get(transformation);
            if (mapped != null) {
                if (BOOTOPTIM_REDIRECT_REPORTED.compareAndSet(false, true)) {
                    StartupReport.optimization(
                            "elements_cull_direction_redirect_cache", true, "vanilla_block_model_rotation_table");
                }
                return mapped[direction.ordinal()];
            }
        }
        if (BOOTOPTIM_REDIRECT_FIELD_ENABLED && ((Object) transformation) instanceof TransformationDirectionCacheAccess cache) {
            if (BOOTOPTIM_REDIRECT_REPORTED.compareAndSet(false, true)) {
                StartupReport.optimization(
                        "elements_cull_direction_field_cache", true, "per_transformation_lazy_direction_map");
            }
            return cache.bootoptim$getCachedDirection(direction);
        }
        return transformation.rotateTransform(direction);
    }
}
