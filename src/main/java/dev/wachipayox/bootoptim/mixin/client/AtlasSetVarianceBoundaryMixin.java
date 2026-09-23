package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.AtlasSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only timing of the aggregate atlas preparation future. */
@Mixin(AtlasSet.class)
abstract class AtlasSetVarianceBoundaryMixin {
    @Unique
    private VarianceProbe.Stamp bootoptim$atlasStart;

    @Inject(method = "scheduleLoad", at = @At("HEAD"))
    private void bootoptim$atlasStart(CallbackInfoReturnable<?> cir) {
        bootoptim$atlasStart = ResourceReloadBoundaryProfiler.start("atlas_schedule_load");
    }

    @Inject(method = "scheduleLoad", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void bootoptim$atlasReturned(CallbackInfoReturnable<?> cir) {
        VarianceProbe.Stamp started = bootoptim$atlasStart;
        bootoptim$atlasStart = null;
        Object value = cir.getReturnValue();
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        try {
            ResourceReloadBoundaryProfiler.observeFutureMap(
                    "atlas_schedule_load",
                    started,
                    (Map<?, ? extends CompletableFuture<?>>) map);
        } catch (ClassCastException ignored) {
            // Diagnostic fail-open: stock return value and reload path are untouched.
        }
    }
}
