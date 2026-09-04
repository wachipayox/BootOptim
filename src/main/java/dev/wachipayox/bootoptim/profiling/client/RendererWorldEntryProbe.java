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
    private static boolean firstRenderSeen;

    private RendererWorldEntryProbe() {}

    public static void beginAttach(boolean hadPendingRendererReload) {
        entry++;
        attachBeginUptimeMs = uptimeMs();
        attachReadyUptimeMs = 0L;
        firstRenderSeen = false;
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_begin entry={} uptime_ms={} renderer_reload_pending={} thread={}",
                entry,
                attachBeginUptimeMs,
                hadPendingRendererReload,
                Thread.currentThread().getName());
    }

    public static void finishAttachWarmup() {
        attachReadyUptimeMs = uptimeMs();
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_ready entry={} uptime_ms={} warmup_ms={} thread={}",
                entry,
                attachReadyUptimeMs,
                Math.max(0L, attachReadyUptimeMs - attachBeginUptimeMs),
                Thread.currentThread().getName());
    }

    public static void markFirstRender() {
        if (entry == 0 || firstRenderSeen) {
            return;
        }

        firstRenderSeen = true;
        long renderUptimeMs = uptimeMs();
        LOGGER.info(
                "BOOTOPTIM_RENDERER_WORLD_ENTRY status=first_render entry={} uptime_ms={} since_attach_begin_ms={} since_attach_ready_ms={} thread={}",
                entry,
                renderUptimeMs,
                Math.max(0L, renderUptimeMs - attachBeginUptimeMs),
                attachReadyUptimeMs == 0L ? -1L : Math.max(0L, renderUptimeMs - attachReadyUptimeMs),
                Thread.currentThread().getName());
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}
