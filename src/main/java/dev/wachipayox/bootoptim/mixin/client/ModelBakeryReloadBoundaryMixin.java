package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler;
import dev.wachipayox.bootoptim.profiling.client.ResourceReloadBoundaryProfiler.Stamp;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only constructor and bake boundaries; no model data is inspected or changed. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryReloadBoundaryMixin {
    @Unique
    private static final ThreadLocal<Stamp> BOOTOPTIM$CONSTRUCTOR = new ThreadLocal<>();
    @Unique
    private Stamp bootoptim$bakeStart;

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void bootoptim$constructorStart(CallbackInfo ci) {
        if (ResourceReloadBoundaryProfiler.enabled()) {
            BOOTOPTIM$CONSTRUCTOR.set(ResourceReloadBoundaryProfiler.start());
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bootoptim$constructorEnd(CallbackInfo ci) {
        Stamp started = BOOTOPTIM$CONSTRUCTOR.get();
        BOOTOPTIM$CONSTRUCTOR.remove();
        ResourceReloadBoundaryProfiler.endSync("model_bakery_init", started);
    }

    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void bootoptim$bakeStart(CallbackInfo ci) {
        bootoptim$bakeStart = ResourceReloadBoundaryProfiler.start();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void bootoptim$bakeEnd(CallbackInfo ci) {
        Stamp started = bootoptim$bakeStart;
        bootoptim$bakeStart = null;
        ResourceReloadBoundaryProfiler.endSync("bake_models", started);
    }
}
