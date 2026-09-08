package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.wachipayox.bootoptim.profiling.client.MoreCullingStartupDiagnostics;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the exact NativeImage translucency scan used by MoreCulling 1.0.8. */
@Pseudo
@Mixin(targets = "ca.fxco.moreculling.utils.SpriteUtils", remap = false)
abstract class MoreCullingSpriteUtilsDiagnosticMixin {
    @Inject(
            method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$moreCullingSpriteScanStart(
            NativeImage image,
            List<NativeImage> layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            CallbackInfoReturnable<Boolean> cir) {
        MoreCullingStartupDiagnostics.onSpriteTranslucencyStart(
                image, layeredImages, minWidth, maxWidth, minHeight, maxHeight);
    }

    @Inject(
            method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("RETURN"),
            require = 0)
    private static void bootoptim$moreCullingSpriteScanEnd(
            NativeImage image,
            List<NativeImage> layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            CallbackInfoReturnable<Boolean> cir) {
        MoreCullingStartupDiagnostics.onSpriteTranslucencyEnd(cir.getReturnValueZ());
    }
}
