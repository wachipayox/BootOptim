package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FilePackResourcesProfiler;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only timing around vanilla external ZIP resource-pack enumeration. */
@Mixin(FilePackResources.class)
abstract class FilePackResourcesProfilerMixin {
    @Inject(method = "getNamespaces", at = @At("HEAD"), require = 0)
    private void bootoptim$beginNamespaces(PackType packType, CallbackInfoReturnable<java.util.Set<String>> cir) {
        FilePackResourcesProfiler.begin("get_namespaces", packType, bootoptim$packId());
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"), require = 0)
    private void bootoptim$finishNamespaces(
            PackType packType,
            CallbackInfoReturnable<java.util.Set<String>> cir) {
        FilePackResourcesProfiler.finish(
                "get_namespaces", packType, bootoptim$packId(), cir.getReturnValue() == null ? 0 : cir.getReturnValue().size());
    }

    @Inject(method = "listResources", at = @At("HEAD"), require = 0)
    private void bootoptim$beginListResources(
            PackType packType,
            String namespace,
            String path,
            PackResources.ResourceOutput output,
            CallbackInfo ci) {
        FilePackResourcesProfiler.begin("list_resources", packType, bootoptim$packId());
    }

    @Inject(method = "listResources", at = @At("RETURN"), require = 0)
    private void bootoptim$finishListResources(
            PackType packType,
            String namespace,
            String path,
            PackResources.ResourceOutput output,
            CallbackInfo ci) {
        FilePackResourcesProfiler.finish("list_resources", packType, bootoptim$packId(), 0);
    }

    private String bootoptim$packId() {
        try {
            return ((FilePackResources) (Object) this).packId();
        } catch (RuntimeException ex) {
            return "<pack-id-error>";
        }
    }
}
