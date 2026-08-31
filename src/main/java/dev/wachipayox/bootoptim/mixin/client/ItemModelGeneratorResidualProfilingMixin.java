package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.GeneratedItemResidualProfiler;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

/** Diagnostic-only split of generated-item sprite scanning and NeoForge seam correction. */
@Mixin(ItemModelGenerator.class)
abstract class ItemModelGeneratorResidualProfilingMixin {
    @Inject(method = "generateBlockModel", at = @At("HEAD"))
    private void bootoptim$beginGenerate(
            Function<Material, TextureAtlasSprite> spriteGetter,
            BlockModel model,
            CallbackInfoReturnable<BlockModel> cir) {
        GeneratedItemResidualProfiler.beginGenerate();
    }

    @Inject(method = "generateBlockModel", at = @At("RETURN"))
    private void bootoptim$endGenerate(
            Function<Material, TextureAtlasSprite> spriteGetter,
            BlockModel model,
            CallbackInfoReturnable<BlockModel> cir) {
        GeneratedItemResidualProfiler.endGenerate();
    }

    @Inject(method = "processFrames", at = @At("HEAD"))
    private void bootoptim$beginProcess(
            int layer,
            String texture,
            SpriteContents sprite,
            CallbackInfoReturnable<List<BlockElement>> cir) {
        GeneratedItemResidualProfiler.beginProcess();
    }

    @Inject(method = "processFrames", at = @At("RETURN"))
    private void bootoptim$endProcess(
            int layer,
            String texture,
            SpriteContents sprite,
            CallbackInfoReturnable<List<BlockElement>> cir) {
        GeneratedItemResidualProfiler.endProcess();
    }

    @Inject(
            method = "getSpans(Lnet/minecraft/client/renderer/texture/SpriteContents;)Ljava/util/List;",
            at = @At("HEAD"))
    private void bootoptim$beginSpans(SpriteContents sprite, CallbackInfoReturnable<List<?>> cir) {
        GeneratedItemResidualProfiler.beginSpans(sprite);
    }

    @Inject(
            method = "getSpans(Lnet/minecraft/client/renderer/texture/SpriteContents;)Ljava/util/List;",
            at = @At("RETURN"))
    private void bootoptim$endSpans(SpriteContents sprite, CallbackInfoReturnable<List<?>> cir) {
        List<?> result = cir.getReturnValue();
        GeneratedItemResidualProfiler.endSpans(sprite, result == null ? -1 : result.size());
    }

    @Redirect(
            method = "generateBlockModel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;fixItemModelSeams(Ljava/util/List;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Ljava/util/List;"))
    private List<BlockElement> bootoptim$profileSeamFix(List<BlockElement> elements, TextureAtlasSprite sprite) {
        return GeneratedItemResidualProfiler.profileSeamFix(elements, sprite);
    }
}
