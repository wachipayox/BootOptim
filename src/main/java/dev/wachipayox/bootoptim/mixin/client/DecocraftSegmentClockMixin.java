package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.SegmentOwnerMetrics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftSegmentClockMixin {
    @Inject(method = "bakeQuad", at = @At("HEAD"), require = 0)
    private void bootoptim$begin(CallbackInfoReturnable<BakedQuad> result) {
        SegmentOwnerMetrics.begin(SegmentOwnerMetrics.DECOCRAFT);
    }
    @Inject(method = "bakeQuad", at = @At("RETURN"), require = 0)
    private void bootoptim$end(CallbackInfoReturnable<BakedQuad> result) {
        SegmentOwnerMetrics.end(SegmentOwnerMetrics.DECOCRAFT);
    }
}
