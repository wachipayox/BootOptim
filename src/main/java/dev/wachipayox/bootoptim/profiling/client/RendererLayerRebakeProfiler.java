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
import java.util.concurrent.locks.LockSupport;
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
                "BOOTOPTIM_RENDERER_LAYER_REBAKE scope={} total_ms={} cpu_ms={} block_entity_create_ms={} entity_create_ms={} player_create_ms={} post_create_ms={} stack_samples={} top_hot={} layer_calls={} unique_layers={} repeat_calls={} layer_ms={} first_layer_ms={} repeat_layer_ms={} top_layers={} thread={}",
                scope.marker,
                formatNanos(totalNanos),
                formatCpuNanos(totalCpuNanos),
                formatNanos(state.blockEntityCreateNanos),
                formatNanos(state.entityCreateNanos),
                formatNanos(state.playerCreateNanos),
                formatNanos(postCreateNanos),
                state.stackSamples,
                state.topHot == null ? "none" : state.topHot,
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
        if (phase == Phase.BLOCK_ENTITY_CREATE || phase == Phase.ENTITY_CREATE) {
            state.sampler = new StackSampler(Thread.currentThread());
            state.sampler.start();
        }
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
        if (state.sampler != null) {
            state.sampler.stop();
            state.stackSamples = state.sampler.samples();
            state.topHot = state.sampler.topHot(12);
            state.sampler = null;
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
        private StackSampler sampler;
        private int stackSamples;
        private String topHot;
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
            if (this.sampler != null) {
                this.sampler.stop();
            }
            this.scope = null;
            this.scopeStartNanos = 0L;
            this.scopeStartCpuNanos = -1L;
            this.blockEntityCreateNanos = 0L;
            this.entityCreateNanos = 0L;
            this.playerCreateNanos = 0L;
            this.activePhase = null;
            this.activePhaseStartNanos = 0L;
            this.sampler = null;
            this.stackSamples = 0;
            this.topHot = null;
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

    private static final class StackSampler implements Runnable {
        private final Thread target;
        private final Map<String, Integer> hot = new HashMap<>();
        private final Thread samplerThread;
        private volatile boolean running = true;
        private int samples;

        private StackSampler(Thread target) {
            this.target = target;
            this.samplerThread = new Thread(this, "BootOptim renderer-constructor sampler");
            this.samplerThread.setDaemon(true);
            this.samplerThread.setPriority(Thread.MIN_PRIORITY);
        }

        private void start() {
            this.samplerThread.start();
        }

        @Override
        public void run() {
            while (this.running) {
                try {
                    StackTraceElement[] stack = this.target.getStackTrace();
                    String key = selectHotFrame(stack);
                    if (key != null) {
                        this.hot.merge(key, 1, Integer::sum);
                    }
                    this.samples++;
                } catch (SecurityException ignored) {
                    this.running = false;
                    break;
                }
                LockSupport.parkNanos(2_000_000L);
            }
        }

        private void stop() {
            this.running = false;
            LockSupport.unpark(this.samplerThread);
            try {
                this.samplerThread.join(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private int samples() {
            return this.samples;
        }

        private String topHot(int limit) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(this.hot.entrySet());
            entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder result = new StringBuilder();
            int size = Math.min(limit, entries.size());
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    result.append(',');
                }
                Map.Entry<String, Integer> entry = entries.get(i);
                result.append(entry.getKey()).append('@').append(entry.getValue());
            }
            return result.length() == 0 ? "none" : result.toString();
        }

        private static String selectHotFrame(StackTraceElement[] stack) {
            for (StackTraceElement frame : stack) {
                String className = frame.getClassName();
                if (className.startsWith("java.")
                        || className.startsWith("jdk.")
                        || className.startsWith("sun.")
                        || className.startsWith("org.spongepowered.")
                        || className.startsWith("com.llamalad7.mixinextras.")
                        || className.startsWith("dev.wachipayox.bootoptim.")
                        || className.equals("net.minecraft.client.renderer.entity.EntityRenderers")
                        || className.equals("net.minecraft.client.renderer.blockentity.BlockEntityRenderers")) {
                    continue;
                }
                return className + "#" + frame.getMethodName();
            }
            return null;
        }
    }
}
