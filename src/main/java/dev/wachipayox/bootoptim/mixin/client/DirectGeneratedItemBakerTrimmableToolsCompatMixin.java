package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBaker;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Preserves Trimmable Tools' ItemModelGenerator side-element expansion in the direct generated-item path.
 *
 * <p>Trimmable Tools mixes into ItemModelGenerator#createSideElements and moves only side element positions by
 * 0.01 blocks for sprites in its namespace. DirectGeneratedItemBaker intentionally bypasses ItemModelGenerator's
 * temporary BlockElement graph, so that external mixin would otherwise be bypassed as well. The direct baker has
 * already applied NeoForge's generated-item seam correction when this redirect runs; because Mth.lerp is affine in
 * the element position, a pre-seam delta becomes (1 - expand) * delta after the seam correction. The Trimmable Tools
 * deltas point outwards, so applying the transformed delta and clamping reproduces the stock ordering of operations,
 * including edge clamping.</p>
 */
@Mixin(DirectGeneratedItemBaker.class)
abstract class DirectGeneratedItemBakerTrimmableToolsCompatMixin {
    @Unique
    private static final String BOOTOPTIM$TRIMMABLE_TOOLS_NAMESPACE = "trimmable_tools";
    @Unique
    private static final float BOOTOPTIM$SIDE_EXPANSION = 0.01F;
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemCompat");
    @Unique
    private static final LongAdder BOOTOPTIM$TRIMMABLE_TOOLS_SIDE_QUADS = new LongAdder();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_GENERATED_ITEM_COMPAT summary=shutdown trimmable_tools_side_quads={}",
                BOOTOPTIM$TRIMMABLE_TOOLS_SIDE_QUADS.sum()), "BootOptim-generated-item-compat-report"));
    }

    @Redirect(
            method = "bakeLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/FaceBakery;bakeQuad(Lorg/joml/Vector3f;Lorg/joml/Vector3f;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;Lnet/minecraft/client/renderer/block/model/BlockElementRotation;Z)Lnet/minecraft/client/renderer/block/model/BakedQuad;",
                    ordinal = 1),
            require = 1)
    private static BakedQuad bootoptim$preserveTrimmableToolsSideExpansion(
            FaceBakery bakery,
            Vector3f from,
            Vector3f to,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state,
            BlockElementRotation rotation,
            boolean shade) {
        if (BOOTOPTIM$TRIMMABLE_TOOLS_NAMESPACE.equals(sprite.contents().name().getNamespace())) {
            BOOTOPTIM$TRIMMABLE_TOOLS_SIDE_QUADS.increment();
            float expand = -sprite.uvShrinkRatio();
            float transformedDelta = (1.0F - expand) * BOOTOPTIM$SIDE_EXPANSION;

            switch (direction) {
                case UP -> {
                    from.y = Mth.clamp(from.y + transformedDelta, 0.0F, 16.0F);
                    to.y = Mth.clamp(to.y + transformedDelta, 0.0F, 16.0F);
                }
                case DOWN -> {
                    from.y = Mth.clamp(from.y - transformedDelta, 0.0F, 16.0F);
                    to.y = Mth.clamp(to.y - transformedDelta, 0.0F, 16.0F);
                }
                case EAST -> {
                    from.x = Mth.clamp(from.x - transformedDelta, 0.0F, 16.0F);
                    to.x = Mth.clamp(to.x - transformedDelta, 0.0F, 16.0F);
                }
                case WEST -> {
                    from.x = Mth.clamp(from.x + transformedDelta, 0.0F, 16.0F);
                    to.x = Mth.clamp(to.x + transformedDelta, 0.0F, 16.0F);
                }
                default -> {
                    // Generated item side spans only use UP, DOWN, EAST and WEST.
                }
            }
        }

        return bakery.bakeQuad(from, to, face, sprite, direction, state, rotation, shade);
    }
}
