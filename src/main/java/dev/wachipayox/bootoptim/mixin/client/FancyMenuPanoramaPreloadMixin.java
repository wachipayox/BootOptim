package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FancyMenu 3.9.0 already decodes local PNG resources asynchronously, but its cubic-panorama
 * preloader waits for each image before starting the next one.
 *
 * <p>Production BootOptim starts the six existing suppliers for the current panorama at method
 * entry. This experiment optionally keeps one additional <em>contiguous</em> panorama in flight.
 * FancyMenu still performs its original get/wait/timeout/error loop in the original order, and
 * texture registration/upload remains lazy on the original thread.</p>
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuPanoramaPreloadMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final String BOOTOPTIM$PRELOADER_CLASS =
            "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader";

    @Unique
    private static final String BOOTOPTIM$CUBIC_SOURCE_CLASS =
            "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader$CubicPanoramaSource";

    @Unique
    private static final String BOOTOPTIM$PANORAMA_HANDLER_CLASS =
            "de.keksuccino.fancymenu.customization.panorama.PanoramaHandler";

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.fancymenuParallelPanoramaPreload", "true"));

    /**
     * Experimental only. 1 is the production behavior; 2 keeps the immediately following
     * panorama in the same contiguous panorama run warm. Values other than 2 intentionally fall
     * back to 1 so this branch can never fan out all registered panoramas by accident.
     */
    @Unique
    private static final int BOOTOPTIM$PANORAMA_WINDOW = bootoptim$parsePanoramaWindow();

    @Unique
    private static boolean bootoptim$compatibilityFailed;

    @Unique
    private static boolean bootoptim$failureReported;

    @Unique
    private static boolean bootoptim$rollingFailureReported;

    @Unique
    private static int bootoptim$panoramas;

    @Unique
    private static int bootoptim$suppliersPrelaunched;

    @Unique
    private static int bootoptim$prelaunchFailures;

    @Unique
    private static int bootoptim$aheadPanoramasPrelaunched;

    @Unique
    private static int bootoptim$plannedSources;

    @Unique
    private static int bootoptim$contiguousPanoramaPairs;

    @Unique
    private static int bootoptim$planCursor;

    @Unique
    private static int bootoptim$planMismatches;

    @Unique
    private static boolean bootoptim$planValid;

    @Unique
    private static boolean[] bootoptim$planLaunched = new boolean[0];

    @Unique
    private static final List<String> BOOTOPTIM$PLANNED_PANORAMA_NAMES = new ArrayList<>();

    @Unique
    private static final List<Integer> BOOTOPTIM$PLANNED_PANORAMA_RUNS = new ArrayList<>();

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
        bootoptim$aheadPanoramasPrelaunched = 0;
        bootoptim$plannedSources = 0;
        bootoptim$contiguousPanoramaPairs = 0;
        bootoptim$planCursor = 0;
        bootoptim$planMismatches = 0;
        bootoptim$planValid = false;
        bootoptim$planLaunched = new boolean[0];
        BOOTOPTIM$PLANNED_PANORAMA_NAMES.clear();
        BOOTOPTIM$PLANNED_PANORAMA_RUNS.clear();
        bootoptim$preloadStartNanos = System.nanoTime();

        if (BOOTOPTIM$PANORAMA_WINDOW > 1
                && !bootoptim$compatibilityFailed
                && waitForCompletedMillis > 0L) {
            bootoptim$buildRollingPlan();
        }
    }

    @Inject(method = "preLoadCubicPanorama", at = @At("HEAD"), require = 0)
    private static void bootoptim$prelaunchPanorama(
            @Coerce Object panoramaSource,
            long waitForCompletedMillis,
            CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || bootoptim$compatibilityFailed || waitForCompletedMillis <= 0L) {
            return;
        }

        bootoptim$panoramas++;

        final String currentPanoramaName;
        try {
            currentPanoramaName = bootoptim$getPanoramaName(panoramaSource);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            bootoptim$disableProductionPrelaunch(ex);
            return;
        }

        // Window 1 is intentionally the exact production mechanism: launch only this panorama's
        // six existing suppliers, then let FancyMenu run its stock ordered waits.
        if (BOOTOPTIM$PANORAMA_WINDOW <= 1 || !bootoptim$planValid) {
            bootoptim$launchPanorama(currentPanoramaName, false);
            return;
        }

        int currentIndex = bootoptim$planCursor++;
        if (currentIndex < 0
                || currentIndex >= BOOTOPTIM$PLANNED_PANORAMA_NAMES.size()
                || !currentPanoramaName.equals(BOOTOPTIM$PLANNED_PANORAMA_NAMES.get(currentIndex))) {
            bootoptim$planMismatches++;
            bootoptim$planValid = false;
            bootoptim$reportRollingFallback("plan_mismatch", null);
            // Fall back to the already-validated production behavior for this and later panoramas.
            bootoptim$launchPanorama(currentPanoramaName, false);
            return;
        }

        int run = BOOTOPTIM$PLANNED_PANORAMA_RUNS.get(currentIndex);
        int maxExclusive = Math.min(
                BOOTOPTIM$PLANNED_PANORAMA_NAMES.size(), currentIndex + BOOTOPTIM$PANORAMA_WINDOW);

        for (int index = currentIndex; index < maxExclusive; index++) {
            // Never jump over a slideshow, audio/video/text resource, or any other preload source.
            // The rolling window is constrained to one contiguous run of cubic panoramas.
            if (BOOTOPTIM$PLANNED_PANORAMA_RUNS.get(index) != run) {
                break;
            }
            if (bootoptim$planLaunched[index]) {
                continue;
            }

            boolean ahead = index > currentIndex;
            String panoramaName = BOOTOPTIM$PLANNED_PANORAMA_NAMES.get(index);
            if (!bootoptim$launchPanorama(panoramaName, ahead)) {
                if (ahead) {
                    // Ahead failure must not disable the existing production optimization. Stop
                    // rolling and let the stock/current-panorama path handle later entries.
                    bootoptim$planValid = false;
                    bootoptim$reportRollingFallback("ahead_prelaunch_failure", null);
                }
                break;
            }

            bootoptim$planLaunched[index] = true;
            if (ahead) {
                bootoptim$aheadPanoramasPrelaunched++;
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
                "BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD status={} panoramas={} suppliers_prelaunched={} failures={} preload_ms={} window={} plan_valid={} planned_sources={} planned_panoramas={} contiguous_pairs={} ahead_panoramas={} plan_mismatches={}",
                bootoptim$compatibilityFailed ? "disabled" : "enabled",
                bootoptim$panoramas,
                bootoptim$suppliersPrelaunched,
                bootoptim$prelaunchFailures,
                String.format(Locale.ROOT, "%.3f", elapsedMs),
                BOOTOPTIM$PANORAMA_WINDOW,
                bootoptim$planValid,
                bootoptim$plannedSources,
                BOOTOPTIM$PLANNED_PANORAMA_NAMES.size(),
                bootoptim$contiguousPanoramaPairs,
                bootoptim$aheadPanoramasPrelaunched,
                bootoptim$planMismatches);
        bootoptim$preloadStartNanos = 0L;
    }

    @Unique
    private static int bootoptim$parsePanoramaWindow() {
        String raw = System.getProperty("boot_optim.experimentFancyMenuPanoramaWindow", "1");
        try {
            return Integer.parseInt(raw.trim()) == 2 ? 2 : 1;
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    @Unique
    private static void bootoptim$buildRollingPlan() {
        try {
            Class<?> preloaderClass = Class.forName(BOOTOPTIM$PRELOADER_CLASS);
            Method getRegisteredResourceSources =
                    preloaderClass.getMethod("getRegisteredResourceSources", String.class);
            Object sourcesValue = getRegisteredResourceSources.invoke(null, new Object[] {null});
            if (!(sourcesValue instanceof Iterable<?> sources)) {
                bootoptim$reportRollingFallback("resource_sources_not_iterable", null);
                return;
            }

            int run = -1;
            boolean previousWasPanorama = false;
            for (Object source : sources) {
                bootoptim$plannedSources++;
                boolean isPanorama = source != null
                        && BOOTOPTIM$CUBIC_SOURCE_CLASS.equals(source.getClass().getName());
                if (!isPanorama) {
                    previousWasPanorama = false;
                    continue;
                }

                String panoramaName = bootoptim$getPanoramaName(source);
                if (!previousWasPanorama) {
                    run++;
                } else {
                    bootoptim$contiguousPanoramaPairs++;
                }
                BOOTOPTIM$PLANNED_PANORAMA_NAMES.add(panoramaName);
                BOOTOPTIM$PLANNED_PANORAMA_RUNS.add(run);
                previousWasPanorama = true;
            }

            bootoptim$planLaunched = new boolean[BOOTOPTIM$PLANNED_PANORAMA_NAMES.size()];
            bootoptim$planValid = !BOOTOPTIM$PLANNED_PANORAMA_NAMES.isEmpty();
            if (!bootoptim$planValid) {
                bootoptim$reportRollingFallback("no_panorama_sources", null);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            bootoptim$planValid = false;
            bootoptim$reportRollingFallback("plan_build_failure", ex);
        }
    }

    @Unique
    private static String bootoptim$getPanoramaName(Object panoramaSource)
            throws ReflectiveOperationException {
        Method getPanoramaName = panoramaSource.getClass().getMethod("getPanoramaName");
        Object panoramaNameValue = getPanoramaName.invoke(panoramaSource);
        if (panoramaNameValue instanceof String panoramaName) {
            return panoramaName;
        }
        throw new IllegalStateException("FancyMenu panorama source returned a non-string name");
    }

    @Unique
    private static boolean bootoptim$launchPanorama(String panoramaName, boolean ahead) {
        try {
            Class<?> panoramaHandlerClass = Class.forName(BOOTOPTIM$PANORAMA_HANDLER_CLASS);
            Method getPanorama = panoramaHandlerClass.getMethod("getPanorama", String.class);
            Object panorama = getPanorama.invoke(null, panoramaName);
            if (panorama == null) {
                return true;
            }

            Field suppliersField = panorama.getClass().getField("panoramaImageSuppliers");
            Object suppliersValue = suppliersField.get(panorama);
            if (!(suppliersValue instanceof Iterable<?> suppliers)) {
                throw new IllegalStateException("FancyMenu panorama supplier collection is not iterable");
            }

            for (Object supplier : suppliers) {
                if (supplier == null) {
                    continue;
                }
                Method get = supplier.getClass().getMethod("get");
                Object resource = get.invoke(supplier);
                if (resource != null) {
                    bootoptim$suppliersPrelaunched++;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            bootoptim$prelaunchFailures++;
            if (ahead) {
                bootoptim$reportRollingFallback("ahead_prelaunch_failure", ex);
            } else {
                bootoptim$disableProductionPrelaunch(ex);
            }
            return false;
        }
    }

    @Unique
    private static void bootoptim$disableProductionPrelaunch(Throwable ex) {
        bootoptim$compatibilityFailed = true;
        if (!bootoptim$failureReported) {
            bootoptim$failureReported = true;
            BOOTOPTIM$LOGGER.warn(
                    "BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD status=disabled reason=compatibility_failure",
                    ex);
        }
    }

    @Unique
    private static void bootoptim$reportRollingFallback(String reason, Throwable ex) {
        if (bootoptim$rollingFailureReported) {
            return;
        }
        bootoptim$rollingFailureReported = true;
        if (ex == null) {
            BOOTOPTIM$LOGGER.warn(
                    "BOOTOPTIM_FANCYMENU_PANORAMA_WINDOW status=fallback window={} reason={}",
                    BOOTOPTIM$PANORAMA_WINDOW,
                    reason);
        } else {
            BOOTOPTIM$LOGGER.warn(
                    "BOOTOPTIM_FANCYMENU_PANORAMA_WINDOW status=fallback window={} reason={}",
                    BOOTOPTIM$PANORAMA_WINDOW,
                    reason,
                    ex);
        }
    }
}
