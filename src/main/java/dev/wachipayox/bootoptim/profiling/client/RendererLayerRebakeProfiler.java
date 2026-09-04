package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.model.geom.ModelLayerLocation;
import org.slf4j.Logger;

/**
 * Diagnostic-only attribution for renderer reload reconstruction and EntityModelSet layer baking.
 *
 * <p>This profiler never skips, caches, shares, reorders or moves renderer/model work. It is scoped
 * only to the existing BlockEntityRenderDispatcher and EntityRenderDispatcher resource-reload
 * callbacks, so layer bakes elsewhere are not charged to these serial startup intervals.</p>
 */
public final class RendererLayerRebakeProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.profileRendererLayerRebake", "false"));
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private RendererLayerRebakeProfiler() {}

    public enum Scope {
        BLOCK_ENTITY("block_entity"),
        ENTITY("entity");

        private final String marker;

        Scope(String marker) {
            this.marker = marker;
        }
    }

    public enum Phase {
        BLOCK_ENTITY_CREATE,
        ENTITY_CREATE,
        PLAYER_CREATE
    }

    public static void begin(Scope scope) {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope != null) {
            return;
        }
        state.reset(scope);
        state.scopeStartNanos = System.nanoTime();
        state.scopeStartCpuNanos = currentThreadCpuNanos();
    }

    public static void end(Scope scope) {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope != scope) {
            return;
        }

        long totalNanos = System.nanoTime() - state.scopeStartNanos;
        long totalCpuNanos = cpuDelta(state.scopeStartCpuNanos);
        long repeatNanos = state.repeatLayerNanos;
        long firstNanos = Math.max(0L, state.layerBakeNanos - repeatNanos);
        long createNanos = state.blockEntityCreateNanos + state.entityCreateNanos + state.playerCreateNanos;
        long postCreateNanos = Math.max(0L, totalNanos - createNanos);

        List<Map.Entry<ModelLayerLocation, LayerStats>> top = new ArrayList<>(state.layers.entrySet());
        top.sort(Comparator.comparingLong((Map.Entry<ModelLayerLocation, LayerStats> entry) -> entry.getValue().nanos)
                .reversed());
        StringBuilder topLayers = new StringBuilder();
        int limit = Math.min(12, top.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<ModelLayerLocation, LayerStats> entry = top.get(i);
            if (i > 0) {
                topLayers.append(',');
            }
            LayerStats stats = entry.getValue();
            topLayers.append(entry.getKey())
                    .append('@')
                    .append(stats.calls)
                    .append('/')
                    .append(formatNanos(stats.nanos));
        }

        LOGGER.info(
                "BOOTOPTIM_RENDERER_LAYER_REBAKE scope={} total_ms={} cpu_ms={} block_entity_create_ms={} entity_create_ms={} player_create_ms={} post_create_ms={} layer_calls={} unique_layers={} repeat_calls={} layer_ms={} first_layer_ms={} repeat_layer_ms={} top_layers={} thread={}",
                scope.marker,
                formatNanos(totalNanos),
                formatCpuNanos(totalCpuNanos),
                formatNanos(state.blockEntityCreateNanos),
                formatNanos(state.entityCreateNanos),
                formatNanos(state.playerCreateNanos),
                formatNanos(postCreateNanos),
                state.layerCalls,
                state.layers.size(),
                state.repeatLayerCalls,
                formatNanos(state.layerBakeNanos),
                formatNanos(firstNanos),
                formatNanos(repeatNanos),
                topLayers.length() == 0 ? "none" : topLayers.toString(),
                Thread.currentThread().getName());

        state.clear();
    }

    public static void beginPhase(Phase phase) {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope == null || state.activePhase != null) {
            return;
        }
        state.activePhase = phase;
        state.activePhaseStartNanos = System.nanoTime();
    }

    public static void endPhase(Phase phase) {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope == null || state.activePhase != phase || state.activePhaseStartNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - state.activePhaseStartNanos;
        switch (phase) {
            case BLOCK_ENTITY_CREATE -> state.blockEntityCreateNanos += elapsed;
            case ENTITY_CREATE -> state.entityCreateNanos += elapsed;
            case PLAYER_CREATE -> state.playerCreateNanos += elapsed;
        }
        state.activePhase = null;
        state.activePhaseStartNanos = 0L;
    }

    public static void beginLayer(ModelLayerLocation layer) {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope == null) {
            return;
        }
        state.activeLayer = layer;
        state.activeLayerStartNanos = System.nanoTime();
    }

    public static void endLayer() {
        if (!ENABLED) {
            return;
        }
        State state = STATE.get();
        if (state.scope == null || state.activeLayer == null || state.activeLayerStartNanos == 0L) {
            return;
        }

        long elapsed = System.nanoTime() - state.activeLayerStartNanos;
        LayerStats stats = state.layers.computeIfAbsent(state.activeLayer, ignored -> new LayerStats());
        if (stats.calls > 0) {
            state.repeatLayerCalls++;
            state.repeatLayerNanos += elapsed;
        }
        stats.calls++;
        stats.nanos += elapsed;
        state.layerCalls++;
        state.layerBakeNanos += elapsed;
        state.activeLayer = null;
        state.activeLayerStartNanos = 0L;
    }

    private static long currentThreadCpuNanos() {
        try {
            if (THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                return THREAD_MX_BEAN.getCurrentThreadCpuTime();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Diagnostic only: wall timing remains usable if CPU timing is unavailable.
        }
        return -1L;
    }

    private static long cpuDelta(long startCpuNanos) {
        if (startCpuNanos < 0L) {
            return -1L;
        }
        long now = currentThreadCpuNanos();
        return now >= startCpuNanos ? now - startCpuNanos : -1L;
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatCpuNanos(long nanos) {
        return nanos >= 0L ? formatNanos(nanos) : "unavailable";
    }

    private static final class State {
        private Scope scope;
        private long scopeStartNanos;
        private long scopeStartCpuNanos;
        private long blockEntityCreateNanos;
        private long entityCreateNanos;
        private long playerCreateNanos;
        private Phase activePhase;
        private long activePhaseStartNanos;
        private long layerBakeNanos;
        private long repeatLayerNanos;
        private int layerCalls;
        private int repeatLayerCalls;
        private ModelLayerLocation activeLayer;
        private long activeLayerStartNanos;
        private final Map<ModelLayerLocation, LayerStats> layers = new HashMap<>();

        private void reset(Scope newScope) {
            clear();
            this.scope = newScope;
        }

        private void clear() {
            this.scope = null;
            this.scopeStartNanos = 0L;
            this.scopeStartCpuNanos = -1L;
            this.blockEntityCreateNanos = 0L;
            this.entityCreateNanos = 0L;
            this.playerCreateNanos = 0L;
            this.activePhase = null;
            this.activePhaseStartNanos = 0L;
            this.layerBakeNanos = 0L;
            this.repeatLayerNanos = 0L;
            this.layerCalls = 0;
            this.repeatLayerCalls = 0;
            this.activeLayer = null;
            this.activeLayerStartNanos = 0L;
            this.layers.clear();
        }
    }

    private static final class LayerStats {
        private int calls;
        private long nanos;
    }
}
