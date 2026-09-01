package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourcePipelineProfiler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Diagnostic-only atlas decomposition: source future, per-sprite load/decode and final stitch. */
@Mixin(SpriteLoader.class)
abstract class SpriteLoaderResourceDecompositionMixin {
    @Shadow @Final private ResourceLocation location;

    @Unique private long bootoptim$loadAndStitchStart = -1L;
    @Unique private long bootoptim$stitchStart = -1L;

    @Inject(
            method = "loadAndStitch(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceLocation;ILjava/util/concurrent/Executor;Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            require = 0)
    private void bootoptim$startLoadAndStitch(
            ResourceManager manager,
            ResourceLocation atlasInfo,
            int mipLevel,
            Executor executor,
            Collection<MetadataSectionSerializer<?>> metadataSections,
            CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$loadAndStitchStart = ResourcePipelineProfiler.start();
        }
    }

    @Inject(
            method = "loadAndStitch(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceLocation;ILjava/util/concurrent/Executor;Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$observeLoadAndStitch(
            ResourceManager manager,
            ResourceLocation atlasInfo,
            int mipLevel,
            Executor executor,
            Collection<MetadataSectionSerializer<?>> metadataSections,
            CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir) {
        long started = bootoptim$loadAndStitchStart;
        bootoptim$loadAndStitchStart = -1L;
        CompletableFuture<SpriteLoader.Preparations> future = cir.getReturnValue();
        if (started <= 0L || future == null) {
            return;
        }
        String atlas = this.location == null ? String.valueOf(atlasInfo) : this.location.toString();
        future.whenComplete((preparations, failure) -> ResourcePipelineProfiler.recordWallScope(
                "atlas.load_and_stitch[" + atlas + "]",
                started,
                preparations == null || preparations.regions() == null ? -1L : preparations.regions().size()));
    }

    @ModifyVariable(
            method = "runSpriteSuppliers",
            at = @At("HEAD"),
            argsOnly = true,
            index = 0,
            require = 0)
    private static SpriteResourceLoader bootoptim$wrapSpriteLoader(SpriteResourceLoader delegate) {
        if (!ResourcePipelineProfiler.enabled() || delegate == null) {
            return delegate;
        }
        return (ResourceLocation id, Resource resource) -> {
            long started = ResourcePipelineProfiler.start();
            try {
                return delegate.loadSprite(id, resource);
            } finally {
                ResourcePipelineProfiler.recordResource(
                        "atlas.sprite_load_inclusive",
                        id,
                        resource == null ? "unknown" : resource.sourcePackId(),
                        started);
            }
        };
    }

    @Inject(method = "stitch", at = @At("HEAD"), require = 0)
    private void bootoptim$startStitch(
            List<SpriteContents> contents,
            int mipLevel,
            Executor executor,
            CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        if (ResourcePipelineProfiler.enabled()) {
            bootoptim$stitchStart = ResourcePipelineProfiler.start();
        }
    }

    @Inject(method = "stitch", at = @At("RETURN"), require = 0)
    private void bootoptim$endStitch(
            List<SpriteContents> contents,
            int mipLevel,
            Executor executor,
            CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        long started = bootoptim$stitchStart;
        bootoptim$stitchStart = -1L;
        String atlas = this.location == null ? "unknown" : this.location.toString();
        ResourcePipelineProfiler.recordWallScope(
                "atlas.stitch[" + atlas + "]",
                started,
                contents == null ? -1L : contents.size());
    }
}
