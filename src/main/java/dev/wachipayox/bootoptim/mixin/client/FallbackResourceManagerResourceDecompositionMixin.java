package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourcePipelineProfiler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Diagnostic-only namespace attribution for ResourceManager work executed inside a profiled resource context.
 * No calls outside the ModelManager/atlas contexts are timed.
 */
@Mixin(FallbackResourceManager.class)
abstract class FallbackResourceManagerResourceDecompositionMixin {
    @Shadow @Final private String namespace;

    @Unique private static final ThreadLocal<Long> bootoptim$listStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$stackListStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$getStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$getStackStart = ThreadLocal.withInitial(() -> -1L);

    @Inject(method = "listResources", at = @At("HEAD"), require = 0)
    private void bootoptim$startListResources(
            String path,
            Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, Resource>> cir) {
        bootoptim$listStart.set(ResourcePipelineProfiler.currentContext() == null ? -1L : ResourcePipelineProfiler.start());
    }

    @Inject(method = "listResources", at = @At("RETURN"), require = 0)
    private void bootoptim$endListResources(
            String path,
            Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, Resource>> cir) {
        long started = bootoptim$listStart.get();
        bootoptim$listStart.remove();
        Map<?, ?> result = cir.getReturnValue();
        ResourcePipelineProfiler.recordNamespace(
                "listResources(" + path + ")",
                this.namespace,
                started,
                result == null ? -1L : result.size());
    }

    @Inject(method = "listResourceStacks", at = @At("HEAD"), require = 0)
    private void bootoptim$startListResourceStacks(
            String path,
            Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, List<Resource>>> cir) {
        bootoptim$stackListStart.set(ResourcePipelineProfiler.currentContext() == null ? -1L : ResourcePipelineProfiler.start());
    }

    @Inject(method = "listResourceStacks", at = @At("RETURN"), require = 0)
    private void bootoptim$endListResourceStacks(
            String path,
            Predicate<ResourceLocation> filter,
            CallbackInfoReturnable<Map<ResourceLocation, List<Resource>>> cir) {
        long started = bootoptim$stackListStart.get();
        bootoptim$stackListStart.remove();
        Map<?, ?> result = cir.getReturnValue();
        ResourcePipelineProfiler.recordNamespace(
                "listResourceStacks(" + path + ")",
                this.namespace,
                started,
                result == null ? -1L : result.size());
    }

    @Inject(method = "getResource", at = @At("HEAD"), require = 0)
    private void bootoptim$startGetResource(
            ResourceLocation id,
            CallbackInfoReturnable<Optional<Resource>> cir) {
        bootoptim$getStart.set(ResourcePipelineProfiler.currentContext() == null ? -1L : ResourcePipelineProfiler.start());
    }

    @Inject(method = "getResource", at = @At("RETURN"), require = 0)
    private void bootoptim$endGetResource(
            ResourceLocation id,
            CallbackInfoReturnable<Optional<Resource>> cir) {
        long started = bootoptim$getStart.get();
        bootoptim$getStart.remove();
        Optional<?> result = cir.getReturnValue();
        ResourcePipelineProfiler.recordNamespace(
                "getResource",
                this.namespace,
                started,
                result != null && result.isPresent() ? 1L : 0L);
    }

    @Inject(method = "getResourceStack", at = @At("HEAD"), require = 0)
    private void bootoptim$startGetResourceStack(
            ResourceLocation id,
            CallbackInfoReturnable<List<Resource>> cir) {
        bootoptim$getStackStart.set(ResourcePipelineProfiler.currentContext() == null ? -1L : ResourcePipelineProfiler.start());
    }

    @Inject(method = "getResourceStack", at = @At("RETURN"), require = 0)
    private void bootoptim$endGetResourceStack(
            ResourceLocation id,
            CallbackInfoReturnable<List<Resource>> cir) {
        long started = bootoptim$getStackStart.get();
        bootoptim$getStackStart.remove();
        List<?> result = cir.getReturnValue();
        ResourcePipelineProfiler.recordNamespace(
                "getResourceStack",
                this.namespace,
                started,
                result == null ? -1L : result.size());
    }
}
