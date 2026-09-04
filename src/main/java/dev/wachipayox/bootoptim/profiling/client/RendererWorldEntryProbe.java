package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;

/** Lightweight markers for the experimental renderer world-entry gate. Client-thread only. */
public final class RendererWorldEntryProbe {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int entry;
    private static long attachBeginUptimeMs;
    private static long attachReadyUptimeMs;
    private static long attachCompleteUptimeMs;
    private static boolean firstRenderSeen;

    private RendererWorldEntryProbe() {}

    public static void beginAttach(boolean hadPendingRendererReload) {
        entry++;
        attachBeginUptimeMs = uptimeMs();
        attachReadyUptimeMs = 0L;
        attachCompleteUptimeMs = 0L;
        firstRenderSeen = false;
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_begin entry={} uptime_ms={} renderer_reload_pending={} thread={}",
                entry,
                attachBeginUptimeMs,
                hadPendingRendererReload,
                Thread.currentThread().getName());
    }

    /** Marks completion of the deferred renderer warmup, before vanilla attaches the level. */
    public static void finishAttachWarmup() {
        attachReadyUptimeMs = uptimeMs();
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_ready entry={} uptime_ms={} warmup_ms={} thread={}",
                entry,
                attachReadyUptimeMs,
                Math.max(0L, attachReadyUptimeMs - attachBeginUptimeMs),
                Thread.currentThread().getName());
    }

    /** Marks TAIL of Minecraft.updateLevelInEngines after ordinary engine/dispatcher attachment. */
    public static void finishEngineAttach() {
        if (entry == 0) {
            return;
        }

        attachCompleteUptimeMs = uptimeMs();
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_complete entry={} uptime_ms={} vanilla_attach_ms={} since_attach_begin_ms={} thread={}",
                entry,
                attachCompleteUptimeMs,
                attachReadyUptimeMs == 0L ? -1L : Math.max(0L, attachCompleteUptimeMs - attachReadyUptimeMs),
                Math.max(0L, attachCompleteUptimeMs - attachBeginUptimeMs),
                Thread.currentThread().getName());
    }

    public static void markFirstRender() {
        if (entry == 0 || firstRenderSeen) {
            return;
        }

        firstRenderSeen = true;
        long renderUptimeMs = uptimeMs();
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=first_render entry={} uptime_ms={} since_attach_begin_ms={} since_attach_ready_ms={} since_attach_complete_ms={} thread={}",
                entry,
                renderUptimeMs,
                Math.max(0L, renderUptimeMs - attachBeginUptimeMs),
                attachReadyUptimeMs == 0L ? -1L : Math.max(0L, renderUptimeMs - attachReadyUptimeMs),
                attachCompleteUptimeMs == 0L ? -1L : Math.max(0L, renderUptimeMs - attachCompleteUptimeMs),
                Thread.currentThread().getName());
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}
