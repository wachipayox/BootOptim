package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.Decocraft3dItems;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces only audited, non-overridden Decocraft generated-item models after stock JSON loading. */
@Mixin(ModelManager.class)
abstract class ModelManagerDecocraft3dItemsMixin {
    @Inject(
            method = "loadBlockModels(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private static void bootoptim$remapDecocraftItems(
            ResourceManager resourceManager,
            Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<ResourceLocation, BlockModel>>> cir) {
        CompletableFuture<Map<ResourceLocation, BlockModel>> loaded = cir.getReturnValue();
        if (loaded == null) {
            return;
        }
        cir.setReturnValue(loaded.thenApply(models -> Decocraft3dItems.remapModels(resourceManager, models)));
    }
}
