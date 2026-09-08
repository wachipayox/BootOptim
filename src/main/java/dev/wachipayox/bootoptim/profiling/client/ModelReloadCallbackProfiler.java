package dev.wachipayox.bootoptim.profiling.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Low-cardinality wall timing for NeoForge model events that execute arbitrary mod callbacks. */
public final class ModelReloadCallbackProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelReloadCallbacks");
    private static final boolean ENABLED = Boolean.getBoolean(ReloadListenerCriticalPathProfiler.PROPERTY);
    private static final ThreadLocal<Long> MODIFY_START = ThreadLocal.withInitial(() -> -1L);
    private static final ThreadLocal<Long> COMPLETED_START = ThreadLocal.withInitial(() -> -1L);

    private ModelReloadCallbackProfiler() {
    }

    public static void beginModifyBakingResult() {
        if (ENABLED) {
            MODIFY_START.set(System.nanoTime());
        }
    }

    public static void endModifyBakingResult() {
        finish("modify_baking_result", MODIFY_START);
    }

    public static void beginBakingCompleted() {
        if (ENABLED) {
            COMPLETED_START.set(System.nanoTime());
        }
    }

    public static void endBakingCompleted() {
        finish("baking_completed", COMPLETED_START);
    }

    private static void finish(String event, ThreadLocal<Long> startHolder) {
        if (!ENABLED) {
            return;
        }
        long started = startHolder.get();
        startHolder.remove();
        if (started < 0L) {
            return;
        }
        double wallMs = (System.nanoTime() - started) / 1_000_000.0D;
        LOGGER.info(
                "BOOTOPTIM_MODEL_CALLBACK event={} wall_ms={} thread=\"{}\" boundary=external_mod_callback",
                event,
                String.format(Locale.ROOT, "%.3f", wallMs),
                Thread.currentThread().getName());
    }
}
