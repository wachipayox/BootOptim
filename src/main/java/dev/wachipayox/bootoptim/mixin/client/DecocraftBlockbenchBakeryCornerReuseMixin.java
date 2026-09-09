package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.wachipayox.bootoptim.optimization.client.DecocraftCornerRotationReuse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Decocraft 3.0.11-only experiment. The stock bakeVertex has already selected a cuboid corner
 * when it calls applyElementRotation. A corner's element/group rotation is independent of face,
 * sprite, material and ModelState. We therefore execute Decocraft's exact private operation for
 * the first occurrence of each of the at-most eight corners of the current element and reuse only
 * its xyz for later faces. Everything after that call remains byte-for-byte on Decocraft's stock path.
 */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftBlockbenchBakeryCornerReuseMixin {
    @Unique private Object bootoptim$cornerElement;
    @Unique private int bootoptim$cornerMask;
    @Unique private float[] bootoptim$cornerData;

    @WrapOperation(
            method = "bakeVertex([Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery$VertexData;ILcom/razz/decocraft/models/bbmodel/BBModelParts$Locator;Lnet/minecraft/core/Direction;Lcom/razz/decocraft/models/bbmodel/BlockbenchLoader$BlockbenchSetting;Lcom/razz/decocraft/models/bbmodel/BBModelParts$UVCoordinate;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Resolution;FFFFFFLnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lorg/joml/Matrix4f;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery;applyElementRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V"),
            require = 0)
    private void bootoptim$reuseCornerRotation(
            @Coerce Object bakery,
            @Coerce Object position,
            @Coerce Object element,
            Operation<Void> original,
            @Local(argsOnly = true, ordinal = 0) float fromX,
            @Local(argsOnly = true, ordinal = 1) float fromY,
            @Local(argsOnly = true, ordinal = 2) float fromZ,
            @Local(argsOnly = true, ordinal = 3) float toX,
            @Local(argsOnly = true, ordinal = 4) float toY,
            @Local(argsOnly = true, ordinal = 5) float toZ) {
        if (!DecocraftCornerRotationReuse.enabled() || !(position instanceof DecocraftVector3Accessor vector)) {
            original.call(bakery, position, element);
            return;
        }
        DecocraftCornerRotationReuse.redirect();

        if (bootoptim$cornerElement != element) {
            bootoptim$cornerElement = element;
            bootoptim$cornerMask = 0;
            DecocraftCornerRotationReuse.elementTransition();
        }

        int x = axis(vector.bootoptim$getX(), fromX, toX);
        int y = axis(vector.bootoptim$getY(), fromY, toY);
        int z = axis(vector.bootoptim$getZ(), fromZ, toZ);
        if (x < 0 || y < 0 || z < 0) {
            DecocraftCornerRotationReuse.classificationFallback();
            original.call(bakery, position, element);
            return;
        }

        int corner = x | (y << 1) | (z << 2);
        int bit = 1 << corner;
        if ((bootoptim$cornerMask & bit) == 0) {
            original.call(bakery, position, element);
            if (bootoptim$cornerData == null) bootoptim$cornerData = new float[24];
            int offset = corner * 3;
            bootoptim$cornerData[offset] = vector.bootoptim$getX();
            bootoptim$cornerData[offset + 1] = vector.bootoptim$getY();
            bootoptim$cornerData[offset + 2] = vector.bootoptim$getZ();
            bootoptim$cornerMask |= bit;
            DecocraftCornerRotationReuse.prepared();
            return;
        }

        int offset = corner * 3;
        vector.bootoptim$setX(bootoptim$cornerData[offset]);
        vector.bootoptim$setY(bootoptim$cornerData[offset + 1]);
        vector.bootoptim$setZ(bootoptim$cornerData[offset + 2]);
        DecocraftCornerRotationReuse.reused();
    }

    @Unique
    private static int axis(float value, float low, float high) {
        int bits = Float.floatToRawIntBits(value);
        if (bits == Float.floatToRawIntBits(low)) return 0;
        if (bits == Float.floatToRawIntBits(high)) return 1;
        return -1;
    }
}
