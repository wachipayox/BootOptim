package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourcePipelineProfiler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Diagnostic-only atlas source-list subdivision before sprite decode/stitching. */
@Mixin(SpriteSourceList.class)
abstract class SpriteSourceListResourceDecompositionMixin {
    @Unique private static final ThreadLocal<Long> bootoptim$loadStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$namesStart = ThreadLocal.withInitial(() -> -1L);
    @Unique private static final ThreadLocal<Long> bootoptim$listStart = ThreadLocal.withInitial(() -> -1L);

    @Inject(method = "load", at = @At("HEAD"), require = 0)
    private static void bootoptim$startDefinitionLoad(
            ResourceManager manager,
            ResourceLocation atlasInfo,
            CallbackInfoReturnable<SpriteSourceList> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        bootoptim$loadStart.set(ResourcePipelineProfiler.start());
        ResourcePipelineProfiler.enterContext("atlas.definition_load:" + atlasInfo);
    }

    @Inject(method = "load", at = @At("RETURN"), require = 0)
    private static void bootoptim$endDefinitionLoad(
            ResourceManager manager,
            ResourceLocation atlasInfo,
            CallbackInfoReturnable<SpriteSourceList> cir) {
        long started = bootoptim$loadStart.get();
        bootoptim$loadStart.remove();
        ResourcePipelineProfiler.exitContext();
        SpriteSourceList result = cir.getReturnValue();
        ResourcePipelineProfiler.registerAtlasList(result, atlasInfo);
        ResourcePipelineProfiler.recordWallScope("atlas.definition_load[" + atlasInfo + "]", started, 1L);
    }

    @Inject(method = "getSpriteNames", at = @At("HEAD"), require = 0)
    private void bootoptim$startSpriteNameDiscovery(
            ResourceManager manager,
            CallbackInfoReturnable<Set<ResourceLocation>> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        String atlas = ResourcePipelineProfiler.atlasListId(this);
        bootoptim$namesStart.set(ResourcePipelineProfiler.start());
        ResourcePipelineProfiler.enterContext("atlas.sprite_names:" + atlas);
    }

    @Inject(method = "getSpriteNames", at = @At("RETURN"), require = 0)
    private void bootoptim$endSpriteNameDiscovery(
            ResourceManager manager,
            CallbackInfoReturnable<Set<ResourceLocation>> cir) {
        long started = bootoptim$namesStart.get();
        bootoptim$namesStart.remove();
        ResourcePipelineProfiler.exitContext();
        Set<ResourceLocation> result = cir.getReturnValue();
        String atlas = ResourcePipelineProfiler.atlasListId(this);
        ResourcePipelineProfiler.recordWallScope(
                "atlas.sprite_names[" + atlas + "]",
                started,
                result == null ? -1L : result.size());
    }

    @Inject(method = "list", at = @At("HEAD"), require = 0)
    private void bootoptim$startSupplierDiscovery(
            ResourceManager manager,
            CallbackInfoReturnable<List<Function<SpriteResourceLoader, SpriteContents>>> cir) {
        if (!ResourcePipelineProfiler.enabled()) return;
        String atlas = ResourcePipelineProfiler.atlasListId(this);
        bootoptim$listStart.set(ResourcePipelineProfiler.start());
        ResourcePipelineProfiler.enterContext("atlas.supplier_discovery:" + atlas);
    }

    @Inject(method = "list", at = @At("RETURN"), require = 0)
    private void bootoptim$endSupplierDiscovery(
            ResourceManager manager,
            CallbackInfoReturnable<List<Function<SpriteResourceLoader, SpriteContents>>> cir) {
        long started = bootoptim$listStart.get();
        bootoptim$listStart.remove();
        ResourcePipelineProfiler.exitContext();
        List<?> result = cir.getReturnValue();
        String atlas = ResourcePipelineProfiler.atlasListId(this);
        ResourcePipelineProfiler.recordWallScope(
                "atlas.supplier_discovery[" + atlas + "]",
                started,
                result == null ? -1L : result.size());
    }
}
