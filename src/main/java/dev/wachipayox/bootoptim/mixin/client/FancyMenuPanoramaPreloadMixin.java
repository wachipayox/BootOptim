package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FancyMenu 3.9.0 already decodes local PNG resources asynchronously, but its
 * cubic-panorama preloader waits for each image before starting the next one.
 *
 * <p>This hook only starts the existing ResourceSupplier instances early. The
 * original FancyMenu loop still performs every get(), wait, timeout check and
 * error check in its original order. Texture registration/upload remains lazy
 * in FancyMenu's ITexture#getResourceLocation and is not moved off-thread.</p>
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuPanoramaPreloadMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.fancymenuParallelPanoramaPreload", "true"));

    @Unique
    private static boolean bootoptim$compatibilityFailed;

    @Unique
    private static boolean bootoptim$failureReported;

    @Unique
    private static int bootoptim$panoramas;

    @Unique
    private static int bootoptim$suppliersPrelaunched;

    @Unique
    private static int bootoptim$prelaunchFailures;

    @Unique
    private static long bootoptim$preloadStartNanos;

    @Inject(method = "preLoadAll", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginPreload(long waitForCompletedMillis, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED) {
            return;
        }
        bootoptim$panoramas = 0;
        bootoptim$suppliersPrelaunched = 0;
        bootoptim$prelaunchFailures = 0;
        bootoptim$preloadStartNanos = System.nanoTime();
    }

    @Inject(method = "preLoadCubicPanorama", at = @At("HEAD"), require = 0)
    private static void bootoptim$prelaunchPanorama(
            @Coerce Object panoramaSource,
            long waitForCompletedMillis,
            CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || bootoptim$compatibilityFailed || waitForCompletedMillis <= 0L) {
            return;
        }

        try {
            Method getPanoramaName = panoramaSource.getClass().getMethod("getPanoramaName");
            Object panoramaNameValue = getPanoramaName.invoke(panoramaSource);
            if (!(panoramaNameValue instanceof String panoramaName)) {
                throw new IllegalStateException("FancyMenu panorama source returned a non-string name");
            }

            ClassLoader loader = panoramaSource.getClass().getClassLoader();
            Class<?> panoramaHandlerClass = Class.forName(
                    "de.keksuccino.fancymenu.customization.panorama.PanoramaHandler", false, loader);
            Method getPanorama = panoramaHandlerClass.getMethod("getPanorama", String.class);
            Object panorama = getPanorama.invoke(null, panoramaName);
            if (panorama == null) {
                return;
            }

            Field suppliersField = panorama.getClass().getField("panoramaImageSuppliers");
            Object suppliersValue = suppliersField.get(panorama);
            if (!(suppliersValue instanceof Iterable<?> suppliers)) {
                throw new IllegalStateException("FancyMenu panorama supplier collection is not iterable");
            }

            bootoptim$panoramas++;
            for (Object supplier : suppliers) {
                if (supplier == null) {
                    continue;
                }
                try {
                    Method get = supplier.getClass().getMethod("get");
                    Object resource = get.invoke(supplier);
                    if (resource != null) {
                        bootoptim$suppliersPrelaunched++;
                    }
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    bootoptim$prelaunchFailures++;
                    throw ex;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            bootoptim$compatibilityFailed = true;
            if (!bootoptim$failureReported) {
                bootoptim$failureReported = true;
                BOOTOPTIM$LOGGER.warn(
                        "BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD status=disabled reason=compatibility_failure",
                        ex);
            }
        }
    }

    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$finishPreload(long waitForCompletedMillis, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || bootoptim$preloadStartNanos == 0L) {
            return;
        }

        double elapsedMs = (System.nanoTime() - bootoptim$preloadStartNanos) / 1_000_000.0D;
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD status={} panoramas={} suppliers_prelaunched={} failures={} preload_ms={}",
                bootoptim$compatibilityFailed ? "disabled" : "enabled",
                bootoptim$panoramas,
                bootoptim$suppliersPrelaunched,
                bootoptim$prelaunchFailures,
                String.format(java.util.Locale.ROOT, "%.3f", elapsedMs));
        bootoptim$preloadStartNanos = 0L;
    }
}
