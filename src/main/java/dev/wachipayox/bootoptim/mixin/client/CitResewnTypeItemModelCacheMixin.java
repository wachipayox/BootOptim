package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.CitResewnItemModelCache;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.Reader;
import java.util.Optional;

/** Optional, fail-open bridge for CITResewn's repeated base item-model parses. */
@Pseudo
@Mixin(targets = "schm.shsupercm.citresewn.defaults.cit.types.TypeItem", remap = false)
abstract class CitResewnTypeItemModelCacheMixin {
    @Redirect(
            method = "loadUnbakedAssets",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;"),
            require = 0)
    private Optional<Resource> bootoptim$rememberBaseModel(ResourceManager manager, ResourceLocation id) {
        Optional<Resource> result = manager.getResource(id);
        return CitResewnItemModelCache.remember(manager, id, result);
    }

    @Redirect(
            method = "loadUnbakedAssets",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;fromStream(Ljava/io/Reader;)Lnet/minecraft/client/renderer/block/model/BlockModel;"),
            require = 0)
    private BlockModel bootoptim$reuseParsedBaseModel(Reader reader) {
        return CitResewnItemModelCache.parse(reader);
    }
}
