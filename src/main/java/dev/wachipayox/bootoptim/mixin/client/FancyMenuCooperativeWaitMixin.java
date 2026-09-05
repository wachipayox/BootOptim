package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.compat.client.FancyMenuCooperativeWait;
import dev.wachipayox.bootoptim.compat.client.FancyMenuCooperativeWait.Family;
import dev.wachipayox.bootoptim.compat.client.FancyMenuCooperativeWait.Snapshot;
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
 * Experimental replacement for the five stock FancyMenu preload wait call sites.
 *
 * <p>The experiment config is not applied at all unless the explicit JVM property is true.
 * Within the first startup preLoadAll scope, MixinExtras preserves the chained original operation
 * so an access mismatch can fail open to FancyMenu's exact stock invocation once.</p>
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.util.resource.preload.ResourcePreLoader", remap = false)
abstract class FancyMenuCooperativeWaitMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final String BOOTOPTIM$RESOURCE_WAIT =
            "Lde/keksuccino/fancymenu/util/resource/Resource;waitForLoadingCompletedOrFailed(J)V";
    @Unique
    private static final String BOOTOPTIM$TEXTURE_WAIT =
            "Lde/keksuccino/fancymenu/util/resource/resources/texture/ITexture;waitForLoadingCompletedOrFailed(J)V";

    @Unique
    private static boolean bootoptim$claimedFirstPreload;
    @Unique
    private static Thread bootoptim$owner;
    @Unique
    private static int bootoptim$depth;

    @Inject(method = "preLoadAll", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginPreLoad(CallbackInfo ci) {
        Thread current = Thread.currentThread();
        if (!bootoptim$claimedFirstPreload) {
            bootoptim$claimedFirstPreload = true;
            bootoptim$owner = current;
            bootoptim$depth = 1;
            FancyMenuCooperativeWait.beginPreload();
        } else if (bootoptim$owner == current && bootoptim$depth > 0) {
            // A nested/re-entrant preload is left stock; only the outer startup scope is experimental.
            bootoptim$depth++;
        }
    }

    @WrapOperation(
            method = "preLoadAll",
            at = @At(value = "INVOKE", target = BOOTOPTIM$RESOURCE_WAIT, remap = false),
            remap = false,
            require = 0,
            expect = 1,
            allow = 1)
    private static void bootoptim$ordinaryWait(
            @Coerce Object resource, long timeoutMillis, Operation<Void> original) {
        bootoptim$routeWait(resource, timeoutMillis, Family.ORDINARY, original);
    }

    @WrapOperation(
            method = "preLoadSlideshow",
            at = @At(value = "INVOKE", target = BOOTOPTIM$TEXTURE_WAIT, remap = false),
            remap = false,
            require = 0,
            expect = 2,
            allow = 2)
    private static void bootoptim$slideshowWait(
            @Coerce Object resource, long timeoutMillis, Operation<Void> original) {
        bootoptim$routeWait(resource, timeoutMillis, Family.SLIDESHOW, original);
    }

    @WrapOperation(
            method = "preLoadCubicPanorama",
            at = @At(value = "INVOKE", target = BOOTOPTIM$TEXTURE_WAIT, remap = false),
            remap = false,
            require = 0,
            expect = 2,
            allow = 2)
    private static void bootoptim$panoramaWait(
            @Coerce Object resource, long timeoutMillis, Operation<Void> original) {
        bootoptim$routeWait(resource, timeoutMillis, Family.PANORAMA, original);
    }

    @Unique
    private static void bootoptim$routeWait(
            Object resource, long timeoutMillis, Family family, Operation<Void> original) {
        if (bootoptim$owner == Thread.currentThread()
                && bootoptim$depth == 1
                && FancyMenuCooperativeWait.tryWait(resource, timeoutMillis, family)) {
            return;
        }
        // Exact chained operation: this is both the inactive/nested path and access fail-open path.
        original.call(resource, timeoutMillis);
    }

    @Inject(method = "preLoadAll", at = @At("RETURN"), require = 0)
    private static void bootoptim$finishPreLoad(CallbackInfo ci) {
        Thread current = Thread.currentThread();
        if (bootoptim$owner != current || bootoptim$depth <= 0) {
            return;
        }
        if (bootoptim$depth > 1) {
            bootoptim$depth--;
            return;
        }

        Snapshot snapshot = FancyMenuCooperativeWait.finishPreload();
        bootoptim$owner = null;
        bootoptim$depth = 0;
        if (snapshot == null) {
            return;
        }

        boolean fallback = snapshot.stockFallbacks() != 0L
                || snapshot.interruptFallbacks() != 0L
                || snapshot.virtualFallbacks() != 0L
                || snapshot.parkFailures() != 0L
                || snapshot.timerFallbacks() != 0L;
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_FANCYMENU_COOPERATIVE_WAIT status={} cpu={} wait_calls={} cooperative_calls={} "
                        + "preload_wall_ms={} preload_cpu_ms={} "
                        + "ordinary_calls={} ordinary_cooperative={} ordinary_wall_ms={} ordinary_cpu_ms={} "
                        + "slideshow_calls={} slideshow_cooperative={} slideshow_wall_ms={} slideshow_cpu_ms={} "
                        + "panorama_calls={} panorama_cooperative={} panorama_wall_ms={} panorama_cpu_ms={} "
                        + "park_calls={} deadline_spins={} interrupt_fallbacks={} virtual_fallbacks={} "
                        + "park_failures={} timer_fallbacks={} access_failures={} stock_fallbacks={} quantum_ns={}",
                fallback ? "fallback" : "ok",
                snapshot.cpuAvailable() ? "available" : "unavailable",
                snapshot.waitCalls(),
                snapshot.cooperativeCalls(),
                bootoptim$millis(snapshot.preloadWallNanos()),
                snapshot.cpuAvailable() ? bootoptim$millis(snapshot.preloadCpuNanos()) : "unavailable",
                snapshot.ordinaryCalls(),
                snapshot.ordinaryCooperativeCalls(),
                bootoptim$millis(snapshot.ordinaryWallNanos()),
                snapshot.cpuAvailable() ? bootoptim$millis(snapshot.ordinaryCpuNanos()) : "unavailable",
                snapshot.slideshowCalls(),
                snapshot.slideshowCooperativeCalls(),
                bootoptim$millis(snapshot.slideshowWallNanos()),
                snapshot.cpuAvailable() ? bootoptim$millis(snapshot.slideshowCpuNanos()) : "unavailable",
                snapshot.panoramaCalls(),
                snapshot.panoramaCooperativeCalls(),
                bootoptim$millis(snapshot.panoramaWallNanos()),
                snapshot.cpuAvailable() ? bootoptim$millis(snapshot.panoramaCpuNanos()) : "unavailable",
                snapshot.parkCalls(),
                snapshot.deadlineSpins(),
                snapshot.interruptFallbacks(),
                snapshot.virtualFallbacks(),
                snapshot.parkFailures(),
                snapshot.timerFallbacks(),
                snapshot.accessFailures(),
                snapshot.stockFallbacks(),
                FancyMenuCooperativeWait.PARK_QUANTUM_NANOS);
    }

    @Unique
    private static String bootoptim$millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }
}
