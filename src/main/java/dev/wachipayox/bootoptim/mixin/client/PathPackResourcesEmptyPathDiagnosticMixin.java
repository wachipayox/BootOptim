package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Diagnostic-only attribution for the exact-pack empty resource-path failures.
 *
 * <p>This intentionally does not cancel, replace, catch or otherwise alter the
 * PathPackResources lookup. It emits an adjacent attribution record at the
 * static helper that performs FileUtil.decomposePath and emits the stock error.</p>
 */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesEmptyPathDiagnosticMixin {
    private static final Logger BOOTOPTIM_DIAGNOSTIC_LOGGER = LogUtils.getLogger();

    @Inject(
            method = "getResource(Lnet/minecraft/resources/ResourceLocation;Ljava/nio/file/Path;)Lnet/minecraft/server/packs/resources/IoSupplier;",
            at = @At("HEAD"))
    private static void bootoptim$attributeEmptyResourcePath(
            ResourceLocation location,
            Path basePath,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!location.getPath().isEmpty()) {
            return;
        }

        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(12)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" <- "));

        BOOTOPTIM_DIAGNOSTIC_LOGGER.error(
                "[BootOptim PathPack diagnostic] empty resource path basePath='{}' namespace='{}' location='{}' stack={}",
                basePath,
                location.getNamespace(),
                location,
                stack);
    }
}
