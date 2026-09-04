package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlLifecycleProfiler;
import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    /**
     * Redirect only the stock RegisterEvent dispatch callsite in GameData.
     *
     * <p>FML's ModLoader/ModContainer classes are already loaded before a normal mod mixin config can transform
     * them in this pack. This late callsite is still transformable. The enabled path calls the same public
     * postEventWithWrapInModOrder implementation that backs postEventWrapContainerInModOrder, with the same
     * active-container pre/post actions plus nanoTime accounting. Registry order, event-priority order and
     * mod-container order remain stock.</p>
     */
    @Redirect(
            method = "postRegisterEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/ModLoader;postEventWrapContainerInModOrder(Lnet/neoforged/bus/api/Event;)V",
                    remap = false),
            require = 0,
            remap = false)
    private static void bootoptim$profileRegisterEventDispatch(Event event) {
        RegisterEvent registerEvent = (RegisterEvent) event;
        if (!FmlRegistryProfiler.enabled()) {
            ModLoader.postEventWrapContainerInModOrder(registerEvent);
            return;
        }

        String registry = FmlRegistryProfiler.registryName(registerEvent);
        long[] startedNanos = { -1L };

        FmlRegistryProfiler.beginRegisterEvent(registerEvent);
        FmlLifecycleProfiler.begin("register_event:" + registry, PRE_RELOAD, CRITICAL);
        try {
            ModLoader.postEventWithWrapInModOrder(
                    registerEvent,
                    (modContainer, ignoredEvent) -> {
                        ModLoadingContext.get().setActiveContainer(modContainer);
                        startedNanos[0] = FmlRegistryProfiler.beginModContainerPost();
                    },
                    (modContainer, ignoredEvent) -> {
                        FmlRegistryProfiler.endModContainerPost(registerEvent, modContainer.getModId(), startedNanos[0]);
                        ModLoadingContext.get().setActiveContainer(null);
                    });
        } finally {
            FmlLifecycleProfiler.end("register_event:" + registry);
            FmlRegistryProfiler.endRegisterEvent(registerEvent);
        }
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
