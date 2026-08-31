package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadSubphaseProfiler;
import net.minecraft.client.resources.model.AtlasSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Diagnostic-only timing of the atlas preparation branch used by ModelManager. */
@Mixin(AtlasSet.class)
abstract class AtlasSetReloadSubphaseMixin {
    @Unique private long bootoptim$atlasStart = -1L;

    @Inject(method = "scheduleLoad", at = @At("HEAD"))
    private void bootoptim$startAtlases(CallbackInfoReturnable<?> cir) {
        if (ModelReloadSubphaseProfiler.enabled()) bootoptim$atlasStart = ModelReloadSubphaseProfiler.start();
    }

    @Inject(method = "scheduleLoad", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void bootoptim$observeAtlases(CallbackInfoReturnable<?> cir) {
        long started = bootoptim$atlasStart;
        bootoptim$atlasStart = -1L;
        Object result = cir.getReturnValue();
        if (started <= 0L || !(result instanceof Map<?, ?> map)) return;
        try {
            ModelReloadSubphaseProfiler.observeFutureMap(
                    "atlas_stitch", started, (Map<?, ? extends CompletableFuture<?>>) map);
        } catch (ClassCastException ignored) {
        }
    }
}
