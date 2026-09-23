package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.optimization.client.DecocraftSpriteArchiveBatch;
import java.io.InputStream;
import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact selected pack path is preserved; only its lazy byte supplier may change. */
@Mixin(PathPackResources.class)
abstract class PathPackResourcesDecocraftSpriteBatchMixin {
    @WrapOperation(method = {"returnFileIfExists", "lambda$listPath$5"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/IoSupplier;create(Ljava/nio/file/Path;)Lnet/minecraft/server/packs/resources/IoSupplier;"),
            remap = false, require = 0)
    private static IoSupplier<InputStream> bootoptim$wrapSpriteSupplier(
            Path path, Operation<IoSupplier<InputStream>> original) {
        return DecocraftSpriteArchiveBatch.wrap(path, original.call(path));
    }
}
