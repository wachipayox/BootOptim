package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ModelReloadProfiler;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Measures atlas loading/stitching through actual asynchronous completion. */
@Mixin(AtlasSet.class)
abstract class AtlasSetProfilingMixin {
    @Inject(method = "scheduleLoad", at = @At("HEAD"))
    private void bootoptim$atlasPrepareStart(
            ResourceManager resourceManager,
            int mipLevel,
            Executor executor,
            CallbackInfoReturnable<Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>>> cir) {
        ModelReloadProfiler.begin("atlas_prepare");
    }

    @Inject(method = "scheduleLoad", at = @At("RETURN"))
    private void bootoptim$atlasPrepareEnd(
            ResourceManager resourceManager,
            int mipLevel,
            Executor executor,
            CallbackInfoReturnable<Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>>> cir) {
        Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> loads = cir.getReturnValue();
        if (loads == null) {
            ModelReloadProfiler.end("atlas_prepare", null);
            return;
        }
        CompletableFuture.allOf(loads.values().toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> ModelReloadProfiler.end("atlas_prepare", failure));
    }
}
