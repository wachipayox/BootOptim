package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only timings inside GameData registry initialization. */
@Mixin(value = GameData.class, priority = 1100)
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

    /**
     * Observe the stock dispatch call before ModernFix redirects it. BootOptim never invokes or replaces the bus.
     * Priority 1100 ensures these callback instructions are inserted before ModernFix's priority-1000 Redirect is
     * applied to the same invoke. MixinExtras only captures the existing RegisterEvent local.
     */
    @Inject(
            method = "postRegisterEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$beforeRegisterEventDispatch(CallbackInfo ci, @Local RegisterEvent registerEvent) {
        if (!FmlRegistryProfiler.enabled()) {
            return;
        }
        String registry = FmlRegistryProfiler.registryName(registerEvent);
        FmlRegistryProfiler.beginRegisterEvent(registerEvent);
        FmlLifecycleProfiler.begin("register_event:" + registry, PRE_RELOAD, CRITICAL);
    }

    @Inject(
            method = "postRegisterEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.AFTER,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$afterRegisterEventDispatch(CallbackInfo ci, @Local RegisterEvent registerEvent) {
        if (!FmlRegistryProfiler.enabled()) {
            return;
        }
        String registry = FmlRegistryProfiler.registryName(registerEvent);
        FmlLifecycleProfiler.end("register_event:" + registry);
        FmlRegistryProfiler.endRegisterEvent(registerEvent);
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
