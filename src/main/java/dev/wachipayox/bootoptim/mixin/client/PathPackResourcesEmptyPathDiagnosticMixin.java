package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
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
 * Diagnostic-only attribution for the exact-pack empty-root-path failures.
 *
 * <p>This intentionally does not cancel, replace, catch or otherwise alter the
 * PathPackResources lookup. It only emits an adjacent attribution record before
 * the stock validation/error path runs.</p>
 */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesEmptyPathDiagnosticMixin {
    private static final Logger BOOTOPTIM_DIAGNOSTIC_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private Path root;

    @Inject(method = "getRootResource", at = @At("HEAD"))
    private void bootoptim$attributeEmptyRootPath(
            String[] elements,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (elements.length != 1 || !elements[0].isEmpty()) {
            return;
        }

        String packId = ((PackResources) (Object) this).packId();
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(10)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" <- "));

        BOOTOPTIM_DIAGNOSTIC_LOGGER.error(
                "[BootOptim PathPack diagnostic] empty root path packId='{}' root='{}' stack={}",
                packId,
                this.root,
                stack);
    }
}
