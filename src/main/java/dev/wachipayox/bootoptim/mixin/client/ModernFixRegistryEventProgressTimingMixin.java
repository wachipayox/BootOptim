package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only observer for ModernFix's existing registry-event progress dispatch.
 *
 * <p>ModernFix already owns the GameData callsite in the exact pack. This mixin intentionally runs at lower
 * priority and only injects timers into methods ModernFix has already merged into GameData. It never redirects,
 * wraps, replaces, reorders or invokes the event bus.</p>
 */
@Mixin(value = GameData.class, priority = 900)
abstract class ModernFixRegistryEventProgressTimingMixin {
    private static final String PRE_RELOAD = "pre_resource_reload";
    private static final String CRITICAL = "critical_before_reload";

    @Inject(method = "postWithProgressBar", at = @At("HEAD"), require = 0, remap = false)
    private static void bootoptim$beforeModernFixRegisterEvent(Event event, CallbackInfo ci) {
        if (!(event instanceof RegisterEvent registerEvent) || !FmlRegistryProfiler.enabled()) {
            return;
        }
        String registry = FmlRegistryProfiler.registryName(registerEvent);
        FmlRegistryProfiler.beginRegisterEvent(registerEvent);
        FmlLifecycleProfiler.begin("register_event:" + registry, PRE_RELOAD, CRITICAL);
    }

    @Inject(method = "postWithProgressBar", at = @At("RETURN"), require = 0, remap = false)
    private static void bootoptim$afterModernFixRegisterEvent(Event event, CallbackInfo ci) {
        if (!(event instanceof RegisterEvent registerEvent) || !FmlRegistryProfiler.enabled()) {
            return;
        }
        String registry = FmlRegistryProfiler.registryName(registerEvent);
        FmlLifecycleProfiler.end("register_event:" + registry);
        FmlRegistryProfiler.endRegisterEvent(registerEvent);
    }

    @Inject(
            method = "lambda$postWithProgressBar$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModContainer;acceptEvent(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$beforeExistingContainerPost(CallbackInfo ci) {
        FmlRegistryProfiler.beginActiveModContainerPost();
    }

    @Inject(
            method = "lambda$postWithProgressBar$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModContainer;acceptEvent(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)V",
                    shift = At.Shift.AFTER,
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$afterExistingContainerPost(CallbackInfo ci) {
        FmlRegistryProfiler.endActiveModContainerPost();
    }
}
