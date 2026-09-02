package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.Decocraft3dItems;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Installs the guarded Decocraft item-sprite filter only on the minecraft:blocks atlas. */
@Mixin(SpriteSourceList.class)
abstract class SpriteSourceListDecocraft3dItemsMixin {
    @Mutable
    @Shadow
    @Final
    private List<SpriteSource> sources;

    @Inject(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/texture/atlas/SpriteSourceList;",
            at = @At("RETURN"),
            require = 1)
    private static void bootoptim$markDecocraftBlocksAtlas(
            ResourceManager resourceManager,
            ResourceLocation atlasInfo,
            CallbackInfoReturnable<SpriteSourceList> cir) {
        Decocraft3dItems.markAtlasList(atlasInfo, cir.getReturnValue());
    }

    @Inject(
            method = "list(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/List;",
            at = @At("HEAD"),
            require = 1)
    private void bootoptim$wrapDecocraftAtlasSources(
            ResourceManager resourceManager,
            CallbackInfoReturnable<List<Function<SpriteResourceLoader, SpriteContents>>> cir) {
        this.sources = Decocraft3dItems.wrapAtlasSources(this, this.sources);
    }
}
