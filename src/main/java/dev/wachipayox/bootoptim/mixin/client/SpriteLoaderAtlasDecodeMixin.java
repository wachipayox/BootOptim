package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.AtlasDecodeProfiler;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.client.textures.SpriteContentsConstructor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Diagnostic-only wrapper around the authoritative NeoForge SpriteResourceLoader. */
@Mixin(SpriteLoader.class)
abstract class SpriteLoaderAtlasDecodeMixin {
    @ModifyVariable(method = "runSpriteSuppliers", at = @At("HEAD"), argsOnly = true, index = 0, require = 0)
    private static SpriteResourceLoader bootoptim$profileSpriteLoader(SpriteResourceLoader delegate) {
        if (!AtlasDecodeProfiler.enabled() || delegate == null) {
            return delegate;
        }
        return (ResourceLocation id, Resource resource, SpriteContentsConstructor constructor) -> {
            AtlasDecodeProfiler.Context context = AtlasDecodeProfiler.begin(
                    id,
                    resource == null ? "unknown" : resource.sourcePackId());
            try {
                SpriteContents result = delegate.loadSprite(
                        id,
                        resource,
                        AtlasDecodeProfiler.wrapConstructor(constructor));
                return AtlasDecodeProfiler.finish(context, result);
            } catch (Throwable failure) {
                AtlasDecodeProfiler.abort(context);
                throw failure;
            }
        };
    }
}
