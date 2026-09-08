package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelDagDependencies;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadDagTrace;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only lexical ModelBakery construction and bake boundaries. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryDagMixin {
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$CTOR_TASK = new ThreadLocal<>();
    @Unique private long bootoptim$bakeTask;

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$ctorHead(CallbackInfo ci) {
        long generation = ResourceReloadDagTrace.activeModelGeneration();
        BOOTOPTIM$CTOR_TASK.set(ResourceReloadDagTrace.beginLexicalTask(
                "model_bakery_prepare", generation, null));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$ctorReturn(CallbackInfo ci) {
        Long value = BOOTOPTIM$CTOR_TASK.get();
        BOOTOPTIM$CTOR_TASK.remove();
        long taskId = value == null ? 0L : value.longValue();
        ResourceReloadDagTrace.endLexicalTask(taskId,
                "model_bakery_prepare", "constructor_return");
        ModelDagDependencies.rememberBakeryPreparation(taskId);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$bakeHead(CallbackInfo ci) {
        bootoptim$bakeTask = ResourceReloadDagTrace.beginLexicalTask(
                "model_bakery_bake", ResourceReloadDagTrace.activeModelGeneration(), null);
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$bakeReturn(CallbackInfo ci) {
        long taskId = bootoptim$bakeTask;
        bootoptim$bakeTask = 0L;
        ResourceReloadDagTrace.endLexicalTask(taskId, "model_bakery_bake", "bake_models_return");
    }
}
