package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.ValidatedDependencyUnion;
import dev.wachipayox.bootoptim.profiling.client.SegmentOwnerMetrics;
import java.util.Collection;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPart.class)
abstract class MultiPartSegmentClockMixin {
    @Inject(method = "getDependencies", at = @At("HEAD"), require = 0)
    private void bootoptim$begin(CallbackInfoReturnable<Collection<ResourceLocation>> result) {
        if (ValidatedDependencyUnion.inScope()) SegmentOwnerMetrics.begin(SegmentOwnerMetrics.MULTIPART);
    }
    @Inject(method = "getDependencies", at = @At("RETURN"), require = 0)
    private void bootoptim$end(CallbackInfoReturnable<Collection<ResourceLocation>> result) {
        if (ValidatedDependencyUnion.inScope()) SegmentOwnerMetrics.end(SegmentOwnerMetrics.MULTIPART);
    }
}
