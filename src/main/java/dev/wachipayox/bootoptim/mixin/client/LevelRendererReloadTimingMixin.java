package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only split of the first LevelRenderer resource reload.
 *
 * <p>The slow-laptop reload critical-path trace attributed roughly 6.5 seconds of ordered
 * post-turn wall to LevelRenderer, but that interval was not split between outline post-chain,
 * transparency post-chain and other reload work. This mixin measures only those existing methods;
 * it does not skip, reorder or move any render work.</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererReloadTimingMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileLevelRendererReload", "false"));

    @Unique
    private static final ThreadMXBean BOOTOPTIM$THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    @Unique
    private boolean bootoptim$reloadActive;

    @Unique
    private boolean bootoptim$reloadReported;

    @Unique
    private long bootoptim$reloadStartNanos;

    @Unique
    private long bootoptim$reloadStartCpuNanos;

    @Unique
    private long bootoptim$outlineStartNanos;

    @Unique
    private long bootoptim$outlineStartCpuNanos;

    @Unique
    private long bootoptim$outlineNanos;

    @Unique
    private long bootoptim$outlineCpuNanos;

    @Unique
    private int bootoptim$outlineCalls;

    @Unique
    private long bootoptim$transparencyStartNanos;

    @Unique
    private long bootoptim$transparencyStartCpuNanos;

    @Unique
    private long bootoptim$transparencyNanos;

    @Unique
    private long bootoptim$transparencyCpuNanos;

    @Unique
    private int bootoptim$transparencyCalls;

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), require = 1)
    private void bootoptim$beginReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$reloadReported) {
            return;
        }

        this.bootoptim$reloadActive = true;
        this.bootoptim$reloadStartNanos = System.nanoTime();
        this.bootoptim$reloadStartCpuNanos = bootoptim$currentThreadCpuNanos();
        this.bootoptim$outlineNanos = 0L;
        this.bootoptim$outlineCpuNanos = 0L;
        this.bootoptim$outlineCalls = 0;
        this.bootoptim$transparencyNanos = 0L;
        this.bootoptim$transparencyCpuNanos = 0L;
        this.bootoptim$transparencyCalls = 0;
    }

    @Inject(method = "initOutline", at = @At("HEAD"), require = 1)
    private void bootoptim$beginOutline(CallbackInfo ci) {
        if (!this.bootoptim$reloadActive) {
            return;
        }
        this.bootoptim$outlineStartNanos = System.nanoTime();
        this.bootoptim$outlineStartCpuNanos = bootoptim$currentThreadCpuNanos();
    }

    @Inject(method = "initOutline", at = @At("RETURN"), require = 1)
    private void bootoptim$finishOutline(CallbackInfo ci) {
        if (!this.bootoptim$reloadActive || this.bootoptim$outlineStartNanos == 0L) {
            return;
        }
        this.bootoptim$outlineNanos += System.nanoTime() - this.bootoptim$outlineStartNanos;
        this.bootoptim$outlineCpuNanos += bootoptim$cpuDelta(this.bootoptim$outlineStartCpuNanos);
        this.bootoptim$outlineCalls++;
        this.bootoptim$outlineStartNanos = 0L;
        this.bootoptim$outlineStartCpuNanos = -1L;
    }

    @Inject(method = "initTransparency", at = @At("HEAD"), require = 1)
    private void bootoptim$beginTransparency(CallbackInfo ci) {
        if (!this.bootoptim$reloadActive) {
            return;
        }
        this.bootoptim$transparencyStartNanos = System.nanoTime();
        this.bootoptim$transparencyStartCpuNanos = bootoptim$currentThreadCpuNanos();
    }

    @Inject(method = "initTransparency", at = @At("RETURN"), require = 1)
    private void bootoptim$finishTransparency(CallbackInfo ci) {
        if (!this.bootoptim$reloadActive || this.bootoptim$transparencyStartNanos == 0L) {
            return;
        }
        this.bootoptim$transparencyNanos += System.nanoTime() - this.bootoptim$transparencyStartNanos;
        this.bootoptim$transparencyCpuNanos += bootoptim$cpuDelta(this.bootoptim$transparencyStartCpuNanos);
        this.bootoptim$transparencyCalls++;
        this.bootoptim$transparencyStartNanos = 0L;
        this.bootoptim$transparencyStartCpuNanos = -1L;
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 1)
    private void bootoptim$finishReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (!this.bootoptim$reloadActive) {
            return;
        }

        long totalNanos = System.nanoTime() - this.bootoptim$reloadStartNanos;
        long totalCpuNanos = bootoptim$cpuDelta(this.bootoptim$reloadStartCpuNanos);
        long knownNanos = this.bootoptim$outlineNanos + this.bootoptim$transparencyNanos;
        long otherNanos = Math.max(0L, totalNanos - knownNanos);
        long knownCpuNanos = this.bootoptim$outlineCpuNanos + this.bootoptim$transparencyCpuNanos;
        long otherCpuNanos = totalCpuNanos >= 0L ? Math.max(0L, totalCpuNanos - knownCpuNanos) : -1L;

        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_LEVEL_RENDERER_RELOAD status=measured total_ms={} cpu_ms={} outline_ms={} outline_cpu_ms={} outline_calls={} transparency_ms={} transparency_cpu_ms={} transparency_calls={} other_ms={} other_cpu_ms={} thread={}",
                bootoptim$formatNanos(totalNanos),
                bootoptim$formatCpuNanos(totalCpuNanos),
                bootoptim$formatNanos(this.bootoptim$outlineNanos),
                bootoptim$formatCpuNanos(this.bootoptim$outlineCpuNanos),
                this.bootoptim$outlineCalls,
                bootoptim$formatNanos(this.bootoptim$transparencyNanos),
                bootoptim$formatCpuNanos(this.bootoptim$transparencyCpuNanos),
                this.bootoptim$transparencyCalls,
                bootoptim$formatNanos(otherNanos),
                bootoptim$formatCpuNanos(otherCpuNanos),
                Thread.currentThread().getName());

        this.bootoptim$reloadActive = false;
        this.bootoptim$reloadReported = true;
    }

    @Unique
    private static long bootoptim$currentThreadCpuNanos() {
        try {
            if (BOOTOPTIM$THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                    && BOOTOPTIM$THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return BOOTOPTIM$THREAD_MX_BEAN.getCurrentThreadCpuTime();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Diagnostic only: wall timing remains valid if CPU timing is unavailable.
        }
        return -1L;
    }

    @Unique
    private static long bootoptim$cpuDelta(long startCpuNanos) {
        if (startCpuNanos < 0L) {
            return -1L;
        }
        long now = bootoptim$currentThreadCpuNanos();
        return now >= startCpuNanos ? now - startCpuNanos : -1L;
    }

    @Unique
    private static String bootoptim$formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    @Unique
    private static String bootoptim$formatCpuNanos(long nanos) {
        return nanos >= 0L ? bootoptim$formatNanos(nanos) : "unavailable";
    }
}
