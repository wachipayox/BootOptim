package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Diagnostic-only accounting for MoreCulling 1.0.8 startup reload work.
 *
 * <p>The hooks observe the original MoreCulling methods; they do not skip, retry, reorder or
 * duplicate cache rebuilds or opacity queries. ThreadMXBean CPU clocks are read only around whole
 * reload-listener/batch boundaries. Per-sprite accounting is post-call, aggregate-only and bounded.</p>
 */
public final class MoreCullingStartupDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME_AVAILABLE = THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
            && THREAD_MX_BEAN.isThreadCpuTimeEnabled();
    private static final int MAX_REPEAT_KEYS_PER_RELOAD = 4096;

    private static final ThreadLocal<SpriteCall> SPRITE_CALL = ThreadLocal.withInitial(SpriteCall::new);
    private static final ThreadLocal<Integer> MODEL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final LongAdder SPRITE_CALLS = new LongAdder();
    private static final LongAdder SPRITE_WALL_NANOS = new LongAdder();
    private static final LongAdder SPRITE_CANDIDATE_PIXELS = new LongAdder();
    private static final LongAdder SPRITE_TRUE_RESULTS = new LongAdder();
    private static final LongAdder SPRITE_FALSE_RESULTS = new LongAdder();
    private static final LongAdder SPRITE_LAYERED_CALLS = new LongAdder();
    private static final LongAdder SPRITE_MODEL_CALLS = new LongAdder();
    private static final LongAdder SPRITE_MODEL_WALL_NANOS = new LongAdder();
    private static final LongAdder REPEAT_TRACKED_UNIQUE = new LongAdder();
    private static final LongAdder REPEAT_TRACKED_UNIQUE_WALL_NANOS = new LongAdder();
    private static final LongAdder REPEAT_MATCHES = new LongAdder();
    private static final LongAdder REPEAT_WALL_NANOS = new LongAdder();
    private static final LongAdder REPEAT_RESULT_MISMATCHES = new LongAdder();
    private static final LongAdder REPEAT_TRACKING_SATURATED = new LongAdder();
    private static final LongAdder REPEAT_UNTRACKED_WALL_NANOS = new LongAdder();

    private static final Map<SpriteKey, Boolean> REPEAT_RESULTS = new HashMap<>();

    private static int reloadsStarted;
    private static int shapeExpectedStates;
    private static int shapeCompletedStates;
    private static int shapeNonOccludingStates;
    private static long shapeStartWallNanos;
    private static long shapeStartCpuNanos;
    private static boolean shapeBatchActive;
    private static int shapeFullCoverageReloads;
    private static long shapeFullWallNanos;
    private static long shapeFullCpuNanos;

    private static int modelExpectedStates;
    private static int modelObservedStates;
    private static long modelStartWallNanos;
    private static long modelStartCpuNanos;
    private static long modelLastReturnWallNanos;
    private static boolean modelBatchActive;
    private static int modelFullCoverageReloads;
    private static long modelFullWallNanos;
    private static long modelFullCpuNanos;

    private static int wrappedListenerRuns;
    private static long shapeListenerWallNanos;
    private static long shapeListenerCpuNanos;
    private static long translucencyListenerWallNanos;
    private static long translucencyListenerCpuNanos;
    private static boolean reportEmitted;

    private MoreCullingStartupDiagnostics() {
    }

    public static void runReloadListener(
            int registrationOrdinal,
            ResourceManager manager,
            ResourceManagerReloadListener delegate) {
        if (!isActive()) {
            delegate.onResourceManagerReload(manager);
            return;
        }

        long startedWall = System.nanoTime();
        long startedCpu = currentThreadCpuNanos();
        try {
            delegate.onResourceManagerReload(manager);
        } finally {
            long wallNanos = System.nanoTime() - startedWall;
            long cpuNanos = elapsedCpuNanos(startedCpu);
            wrappedListenerRuns++;
            if (registrationOrdinal == 0) {
                shapeListenerWallNanos += wallNanos;
                if (cpuNanos >= 0L) {
                    shapeListenerCpuNanos += cpuNanos;
                }
                logListener("shape_listener", wallNanos, cpuNanos);
            } else if (registrationOrdinal == 1) {
                translucencyListenerWallNanos += wallNanos;
                if (cpuNanos >= 0L) {
                    translucencyListenerCpuNanos += cpuNanos;
                }
                logListener("translucency_listener", wallNanos, cpuNanos);
            }
        }
    }

    public static void onShapeCacheStateStart(BlockState state) {
        if (!isActive()) {
            return;
        }

        if (!shapeBatchActive) {
            reloadsStarted++;
            shapeExpectedStates = Block.BLOCK_STATE_REGISTRY.size();
            shapeCompletedStates = 0;
            shapeNonOccludingStates = 0;
            shapeStartWallNanos = System.nanoTime();
            shapeStartCpuNanos = currentThreadCpuNanos();
            shapeBatchActive = true;

            modelExpectedStates = 0;
            modelObservedStates = 0;
            modelStartWallNanos = 0L;
            modelStartCpuNanos = -1L;
            modelLastReturnWallNanos = 0L;
            modelBatchActive = false;
            REPEAT_RESULTS.clear();
        }

        if (!state.canOcclude()) {
            shapeNonOccludingStates++;
        }
    }

    public static void onShapeCacheStateEnd() {
        if (!isActive() || !shapeBatchActive) {
            return;
        }

        shapeCompletedStates++;
        if (shapeExpectedStates <= 0 || shapeCompletedStates < shapeExpectedStates) {
            return;
        }

        long wallNanos = System.nanoTime() - shapeStartWallNanos;
        long cpuNanos = elapsedCpuNanos(shapeStartCpuNanos);
        shapeFullCoverageReloads++;
        shapeFullWallNanos += wallNanos;
        if (cpuNanos >= 0L) {
            shapeFullCpuNanos += cpuNanos;
        }
        modelExpectedStates = shapeNonOccludingStates;
        shapeBatchActive = false;

        LOGGER.info(
                "BOOTOPTIM_MORECULLING phase=shape_cache reload={} states_expected={} states_observed={} non_occluding={} wall_ms={} cpu_ms={} coverage_percent=100.000",
                reloadsStarted,
                shapeExpectedStates,
                shapeCompletedStates,
                shapeNonOccludingStates,
                formatNanos(wallNanos),
                formatNanos(cpuNanos));
    }

    public static void onModelTranslucencyStateStart() {
        if (!isActive()) {
            return;
        }

        MODEL_DEPTH.set(MODEL_DEPTH.get() + 1);
        if (!modelBatchActive) {
            modelStartWallNanos = System.nanoTime();
            modelStartCpuNanos = currentThreadCpuNanos();
            modelLastReturnWallNanos = 0L;
            modelBatchActive = true;
        }
    }

    public static void onModelTranslucencyStateEnd() {
        if (!isActive()) {
            return;
        }

        try {
            if (!modelBatchActive) {
                return;
            }
            modelObservedStates++;
            modelLastReturnWallNanos = System.nanoTime();

            if (modelExpectedStates > 0 && modelObservedStates >= modelExpectedStates) {
                long wallNanos = modelLastReturnWallNanos - modelStartWallNanos;
                long cpuNanos = elapsedCpuNanos(modelStartCpuNanos);
                modelFullCoverageReloads++;
                modelFullWallNanos += wallNanos;
                if (cpuNanos >= 0L) {
                    modelFullCpuNanos += cpuNanos;
                }
                modelBatchActive = false;

                LOGGER.info(
                        "BOOTOPTIM_MORECULLING phase=translucency_standard_models reload={} states_expected={} standard_models_observed={} wall_ms={} cpu_ms={} coverage_percent=100.000",
                        reloadsStarted,
                        modelExpectedStates,
                        modelObservedStates,
                        formatNanos(wallNanos),
                        formatNanos(cpuNanos));
            }
        } finally {
            int depth = MODEL_DEPTH.get();
            if (depth <= 1) {
                MODEL_DEPTH.remove();
            } else {
                MODEL_DEPTH.set(depth - 1);
            }
        }
    }

    public static void onSpriteTranslucencyStart(
            NativeImage image,
            Object layeredImages,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight) {
        if (!isActive()) {
            return;
        }

        SpriteCall call = SPRITE_CALL.get();
        call.active = true;
        call.image = image;
        call.layered = layeredImages != null;
        call.minWidth = minWidth;
        call.maxWidth = maxWidth;
        call.minHeight = minHeight;
        call.maxHeight = maxHeight;
        call.insideObservedModel = MODEL_DEPTH.get() > 0;
        call.startedNanos = System.nanoTime();
    }

    public static void onSpriteTranslucencyEnd(boolean result) {
        if (!isActive()) {
            return;
        }

        SpriteCall call = SPRITE_CALL.get();
        if (!call.active) {
            return;
        }

        long wallNanos = System.nanoTime() - call.startedNanos;
        call.active = false;

        SPRITE_CALLS.increment();
        SPRITE_WALL_NANOS.add(wallNanos);
        long width = Math.max(0L, (long) call.maxWidth - call.minWidth);
        long height = Math.max(0L, (long) call.maxHeight - call.minHeight);
        SPRITE_CANDIDATE_PIXELS.add(saturatedMultiply(width, height));
        if (result) {
            SPRITE_TRUE_RESULTS.increment();
        } else {
            SPRITE_FALSE_RESULTS.increment();
        }
        if (call.insideObservedModel) {
            SPRITE_MODEL_CALLS.increment();
            SPRITE_MODEL_WALL_NANOS.add(wallNanos);
        }
        if (call.layered) {
            SPRITE_LAYERED_CALLS.increment();
            return;
        }

        SpriteKey key = new SpriteKey(
                call.image,
                call.minWidth,
                call.maxWidth,
                call.minHeight,
                call.maxHeight);
        Boolean previous = REPEAT_RESULTS.get(key);
        if (previous != null) {
            REPEAT_MATCHES.increment();
            REPEAT_WALL_NANOS.add(wallNanos);
            if (previous.booleanValue() != result) {
                REPEAT_RESULT_MISMATCHES.increment();
            }
        } else if (REPEAT_RESULTS.size() < MAX_REPEAT_KEYS_PER_RELOAD) {
            REPEAT_RESULTS.put(key, result);
            REPEAT_TRACKED_UNIQUE.increment();
            REPEAT_TRACKED_UNIQUE_WALL_NANOS.add(wallNanos);
        } else {
            REPEAT_TRACKING_SATURATED.increment();
            REPEAT_UNTRACKED_WALL_NANOS.add(wallNanos);
        }
    }

    public static void reportAtMainMenu() {
        if (!StartupProfiler.isEnabled() || reportEmitted) {
            return;
        }
        reportEmitted = true;

        int expected = modelExpectedStates;
        int observed = modelObservedStates;
        double coverage = expected <= 0 ? 0.0D : Math.min(100.0D, observed * 100.0D / expected);
        long observedModelSpan = modelStartWallNanos > 0L && modelLastReturnWallNanos >= modelStartWallNanos
                ? modelLastReturnWallNanos - modelStartWallNanos
                : -1L;

        LOGGER.info(
                "BOOTOPTIM_MORECULLING phase=startup_summary reloads_started={} wrapped_listener_runs={} shape_full_reloads={} shape_wall_ms={} shape_cpu_ms={} shape_listener_wall_ms={} shape_listener_cpu_ms={} translucency_listener_wall_ms={} translucency_listener_cpu_ms={} translucency_expected_states={} translucency_standard_models_observed={} translucency_coverage_percent={} translucency_full_standard_reloads={} translucency_standard_wall_ms={} translucency_standard_cpu_ms={} translucency_observed_span_ms={} sprite_calls={} sprite_wall_sum_ms={} sprite_candidate_pixels={} sprite_true={} sprite_false={} sprite_inside_observed_model_calls={} sprite_inside_observed_model_wall_sum_ms={} sprite_layered_calls={} repeat_unique_keys={} repeat_unique_wall_ms={} repeat_calls={} repeat_wall_ms={} repeat_result_mismatches={} repeat_tracking_saturated={} repeat_untracked_wall_ms={} repeat_key_cap={} cpu_supported={}",
                reloadsStarted,
                wrappedListenerRuns,
                shapeFullCoverageReloads,
                formatNanos(shapeFullWallNanos),
                CPU_TIME_AVAILABLE ? formatNanos(shapeFullCpuNanos) : "n/a",
                formatNanos(shapeListenerWallNanos),
                CPU_TIME_AVAILABLE ? formatNanos(shapeListenerCpuNanos) : "n/a",
                formatNanos(translucencyListenerWallNanos),
                CPU_TIME_AVAILABLE ? formatNanos(translucencyListenerCpuNanos) : "n/a",
                expected,
                observed,
                String.format(Locale.ROOT, "%.3f", coverage),
                modelFullCoverageReloads,
                formatNanos(modelFullWallNanos),
                CPU_TIME_AVAILABLE ? formatNanos(modelFullCpuNanos) : "n/a",
                formatNanos(observedModelSpan),
                SPRITE_CALLS.sum(),
                formatNanos(SPRITE_WALL_NANOS.sum()),
                SPRITE_CANDIDATE_PIXELS.sum(),
                SPRITE_TRUE_RESULTS.sum(),
                SPRITE_FALSE_RESULTS.sum(),
                SPRITE_MODEL_CALLS.sum(),
                formatNanos(SPRITE_MODEL_WALL_NANOS.sum()),
                SPRITE_LAYERED_CALLS.sum(),
                REPEAT_TRACKED_UNIQUE.sum(),
                formatNanos(REPEAT_TRACKED_UNIQUE_WALL_NANOS.sum()),
                REPEAT_MATCHES.sum(),
                formatNanos(REPEAT_WALL_NANOS.sum()),
                REPEAT_RESULT_MISMATCHES.sum(),
                REPEAT_TRACKING_SATURATED.sum(),
                formatNanos(REPEAT_UNTRACKED_WALL_NANOS.sum()),
                MAX_REPEAT_KEYS_PER_RELOAD,
                CPU_TIME_AVAILABLE);
    }

    private static boolean isActive() {
        return StartupProfiler.isEnabled() && !reportEmitted;
    }

    private static void logListener(String phase, long wallNanos, long cpuNanos) {
        LOGGER.info(
                "BOOTOPTIM_MORECULLING phase={} wall_ms={} cpu_ms={}",
                phase,
                formatNanos(wallNanos),
                formatNanos(cpuNanos));
    }

    private static long currentThreadCpuNanos() {
        return CPU_TIME_AVAILABLE ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : -1L;
    }

    private static long elapsedCpuNanos(long startedNanos) {
        if (startedNanos < 0L || !CPU_TIME_AVAILABLE) {
            return -1L;
        }
        long now = THREAD_MX_BEAN.getCurrentThreadCpuTime();
        return now < startedNanos ? -1L : now - startedNanos;
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static final class SpriteCall {
        private boolean active;
        private long startedNanos;
        private NativeImage image;
        private boolean layered;
        private int minWidth;
        private int maxWidth;
        private int minHeight;
        private int maxHeight;
        private boolean insideObservedModel;
    }

    private static final class SpriteKey {
        private final NativeImage image;
        private final int minWidth;
        private final int maxWidth;
        private final int minHeight;
        private final int maxHeight;
        private final int hash;

        private SpriteKey(NativeImage image, int minWidth, int maxWidth, int minHeight, int maxHeight) {
            this.image = image;
            this.minWidth = minWidth;
            this.maxWidth = maxWidth;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            int value = System.identityHashCode(image);
            value = 31 * value + minWidth;
            value = 31 * value + maxWidth;
            value = 31 * value + minHeight;
            value = 31 * value + maxHeight;
            this.hash = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SpriteKey key)) {
                return false;
            }
            return image == key.image
                    && minWidth == key.minWidth
                    && maxWidth == key.maxWidth
                    && minHeight == key.minHeight
                    && maxHeight == key.maxHeight;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
