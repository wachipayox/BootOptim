package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import net.neoforged.neoforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only timing for RegistryManager.postNewRegistryEvent(). */
@Mixin(RegistryManager.class)
abstract class RegistryManagerTimingMixin {
    @Inject(method = "postNewRegistryEvent", at = @At("HEAD"), require = 0)
    private static void bootoptim$beforePostNewRegistryEvent(CallbackInfo ci) {
        FmlLifecycleProfiler.begin("registry_post_new_registry_event", "pre_resource_reload", "critical_before_reload");
    }

    @Inject(method = "postNewRegistryEvent", at = @At("RETURN"), require = 0)
    private static void bootoptim$afterPostNewRegistryEvent(CallbackInfo ci) {
        FmlLifecycleProfiler.end("registry_post_new_registry_event");
    }
}
