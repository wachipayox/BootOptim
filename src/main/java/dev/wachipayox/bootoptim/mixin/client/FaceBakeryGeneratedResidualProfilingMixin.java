package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelElementResidualProfiler;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only FaceBakery timing while NeoForge is baking a generated item model. */
@Mixin(FaceBakery.class)
abstract class FaceBakeryGeneratedResidualProfilingMixin {
    @Inject(method = "bakeQuad", at = @At("HEAD"))
    private void bootoptim$beginGeneratedFace(
            Vector3f from,
            Vector3f to,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state,
            BlockElementRotation rotation,
            boolean shade,
            CallbackInfoReturnable<BakedQuad> cir) {
        ModelElementResidualProfiler.beginGeneratedFace();
    }

    @Inject(method = "bakeQuad", at = @At("RETURN"))
    private void bootoptim$endGeneratedFace(
            Vector3f from,
            Vector3f to,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state,
            BlockElementRotation rotation,
            boolean shade,
            CallbackInfoReturnable<BakedQuad> cir) {
        ModelElementResidualProfiler.endGeneratedFace();
    }
}
