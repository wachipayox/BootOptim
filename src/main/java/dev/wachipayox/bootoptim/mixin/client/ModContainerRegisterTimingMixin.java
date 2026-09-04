package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.FmlRegistryProfiler;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Diagnostic-only wall attribution around the exact per-phase mod event-bus post already performed by FML.
 */
@Mixin(ModContainer.class)
abstract class ModContainerRegisterTimingMixin {
    @Redirect(
            method = "acceptEvent(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
            require = 0)
    private Event bootoptim$profileRegisterEventPost(IEventBus bus, EventPriority priority, Event event) {
        if (!(event instanceof RegisterEvent registerEvent) || !FmlRegistryProfiler.enabled()) {
            return bus.post(priority, event);
        }

        long startedNanos = FmlRegistryProfiler.beginModContainerPost();
        try {
            return bus.post(priority, event);
        } finally {
            String modId;
            try {
                modId = ((ModContainer) (Object) this).getModId();
            } catch (Throwable ignored) {
                modId = "unknown";
            }
            FmlRegistryProfiler.endModContainerPost(registerEvent, modId, startedNanos);
        }
    }
}
