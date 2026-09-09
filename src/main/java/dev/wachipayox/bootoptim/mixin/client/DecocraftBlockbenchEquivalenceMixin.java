package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.DecocraftBakedQuadEquivalenceProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchModel", remap = false)
abstract class DecocraftBlockbenchEquivalenceMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bootoptim$observeFinalBakedQuads(CallbackInfo ci) {
        DecocraftBakedQuadEquivalenceProbe.observe(this);
    }
}
