package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftRotatedQuadReuse;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBakery.class)
abstract class ModelBakeryDecocraftReuseMixin {
    @Inject(method = "bakeModels", at = @At("HEAD"), require = 0)
    private void bootoptim$beginDecocraftReuse(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        DecocraftRotatedQuadReuse.beginModelBake();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void bootoptim$finishDecocraftReuse(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        DecocraftRotatedQuadReuse.finishModelBake();
    }
}
