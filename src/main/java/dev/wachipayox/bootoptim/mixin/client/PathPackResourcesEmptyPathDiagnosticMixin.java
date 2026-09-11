package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * PathPackResources lookup. It only emits an adjacent attribution record before
 * the stock getResource path reaches its validation/error handling.</p>
 */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesEmptyPathDiagnosticMixin {
    private static final Logger BOOTOPTIM_DIAGNOSTIC_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private Path root;

    @Inject(
            method = "getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;",
            at = @At("HEAD"))
    private void bootoptim$attributeEmptyResourcePath(
            PackType packType,
            ResourceLocation location,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (!location.getPath().isEmpty()) {
            return;
        }

        String packId = ((PackResources) (Object) this).packId();
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(10)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" <- "));

        BOOTOPTIM_DIAGNOSTIC_LOGGER.error(
                "[BootOptim PathPack diagnostic] empty resource path packId='{}' root='{}' packType={} namespace='{}' location='{}' stack={}",
                packId,
                this.root,
                packType,
                location.getNamespace(),
                location,
                stack);
    }
}
