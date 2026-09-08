package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EntityRendererReloadProfiler;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Times the existing EventListener.invoke call only for AddLayers while the diagnostic is active. */
@Mixin(value = EventBus.class, remap = false)
abstract class EventBusAddLayersInventoryMixin {
    @Redirect(
            method = "post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/EventListener;invoke(Lnet/neoforged/bus/api/Event;)V"),
            require = 0)
    private void bootoptim$profileAddLayersSubscriber(EventListener listener, Event event) {
        EntityRendererReloadProfiler.invokeListener(listener, event);
    }
}
