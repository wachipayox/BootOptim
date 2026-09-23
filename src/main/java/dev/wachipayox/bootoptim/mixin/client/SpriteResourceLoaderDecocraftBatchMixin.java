package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.DecocraftSpriteArchiveBatch;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.client.textures.SpriteContentsConstructor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact NeoForge 1.21.1 stock sprite loader callsite; custom loaders are untouched. */
@Mixin(SpriteResourceLoader.class)
abstract class SpriteResourceLoaderDecocraftBatchMixin {
    @WrapOperation(method = "lambda$create$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;open()Ljava/io/InputStream;"),
            remap = false, require = 0)
    private static InputStream bootoptim$openSprite(
            Resource resource, Operation<InputStream> original,
            Collection<?> metadataSections, ResourceLocation id, Resource selectedResource,
            SpriteContentsConstructor constructor) throws IOException {
        return DecocraftSpriteArchiveBatch.open(resource, id, () -> original.call(resource));
    }
}
