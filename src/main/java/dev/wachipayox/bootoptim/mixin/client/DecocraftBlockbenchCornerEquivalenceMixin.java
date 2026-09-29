package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DecocraftCornerQuadEquivalenceProbe;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchModel", remap = false)
abstract class DecocraftBlockbenchCornerEquivalenceMixin {
    @Shadow private List<BakedQuad> bakedQuads;

    @Inject(
            method = "<init>(Lcom/razz/decocraft/models/bbmodel/BlockbenchLoader$BlockbenchSetting;Lcom/razz/decocraft/models/bbmodel/BBModel;Ljava/util/function/Function;Lnet/minecraft/client/resources/model/ModelState;)V",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$observeCornerReuseOutput(CallbackInfo ci) {
        DecocraftCornerQuadEquivalenceProbe.observe(bakedQuads);
    }
}
