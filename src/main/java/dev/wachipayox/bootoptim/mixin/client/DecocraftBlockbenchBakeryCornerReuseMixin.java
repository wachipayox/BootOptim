package dev.wachipayox.bootoptim.mixin.client;

import com.razz.decocraft.models.bbmodel.BBModelParts.Element;
import com.razz.decocraft.models.bbmodel.BlockbenchBakery;
import com.razz.decocraft.models.libgdx.Vector3;
import dev.wachipayox.bootoptim.optimization.client.DecocraftCornerRotationReuse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Decocraft 3.0.11-only experiment: within one BlockbenchBakery/model, a cuboid corner is
 * independent of face, sprite, material and ModelState until after applyElementRotation.
 * First-seen corners still execute Decocraft's exact private method; later faces reuse only
 * that resulting xyz and then continue through the untouched stock scale/root/model-state/UV/facing/BakedQuad path.
 */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftBlockbenchBakeryCornerReuseMixin {
    @Unique private Element bootoptim$cornerElement;
    @Unique private int bootoptim$cornerMask;
    @Unique private float[] bootoptim$cornerData;

    @Shadow
    private void applyElementRotation(Vector3 position, Element element) {
        throw new AssertionError("mixin shadow");
    }

    @Redirect(
            method = "bakeVertex([Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery$VertexData;ILcom/razz/decocraft/models/bbmodel/BBModelParts$Locator;Lnet/minecraft/core/Direction;Lcom/razz/decocraft/models/bbmodel/BlockbenchLoader$BlockbenchSetting;Lcom/razz/decocraft/models/bbmodel/BBModelParts$UVCoordinate;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Resolution;FFFFFFLnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lorg/joml/Matrix4f;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery;applyElementRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V"),
            require = 0,
            expect = 1)
    private void bootoptim$reuseCornerRotation(BlockbenchBakery bakery, Vector3 position, Element element) {
        if (!DecocraftCornerRotationReuse.enabled()) {
            applyElementRotation(position, element);
            return;
        }
        DecocraftCornerRotationReuse.redirect();

        if (element == null || element.from == null || element.to == null || position == null) {
            DecocraftCornerRotationReuse.classificationFallback();
            applyElementRotation(position, element);
            return;
        }

        if (bootoptim$cornerElement != element) {
            bootoptim$cornerElement = element;
            bootoptim$cornerMask = 0;
            DecocraftCornerRotationReuse.elementTransition();
        }

        float halfInflate = element.inflate / 2.0F;
        float fromX = (element.from.x - halfInflate) / 16.0F;
        float fromY = (element.from.y - halfInflate) / 16.0F;
        float fromZ = (element.from.z - halfInflate) / 16.0F;
        float toX = (element.to.x + halfInflate) / 16.0F;
        float toY = (element.to.y + halfInflate) / 16.0F;
        float toZ = (element.to.z + halfInflate) / 16.0F;

        int x = axis(position.x, fromX, toX);
        int y = axis(position.y, fromY, toY);
        int z = axis(position.z, fromZ, toZ);
        if (x < 0 || y < 0 || z < 0) {
            DecocraftCornerRotationReuse.classificationFallback();
            applyElementRotation(position, element);
            return;
        }
        int corner = x | (y << 1) | (z << 2);
        int bit = 1 << corner;
        if ((bootoptim$cornerMask & bit) == 0) {
            applyElementRotation(position, element);
            if (bootoptim$cornerData == null) bootoptim$cornerData = new float[24];
            int offset = corner * 3;
            bootoptim$cornerData[offset] = position.x;
            bootoptim$cornerData[offset + 1] = position.y;
            bootoptim$cornerData[offset + 2] = position.z;
            bootoptim$cornerMask |= bit;
            DecocraftCornerRotationReuse.prepared();
            return;
        }

        int offset = corner * 3;
        position.x = bootoptim$cornerData[offset];
        position.y = bootoptim$cornerData[offset + 1];
        position.z = bootoptim$cornerData[offset + 2];
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
