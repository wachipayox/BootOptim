package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.YaclImagePreparation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Dispatches YACL's image {@code prepare} to the background executor for manual reloads after game load.
 * Observation of the result is unchanged: the same future feeds YACL's preparation barrier and apply step.
 * {@code require = 0}: if YACL changes this call site the injection is skipped and stock behaviour remains.
 */
@Pseudo
@Mixin(targets = "dev.isxander.yacl3.gui.image.YACLImageReloadListener", remap = false)
abstract class YaclImagePreparationMixin {
    @WrapOperation(method = "reload0", at = @At(value = "INVOKE", target =
            "Ldev/isxander/yacl3/gui/image/YACLImageReloadListener;prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            remap = false), require = 0, remap = false)
    private CompletableFuture<?> bootoptim$dispatchPrepare(@Coerce Object listener, ResourceManager resources, Executor executor,
            Operation<CompletableFuture<?>> original) {
        if (!YaclImagePreparation.shouldDispatch()) {
            return original.call(listener, resources, executor);
        }
        return YaclImagePreparation.dispatch(() -> original.call(listener, resources, executor), executor);
    }
}
