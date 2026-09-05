package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceOpenPhysicalProfiler;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedReader;
import java.io.InputStream;

/** Diagnostic-only split of Resource open from subsequent stream reads. */
@Mixin(Resource.class)
abstract class ResourceOpenPhysicalMixin {
    @Shadow @Final private PackResources source;

    @Unique private static final ThreadLocal<ResourceOpenPhysicalProfiler.OpenToken> bootoptim$openToken = new ThreadLocal<>();
    @Unique private static final ThreadLocal<ResourceOpenPhysicalProfiler.ReaderToken> bootoptim$readerToken = new ThreadLocal<>();

    @Inject(method = "open", at = @At("HEAD"), require = 0)
    private void bootoptim$startOpen(CallbackInfoReturnable<InputStream> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled() || this.source == null) return;
        bootoptim$openToken.set(ResourceOpenPhysicalProfiler.beginResourceOpen(
                this.source.packId(), this.source.getClass().getName()));
    }

    @Inject(method = "open", at = @At("RETURN"), cancellable = true, require = 0)
    private void bootoptim$endOpen(CallbackInfoReturnable<InputStream> cir) {
        ResourceOpenPhysicalProfiler.OpenToken token = bootoptim$openToken.get();
        bootoptim$openToken.remove();
        if (token != null) {
            cir.setReturnValue(ResourceOpenPhysicalProfiler.endResourceOpen(token, cir.getReturnValue()));
        }
    }

    @Inject(method = "openAsReader", at = @At("HEAD"), require = 0)
    private void bootoptim$startReader(CallbackInfoReturnable<BufferedReader> cir) {
        if (!ResourceOpenPhysicalProfiler.enabled() || this.source == null) return;
        bootoptim$readerToken.set(ResourceOpenPhysicalProfiler.beginReaderOpen(
                this.source.packId(), this.source.getClass().getName()));
    }

    @Inject(method = "openAsReader", at = @At("RETURN"), require = 0)
    private void bootoptim$endReader(CallbackInfoReturnable<BufferedReader> cir) {
        ResourceOpenPhysicalProfiler.ReaderToken token = bootoptim$readerToken.get();
        bootoptim$readerToken.remove();
        ResourceOpenPhysicalProfiler.endReaderOpen(token);
    }
}
