package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.neoforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only timings inside GameData registry initialization. */
@Mixin(GameData.class)
abstract class GameDataRegistryTimingMixin {
    private static final String PRE_RELOAD = "pre_resource_reload";
    private static final String CRITICAL = "critical_before_reload";

    @Inject(method = "unfreezeData", at = @At("HEAD"), require = 0)
    private static void bootoptim$beforeUnfreeze(CallbackInfo ci) {
        FmlLifecycleProfiler.begin("registry_unfreeze", PRE_RELOAD, CRITICAL);
    }

    @Inject(method = "unfreezeData", at = @At("RETURN"), require = 0)
    private static void bootoptim$afterUnfreeze(CallbackInfo ci) {
        FmlLifecycleProfiler.end("registry_unfreeze");
    }

    @Inject(method = "postRegisterEvents", at = @At("HEAD"), require = 0)
    private static void bootoptim$beforePostRegisterEvents(CallbackInfo ci) {
        FmlRegistryProfiler.beginPostRegisterEvents();
        FmlLifecycleProfiler.begin("registry_post_register_events", PRE_RELOAD, CRITICAL);
    }

    @Inject(method = "postRegisterEvents", at = @At("RETURN"), require = 0)
    private static void bootoptim$afterPostRegisterEvents(CallbackInfo ci) {
        FmlLifecycleProfiler.end("registry_post_register_events");
        FmlRegistryProfiler.endPostRegisterEvents();
    }

    @Inject(method = "freezeData", at = @At("HEAD"), require = 0)
    private static void bootoptim$beforeFreeze(CallbackInfo ci) {
        FmlLifecycleProfiler.begin("registry_freeze", PRE_RELOAD, CRITICAL);
    }

    @Inject(method = "freezeData", at = @At("RETURN"), require = 0)
    private static void bootoptim$afterFreeze(CallbackInfo ci) {
        FmlLifecycleProfiler.end("registry_freeze");
    }
}
