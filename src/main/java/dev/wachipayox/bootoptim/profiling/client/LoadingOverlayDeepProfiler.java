package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import dev.wachipayox.bootoptim.profiling.VarianceProbe;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Frame aggregates for manual LoadingOverlay instances; no per-frame logging. */
public final class LoadingOverlayDeepProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/OverlayDeep");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    // All map access is on the Minecraft render thread. Exit stays in the map
    // until the completed Window.updateDisplay so the final frame is included.
    private static final IdentityHashMap<Overlay, Trace> TRACES = new IdentityHashMap<>();
    private static final ConcurrentLinkedQueue<Trace> COMPLETED = new ConcurrentLinkedQueue<>();

    private LoadingOverlayDeepProfiler() {}

    public static void renderBegin(LoadingOverlay overlay, boolean reloadDone, long fadeOutStart) {
        if (!VarianceProbe.enabled() || !StartupProfiler.hasMainMenuOpened()) return;
        Trace trace = TRACES.computeIfAbsent(overlay, ignored -> new Trace(NEXT_ID.incrementAndGet(), overlay));
        long now = System.nanoTime();
        if (trace.lastRenderEnd > 0) {
            long gap = Math.max(0, now - trace.lastRenderEnd);
            trace.gapSum += gap;
            trace.gapMax = Math.max(trace.gapMax, gap);
        }
        trace.renderStart = now;
        trace.frames++;
        if (reloadDone && !trace.doneSeen) {
            trace.doneSeen = true;
            VarianceProbe.point("overlay_reload_done_seen", "overlay_" + trace.id);
        }
        observeFade(trace, fadeOutStart);
    }

    public static void renderEnd(LoadingOverlay overlay, long fadeOutStart) {
        Trace trace = TRACES.get(overlay);
        if (trace == null || trace.renderStart == 0) return;
        long now = System.nanoTime();
        long elapsed = Math.max(0, now - trace.renderStart);
        trace.overlayRenderSum += elapsed;
        trace.overlayRenderMax = Math.max(trace.overlayRenderMax, elapsed);
        trace.lastRenderEnd = now;
        trace.renderStart = 0;
        observeFade(trace, fadeOutStart);
    }

    private static void observeFade(Trace trace, long fadeOutStart) {
        if (fadeOutStart >= 0 && !trace.fadeSeen) {
            trace.fadeSeen = true;
            VarianceProbe.point("overlay_fade_begin_seen", "overlay_" + trace.id);
        }
    }

    public static void stage(Overlay overlay, String stage, long nanos) {
        Trace trace = TRACES.get(overlay);
        if (trace == null || nanos < 0) return;
        if (stage.equals("game_render")) {
            trace.gameRenderSum += nanos;
            trace.gameRenderMax = Math.max(trace.gameRenderMax, nanos);
        } else if (stage.equals("blit")) {
            trace.blitSum += nanos;
            trace.blitMax = Math.max(trace.blitMax, nanos);
        } else if (stage.equals("display")) {
            trace.displaySum += nanos;
            trace.displayMax = Math.max(trace.displayMax, nanos);
        }
    }

    public static void exit(Overlay overlay) {
        Trace trace = TRACES.get(overlay);
        if (trace == null) return;
        trace.exited = true;
        COMPLETED.add(trace);
    }

    public static void emitCompletedAfterFrame() {
        Trace trace;
        while ((trace = COMPLETED.poll()) != null) {
            if (!trace.exited) continue;
            TRACES.remove(trace.overlay);
            LOGGER.info("BOOTOPTIM_OVERLAY_DEEP overlay_id={} frames={} done_seen={} fade_seen={} gap_ms_sum={} gap_ms_max={} overlay_render_ms_sum={} overlay_render_ms_max={} game_render_ms_sum={} game_render_ms_max={} blit_ms_sum={} blit_ms_max={} display_ms_sum={} display_ms_max={}",
                    trace.id, trace.frames, trace.doneSeen, trace.fadeSeen,
                    ms(trace.gapSum), ms(trace.gapMax), ms(trace.overlayRenderSum), ms(trace.overlayRenderMax),
                    ms(trace.gameRenderSum), ms(trace.gameRenderMax), ms(trace.blitSum), ms(trace.blitMax),
                    ms(trace.displaySum), ms(trace.displayMax));
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static final class Trace {
        private final int id;
        private final Overlay overlay;
        private int frames;
        private boolean doneSeen;
        private boolean fadeSeen;
        private boolean exited;
        private long renderStart;
        private long lastRenderEnd;
        private long gapSum;
        private long gapMax;
        private long overlayRenderSum;
        private long overlayRenderMax;
        private long gameRenderSum;
        private long gameRenderMax;
        private long blitSum;
        private long blitMax;
        private long displaySum;
        private long displayMax;

        private Trace(int id, Overlay overlay) { this.id = id; this.overlay = overlay; }
    }
}
