package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.wachipayox.bootoptim.optimization.client.MoreCullingTranslucencyCache;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses only exact reload-local MoreCulling NativeImage+bounds translucency results. */
@Pseudo
@Mixin(targets = "ca.fxco.moreculling.utils.SpriteUtils", remap = false)
abstract class MoreCullingTranslucencyCacheMixin {
    @Inject(
            method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void bootoptim$reuseMoreCullingTranslucency(
            NativeImage image,
            List<NativeImage> layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            CallbackInfoReturnable<Boolean> cir) {
        MoreCullingTranslucencyCache.Lookup cached = MoreCullingTranslucencyCache.lookup(
                image, layeredImages, minWidth, maxWidth, minHeight, maxHeight);
        if (cached == MoreCullingTranslucencyCache.Lookup.TRUE) {
            cir.setReturnValue(true);
        } else if (cached == MoreCullingTranslucencyCache.Lookup.FALSE) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("RETURN"),
            require = 0)
    private static void bootoptim$rememberMoreCullingTranslucency(
            NativeImage image,
            List<NativeImage> layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            CallbackInfoReturnable<Boolean> cir) {
        MoreCullingTranslucencyCache.store(
                image,
                layeredImages,
                minWidth,
                maxWidth,
                minHeight,
                maxHeight,
                cir.getReturnValueZ());
    }
}
