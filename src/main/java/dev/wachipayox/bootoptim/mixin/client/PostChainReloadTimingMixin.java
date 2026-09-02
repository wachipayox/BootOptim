package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only detail for the world post chains created by LevelRenderer reload. */
@Mixin(PostChain.class)
abstract class PostChainReloadTimingMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LogUtils.getLogger();

    @Unique
    private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileLevelRendererReload", "false"));

    @Unique
    private static final ThreadMXBean BOOTOPTIM$THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    @Unique
    private String bootoptim$trackedChain;

    @Unique
    private long bootoptim$loadStartNanos;

    @Unique
    private long bootoptim$loadStartCpuNanos;

    @Unique
    private long bootoptim$resizeStartNanos;

    @Unique
    private long bootoptim$resizeStartCpuNanos;

    @Inject(method = "load", at = @At("HEAD"), require = 1)
    private void bootoptim$beginLoad(TextureManager textureManager, ResourceLocation location, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || !bootoptim$isTracked(location)) {
            return;
        }
        this.bootoptim$trackedChain = location.toString();
        this.bootoptim$loadStartNanos = System.nanoTime();
        this.bootoptim$loadStartCpuNanos = bootoptim$currentThreadCpuNanos();
    }

    @Inject(method = "load", at = @At("RETURN"), require = 1)
    private void bootoptim$finishLoad(TextureManager textureManager, ResourceLocation location, CallbackInfo ci) {
        if (this.bootoptim$loadStartNanos == 0L || this.bootoptim$trackedChain == null) {
            return;
        }
        long wallNanos = System.nanoTime() - this.bootoptim$loadStartNanos;
        long cpuNanos = bootoptim$cpuDelta(this.bootoptim$loadStartCpuNanos);
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_LEVEL_RENDERER_POST_CHAIN chain={} stage=load wall_ms={} cpu_ms={} thread={}",
                this.bootoptim$trackedChain,
                bootoptim$formatNanos(wallNanos),
                bootoptim$formatCpuNanos(cpuNanos),
                Thread.currentThread().getName());
        this.bootoptim$loadStartNanos = 0L;
        this.bootoptim$loadStartCpuNanos = -1L;
    }

    @Inject(method = "resize", at = @At("HEAD"), require = 1)
    private void bootoptim$beginResize(int width, int height, CallbackInfo ci) {
        if (!BOOTOPTIM$ENABLED || this.bootoptim$trackedChain == null) {
            return;
        }
        this.bootoptim$resizeStartNanos = System.nanoTime();
        this.bootoptim$resizeStartCpuNanos = bootoptim$currentThreadCpuNanos();
    }

    @Inject(method = "resize", at = @At("RETURN"), require = 1)
    private void bootoptim$finishResize(int width, int height, CallbackInfo ci) {
        if (this.bootoptim$resizeStartNanos == 0L || this.bootoptim$trackedChain == null) {
            return;
        }
        long wallNanos = System.nanoTime() - this.bootoptim$resizeStartNanos;
        long cpuNanos = bootoptim$cpuDelta(this.bootoptim$resizeStartCpuNanos);
        BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_LEVEL_RENDERER_POST_CHAIN chain={} stage=resize width={} height={} wall_ms={} cpu_ms={} thread={}",
                this.bootoptim$trackedChain,
                width,
                height,
                bootoptim$formatNanos(wallNanos),
                bootoptim$formatCpuNanos(cpuNanos),
                Thread.currentThread().getName());
        this.bootoptim$resizeStartNanos = 0L;
        this.bootoptim$resizeStartCpuNanos = -1L;
    }

    @Unique
    private static boolean bootoptim$isTracked(ResourceLocation location) {
        if (!"minecraft".equals(location.getNamespace())) {
            return false;
        }
        String path = location.getPath();
        return "shaders/post/entity_outline.json".equals(path)
                || "shaders/post/transparency.json".equals(path);
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
