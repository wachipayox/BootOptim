package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceOpenPhysicalProfiler;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only attribution for PathPackResources directory enumeration. */
@Mixin(PathPackResources.class)
abstract class PathPackResourcesPhysicalMixin {
    @Unique private static final ThreadLocal<ResourceOpenPhysicalProfiler.PackToken> bootoptim$listToken = new ThreadLocal<>();

    @Inject(method = "listResources", at = @At("HEAD"), require = 0)
    private void bootoptim$startList(PackType type, String namespace, String path, PackResources.ResourceOutput output, CallbackInfo ci) {
        PathPackResources self = (PathPackResources) (Object) this;
        ResourceOpenPhysicalProfiler.PackToken token = ResourceOpenPhysicalProfiler.beginPackScope(
                self.packId(), self.getClass().getName());
        if (token != null) bootoptim$listToken.set(token);
    }

    @Inject(method = "listResources", at = @At("RETURN"), require = 0)
    private void bootoptim$endList(PackType type, String namespace, String path, PackResources.ResourceOutput output, CallbackInfo ci) {
        ResourceOpenPhysicalProfiler.PackToken token = bootoptim$listToken.get();
        bootoptim$listToken.remove();
        ResourceOpenPhysicalProfiler.endPackList(token);
    }
}
