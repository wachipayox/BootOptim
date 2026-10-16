package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.wachipayox.bootoptim.optimization.client.MoreCullingTranslucencyIndex;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "ca.fxco.moreculling.utils.SpriteUtils", remap = false)
public abstract class MoreCullingTranslucencyIndexMixin {
    @Inject(method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void bootoptim$indexed(NativeImage image, List<?> orMatch, int x0, int x1, int y0, int y1,
                                          CallbackInfoReturnable<Boolean> result) {
        Boolean answer = MoreCullingTranslucencyIndex.replace(image, orMatch, x0, x1, y0, y1);
        if (answer != null) {
            result.setReturnValue(answer);
        }
    }

    @Inject(method = "doesHaveTranslucency(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/List;IIII)Z",
            at = @At("RETURN"), require = 0)
    private static void bootoptim$verify(NativeImage image, List<?> orMatch, int x0, int x1, int y0, int y1,
                                         CallbackInfoReturnable<Boolean> result) {
        MoreCullingTranslucencyIndex.verify(result.getReturnValueZ());
    }
}
