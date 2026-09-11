package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceReloadDagTrace;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.AtlasSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only aggregate atlas scheduling/preparation boundary. */
@Mixin(AtlasSet.class)
abstract class AtlasSetDagMixin {
    @Unique private long bootoptim$generation;
    @Unique private long bootoptim$scheduleTask;

    @Inject(method = "scheduleLoad", at = @At("HEAD"))
    private void bootoptim$scheduleHead(CallbackInfoReturnable<?> cir) {
        bootoptim$generation = ResourceReloadDagTrace.activeModelGeneration();
        bootoptim$scheduleTask = ResourceReloadDagTrace.beginLexicalTask(
                "model_manager_atlas_schedule", bootoptim$generation, null);
        ResourceReloadDagTrace.beginAsyncPhase(
                "model_manager_atlas_preparation", bootoptim$generation, bootoptim$scheduleTask);
    }

    @Inject(method = "scheduleLoad", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void bootoptim$scheduleReturn(CallbackInfoReturnable<?> cir) {
        long generation = bootoptim$generation;
        long taskId = bootoptim$scheduleTask;
        bootoptim$generation = 0L;
        bootoptim$scheduleTask = 0L;
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_manager_atlas_schedule", "returned_future_map");
        Object value = cir.getReturnValue();
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        try {
            ResourceReloadDagTrace.observeAsyncPhaseMap("model_manager_atlas_preparation",
                    generation, taskId, (Map<?, ? extends CompletableFuture<?>>) map);
        } catch (ClassCastException ignored) {
            // Diagnostic fail-open: the stock return value and scheduling remain untouched.
        }
    }
}
