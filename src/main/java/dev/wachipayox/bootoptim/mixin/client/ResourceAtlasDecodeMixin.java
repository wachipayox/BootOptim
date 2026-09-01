package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.AtlasDecodeProfiler;
import java.io.InputStream;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only Resource timings while a SpriteResourceLoader call is active on this worker. */
@Mixin(Resource.class)
abstract class ResourceAtlasDecodeMixin {
    @Inject(method = "metadata", at = @At("HEAD"), require = 0)
    private void bootoptim$metadataStart(CallbackInfoReturnable<ResourceMetadata> cir) {
        AtlasDecodeProfiler.metadataStart();
    }

    @Inject(method = "metadata", at = @At("RETURN"), require = 0)
    private void bootoptim$metadataEnd(CallbackInfoReturnable<ResourceMetadata> cir) {
        AtlasDecodeProfiler.metadataEnd();
    }

    @Inject(method = "open", at = @At("HEAD"), require = 0)
    private void bootoptim$openStart(CallbackInfoReturnable<InputStream> cir) {
        AtlasDecodeProfiler.resourceOpenStart();
    }

    @Inject(method = "open", at = @At("RETURN"), cancellable = true, require = 0)
    private void bootoptim$openEnd(CallbackInfoReturnable<InputStream> cir) {
        cir.setReturnValue(AtlasDecodeProfiler.resourceOpenEnd(cir.getReturnValue()));
    }
}
