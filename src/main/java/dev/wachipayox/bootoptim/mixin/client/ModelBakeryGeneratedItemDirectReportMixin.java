package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBakerReport;
import dev.wachipayox.bootoptim.optimization.client.GeneratedItemBakeRouteProbe;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBakery.class)
abstract class ModelBakeryGeneratedItemDirectReportMixin {
    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void bootoptim$reportGeneratedItemDirectVerifier(ModelBakery.TextureGetter textureGetter, CallbackInfo ci) {
        GeneratedItemBakeRouteProbe.report();
        DirectGeneratedItemBakerReport.reportAfterModelBake();
    }
}
