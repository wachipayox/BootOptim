package dev.wachipayox.bootoptim.profiling.client;

import java.lang.StackWalker;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Diagnostic-only, low-overhead startup attribution for voxel-shape construction and joins.
 *
 * <p>Hot generic paths keep exact aggregate counters/timers but only sample stack attribution.
 * Source-specific builders are rare enough to keep exact parameter-tuple repetition and identity
 * observations. No shape result is replaced or cached by this profiler.
 */
public final class VoxelShapeStartupProfiler {
    public static final String ENABLE_PROPERTY = "boot_optim.profileVoxelShapes";
    public static final String SAMPLE_INTERVAL_PROPERTY = "boot_optim.voxelShapeSampleInterval";
    public static final String BITSET_SAMPLE_INTERVAL_PROPERTY = "boot_optim.voxelShapeBitSetSampleInterval";

    private static final boolean ENABLED = Boolean.getBoolean(ENABLE_PROPERTY)
            || Boolean.getBoolean("boot_optim.benchmark.exitOnTitle");
    private static final int JOIN_SAMPLE_INTERVAL = powerOfTwoInterval(SAMPLE_INTERVAL_PROPERTY, 128);
    private static final int JOIN_SAMPLE_MASK = JOIN_SAMPLE_INTERVAL - 1;
    private static final int BITSET_SAMPLE_INTERVAL = powerOfTwoInterval(BITSET_SAMPLE_INTERVAL_PROPERTY, 256);
    private static final int BITSET_SAMPLE_MASK = BITSET_SAMPLE_INTERVAL - 1;
    private static final int SITE_LIMIT = 384;
    private static final int SOURCE_KEY_LIMIT = 512;
    private static final int IDENTITY_LIMIT = 128;
    private static final int OUTPUT_LIMIT = 96;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean THREAD_CPU_AVAILABLE =
            THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final PhaseStats[] PHASE_STATS = new PhaseStats[Phase.values().length];
    private static final ConcurrentHashMap<SiteKey, SiteStats> SITES = new ConcurrentHashMap<>();
    private static final LongAdder SITE_OVERFLOW = new LongAdder();
    private static final LongAdder SOURCE_OVERFLOW = new LongAdder();
    private static final LongAdder NESTED_JOIN_SCOPES = new LongAdder();
    private static final LongAdder SOURCE_STACK_MISMATCHES = new LongAdder();

    private static final ThreadLocal<HotThreadState> HOT_THREAD =
            ThreadLocal.withInitial(HotThreadState::new);
    private static final ThreadLocal<ArrayDeque<SourceFrame>> SOURCE_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final Object PHASE_LOCK = new Object();
    private static final Object SOURCE_LOCK = new Object();
    private static final Map<SourceKey, SourceStats> SOURCE_STATS = new HashMap<>();
    private static final AtomicBoolean MODEL_RELOAD_SEEN = new AtomicBoolean();
    private static final AtomicBoolean FINISHED = new AtomicBoolean();

    private static volatile Phase currentPhase = Phase.PRE_MOD_ENTRYPOINT;
    private static volatile long currentPhaseStartUptimeMs = 0L;

    static {
        for (int i = 0; i < PHASE_STATS.length; i++) {
            PHASE_STATS[i] = new PhaseStats();
        }
    }

    private VoxelShapeStartupProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void markModEntrypoint() {
        if (!ENABLED) {
            return;
        }
        transitionTo(Phase.MOD_LOADING);
    }

    public static void beginModelReload() {
        if (!ENABLED || !MODEL_RELOAD_SEEN.compareAndSet(false, true)) {
            return;
        }
        transitionTo(Phase.MODEL_RELOAD);
    }

    public static void observeModelReloadFuture(CompletableFuture<?> future) {
        if (!ENABLED || future == null || !MODEL_RELOAD_SEEN.get()) {
            return;
        }
        future.whenComplete((ignored, failure) -> transitionTo(Phase.POST_MODEL_RELOAD));
    }

    public static void beginJoin(VoxelShape first, VoxelShape second, BooleanOp operator) {
        if (!ENABLED) {
            return;
        }

        Phase phase = currentPhase;
        PhaseStats phaseStats = PHASE_STATS[phase.ordinal()];
        phaseStats.joinCalls.increment();

        HotThreadState state = HOT_THREAD.get();
        state.joinDepth++;
        if (state.joinDepth != 1) {
            NESTED_JOIN_SCOPES.increment();
            return;
        }

        state.joinPhase = phase;
        state.joinStartedNanos = System.nanoTime();
        int sequence = ++state.joinSequence;
        state.joinSampled = sequence == 1 || (sequence & JOIN_SAMPLE_MASK) == 0;

        if (!state.joinSampled) {
            state.joinSite = null;
            state.joinCpuStartedNanos = -1L;
            return;
        }

        state.joinCpuStartedNanos = currentThreadCpuTime();
        String caller = findExternalCaller();
        String[] callerParts = splitCaller(caller);
        String tuple = "op=" + token(operator == null ? "null" : operator.getClass().getName())
                + ",a=" + token(first == null ? "null" : first.getClass().getSimpleName())
                + ",b=" + token(second == null ? "null" : second.getClass().getSimpleName());
        state.joinSite = new SiteKey(
                "join",
                phase,
                callerParts[0],
                callerParts[1],
                Integer.parseInt(callerParts[2]),
                tuple);
    }

    public static void endJoin(VoxelShape first, VoxelShape second, VoxelShape result) {
        if (!ENABLED) {
            return;
        }

        HotThreadState state = HOT_THREAD.get();
        if (state.joinDepth <= 0) {
            return;
        }
        if (state.joinDepth != 1) {
            state.joinDepth--;
            return;
        }

        long elapsed = Math.max(0L, System.nanoTime() - state.joinStartedNanos);
        PhaseStats phaseStats = PHASE_STATS[state.joinPhase.ordinal()];
        phaseStats.joinTaskNanos.add(elapsed);
        phaseStats.joinMaxNanos.accumulate(elapsed);

        if (state.joinSampled && state.joinSite != null) {
            long cpuElapsed = elapsedCpu(state.joinCpuStartedNanos);
            phaseStats.joinSamples.increment();
            phaseStats.joinSampleTaskNanos.add(elapsed);
            if (cpuElapsed >= 0L) {
                phaseStats.joinCpuSamples.increment();
                phaseStats.joinSampleCpuNanos.add(cpuElapsed);
            }

            SiteStats siteStats = siteStats(state.joinSite);
            if (siteStats != null) {
                siteStats.samples.increment();
                siteStats.sampleTaskNanos.add(elapsed);
                siteStats.maxTaskNanos.accumulate(elapsed);
                if (cpuElapsed >= 0L) {
                    siteStats.sampleCpuNanos.add(cpuElapsed);
                }
                if (result == first) {
                    siteStats.resultSameFirst.increment();
                }
                if (result == second) {
                    siteStats.resultSameSecond.increment();
                }
            }
        }

        state.joinDepth = 0;
        state.joinSampled = false;
        state.joinSite = null;
        state.joinCpuStartedNanos = -1L;
    }

    public static void recordBitSetConstruction(int xSize, int ySize, int zSize, String constructorKind) {
        if (!ENABLED) {
            return;
        }

        Phase phase = currentPhase;
        PHASE_STATS[phase.ordinal()].bitSetConstructors.increment();

        HotThreadState state = HOT_THREAD.get();
        int sequence = ++state.bitSetSequence;
        if (sequence != 1 && (sequence & BITSET_SAMPLE_MASK) != 0) {
            return;
        }

        String caller = findExternalCaller();
        String[] callerParts = splitCaller(caller);
        SiteKey key = new SiteKey(
                "bitset_ctor",
                phase,
                callerParts[0],
                callerParts[1],
                Integer.parseInt(callerParts[2]),
                "kind=" + token(constructorKind)
                        + ",dims=" + xSize + "x" + ySize + "x" + zSize);
        SiteStats stats = siteStats(key);
        if (stats != null) {
            stats.samples.increment();
        }
    }

    public static void beginWallShapes(
            Block owner,
            float width,
            float depth,
            float wallPostHeight,
            float wallMinY,
            float wallLowHeight,
            float wallTallHeight) {
        beginSource(
                "wall_make_shapes",
                owner,
                floatTuple(
                        "w", width,
                        "d", depth,
                        "post", wallPostHeight,
                        "miny", wallMinY,
                        "low", wallLowHeight,
                        "tall", wallTallHeight));
    }

    public static void endWallShapes(Object result) {
        endSource("wall_make_shapes", result);
    }

    public static void beginCrossCollisionShapes(
            Block owner,
            float nodeWidth,
            float extensionWidth,
            float nodeHeight,
            float extensionBottom,
            float extensionHeight) {
        beginSource(
                "cross_collision_make_shapes",
                owner,
                floatTuple(
                        "nodew", nodeWidth,
                        "extw", extensionWidth,
                        "nodeh", nodeHeight,
                        "extbottom", extensionBottom,
                        "exth", extensionHeight));
    }

    public static void endCrossCollisionShapes(Object result) {
        endSource("cross_collision_make_shapes", result);
    }

    public static void beginStateShapeTable(Block owner) {
        int states = -1;
        try {
            states = owner.getStateDefinition().getPossibleStates().size();
        } catch (Throwable ignored) {
            // Diagnostic metadata only; never perturb block construction.
        }
        beginSource("block_state_shape_table", owner, "states=" + states);
    }

    public static void endStateShapeTable(Object result) {
        endSource("block_state_shape_table", result);
    }

    public static void finishAndDump() {
        if (!ENABLED || !FINISHED.compareAndSet(false, true)) {
            return;
        }

        closeCurrentPhase();
        NamespaceIndex namespaces = NamespaceIndex.build();

        long totalJoinCalls = 0L;
        long totalJoinTaskNanos = 0L;
        long totalBitSetConstructors = 0L;
        for (PhaseStats stats : PHASE_STATS) {
            totalJoinCalls += stats.joinCalls.sum();
            totalJoinTaskNanos += stats.joinTaskNanos.sum();
            totalBitSetConstructors += stats.bitSetConstructors.sum();
        }

        int sourceKeyCount;
        List<Map.Entry<SourceKey, SourceStats>> sources;
        synchronized (SOURCE_LOCK) {
            sourceKeyCount = SOURCE_STATS.size();
            sources = new ArrayList<>(SOURCE_STATS.entrySet());
        }

        System.out.printf(
                Locale.ROOT,
                "BOOTOPTIM_VOXEL_SHAPES event=summary enabled=true join_calls_exact=%d join_task_sum_ms=%.3f "
                        + "bitset_ctor_calls_exact=%d join_sample_interval=%d bitset_sample_interval=%d "
                        + "site_keys=%d site_overflow=%d source_keys=%d source_overflow=%d "
                        + "nested_join_scopes=%d source_stack_mismatches=%d critical_wall=ceiling_only%n",
                totalJoinCalls,
                millis(totalJoinTaskNanos),
                totalBitSetConstructors,
                JOIN_SAMPLE_INTERVAL,
                BITSET_SAMPLE_INTERVAL,
                SITES.size(),
                SITE_OVERFLOW.sum(),
                sourceKeyCount,
                SOURCE_OVERFLOW.sum(),
                NESTED_JOIN_SCOPES.sum(),
                SOURCE_STACK_MISMATCHES.sum());

        for (Phase phase : Phase.values()) {
            PhaseStats stats = PHASE_STATS[phase.ordinal()];
            long calls = stats.joinCalls.sum();
            long taskNanos = stats.joinTaskNanos.sum();
            long sampleCalls = stats.joinSamples.sum();
            long cpuSamples = stats.joinCpuSamples.sum();
            long sampleTaskNanos = stats.joinSampleTaskNanos.sum();
            long sampleCpuNanos = stats.joinSampleCpuNanos.sum();
            long wallMs = stats.wallMs.sum();
            double sampleCpuToTaskRatio = cpuSamples == 0L || sampleTaskNanos == 0L
                    ? -1.0D
                    : (double) sampleCpuNanos / (double) sampleTaskNanos;
            double wallCeilingMs = Math.min(millis(taskNanos), wallMs);

            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_VOXEL_SHAPES kind=phase phase=%s wall_ms=%d join_calls_exact=%d "
                            + "join_task_sum_ms=%.3f join_task_max_ms=%.3f join_samples=%d join_cpu_samples=%d "
                            + "join_sample_task_sum_ms=%.3f join_sample_cpu_ms=%.3f join_sample_cpu_to_task_ratio=%.4f "
                            + "bitset_ctor_calls_exact=%d critical_wall_ceiling_ms=%.3f "
                            + "task_sum_is_not_wall=true cpu_metric=sample_only_no_total_estimate%n",
                    phase.token,
                    wallMs,
                    calls,
                    millis(taskNanos),
                    millis(stats.joinMaxNanos.get()),
                    sampleCalls,
                    cpuSamples,
                    millis(sampleTaskNanos),
                    millis(sampleCpuNanos),
                    sampleCpuToTaskRatio,
                    stats.bitSetConstructors.sum(),
                    wallCeilingMs);
        }

        sources.sort(Comparator.comparingLong((Map.Entry<SourceKey, SourceStats> entry) ->
                        entry.getValue().taskNanos)
                .reversed());
        int sourceRank = 0;
        for (Map.Entry<SourceKey, SourceStats> entry : sources) {
            if (++sourceRank > OUTPUT_LIMIT) {
                break;
            }
            SourceKey key = entry.getKey();
            SourceSnapshot snapshot;
            synchronized (SOURCE_LOCK) {
                snapshot = entry.getValue().snapshot(namespaces);
            }

            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_VOXEL_SHAPES kind=source rank=%d source=%s phase=%s block_class=%s "
                            + "namespaces=%s block_ids=%s tuple=%s calls_exact=%d owner_instances=%d "
                            + "same_owner_repeats=%d task_sum_ms=%.3f max_ms=%.3f result_containers=%d "
                            + "result_container_reuses=%d result_slots=%d unique_shape_identities_sum=%d "
                            + "duplicate_shape_slots=%d identity_tracking_capped=%s%n",
                    sourceRank,
                    key.kind,
                    key.phase.token,
                    token(key.blockClass),
                    token(snapshot.namespaces),
                    token(snapshot.blockIds),
                    token(key.tuple),
                    snapshot.calls,
                    snapshot.ownerInstances,
                    Math.max(0L, snapshot.calls - snapshot.ownerInstances),
                    millis(snapshot.taskNanos),
                    millis(snapshot.maxNanos),
                    snapshot.resultContainers,
                    snapshot.resultContainerReuses,
                    snapshot.resultSlots,
                    snapshot.uniqueShapeIdentities,
                    snapshot.duplicateShapeSlots,
                    snapshot.identityTrackingCapped);
        }

        List<Map.Entry<SiteKey, SiteStats>> sites = new ArrayList<>(SITES.entrySet());
        sites.sort((left, right) -> {
            int byTask = Long.compare(
                    right.getValue().sampleTaskNanos.sum(),
                    left.getValue().sampleTaskNanos.sum());
            if (byTask != 0) {
                return byTask;
            }
            return Long.compare(right.getValue().samples.sum(), left.getValue().samples.sum());
        });
        int siteRank = 0;
        for (Map.Entry<SiteKey, SiteStats> entry : sites) {
            if (++siteRank > OUTPUT_LIMIT) {
                break;
            }
            SiteKey key = entry.getKey();
            SiteStats stats = entry.getValue();
            long samples = stats.samples.sum();
            int interval = key.operation.equals("join") ? JOIN_SAMPLE_INTERVAL : BITSET_SAMPLE_INTERVAL;
            String namespaceHint = namespaces.hint(key.callerClass);

            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_VOXEL_SHAPES kind=site rank=%d operation=%s phase=%s caller=%s method=%s "
                            + "line=%d namespace_hint=%s tuple=%s samples=%d "
                            + "sample_task_sum_ms=%.3f sample_cpu_ms=%.3f sample_max_ms=%.3f "
                            + "result_same_first=%d result_same_second=%d sampling=first_plus_1_in_%d "
                            + "call_count=sample_only_no_extrapolation%n",
                    siteRank,
                    key.operation,
                    key.phase.token,
                    token(key.callerClass),
                    token(key.method),
                    key.line,
                    token(namespaceHint),
                    token(key.tuple),
                    samples,
                    millis(stats.sampleTaskNanos.sum()),
                    millis(stats.sampleCpuNanos.sum()),
                    millis(stats.maxTaskNanos.get()),
                    stats.resultSameFirst.sum(),
                    stats.resultSameSecond.sum(),
                    interval);
        }

        System.out.println(
                "BOOTOPTIM_VOXEL_SHAPES event=interpretation exact=phase_counts+source_tuple_counts "
                        + "sampled=call_sites+cpu site_call_count=no_extrapolation cpu_total=no_extrapolation "
                        + "result_hash=identity_only structural_hash=omitted "
                        + "reason=no_cheap_stable_structural_hash wall_claim=ceiling_not_recoverable");
    }

    private static void beginSource(String kind, Block owner, String tuple) {
        if (!ENABLED) {
            return;
        }
        Phase phase = currentPhase;
        SourceKey key = new SourceKey(kind, phase, owner.getClass().getName(), tuple);
        SOURCE_STACK.get().push(new SourceFrame(key, owner, System.nanoTime()));
    }

    private static void endSource(String expectedKind, Object result) {
        if (!ENABLED) {
            return;
        }

        ArrayDeque<SourceFrame> stack = SOURCE_STACK.get();
        if (stack.isEmpty()) {
            SOURCE_STACK_MISMATCHES.increment();
            return;
        }

        SourceFrame frame = stack.pop();
        if (!frame.key.kind.equals(expectedKind)) {
            SOURCE_STACK_MISMATCHES.increment();
        }
        if (stack.isEmpty()) {
            SOURCE_STACK.remove();
        }

        long elapsed = Math.max(0L, System.nanoTime() - frame.startedNanos);
        SourceResultSummary resultSummary = summarizeResult(result);

        synchronized (SOURCE_LOCK) {
            SourceStats stats = SOURCE_STATS.get(frame.key);
            if (stats == null) {
                if (SOURCE_STATS.size() >= SOURCE_KEY_LIMIT) {
                    SOURCE_OVERFLOW.increment();
                    return;
                }
                stats = new SourceStats();
                SOURCE_STATS.put(frame.key, stats);
            }
            stats.observe(frame.owner, result, resultSummary, elapsed);
        }
    }

    private static SiteStats siteStats(SiteKey key) {
        SiteStats existing = SITES.get(key);
        if (existing != null) {
            return existing;
        }
        if (SITES.size() >= SITE_LIMIT) {
            SITE_OVERFLOW.increment();
            return null;
        }
        SiteStats created = new SiteStats();
        SiteStats raced = SITES.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private static SourceResultSummary summarizeResult(Object result) {
        if (result == null) {
            return new SourceResultSummary(0, 0);
        }

        IdentityHashMap<VoxelShape, Boolean> unique = new IdentityHashMap<>();
        int slots = 0;
        if (result instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof VoxelShape shape) {
                    slots++;
                    unique.put(shape, Boolean.TRUE);
                }
            }
        } else if (result instanceof VoxelShape[] array) {
            for (VoxelShape shape : array) {
                if (shape != null) {
                    slots++;
                    unique.put(shape, Boolean.TRUE);
                }
            }
        } else if (result instanceof VoxelShape shape) {
            slots = 1;
            unique.put(shape, Boolean.TRUE);
        }
        return new SourceResultSummary(slots, unique.size());
    }

    private static void transitionTo(Phase next) {
        if (!ENABLED || FINISHED.get()) {
            return;
        }

        synchronized (PHASE_LOCK) {
            Phase previous = currentPhase;
            if (next.ordinal() <= previous.ordinal()) {
                return;
            }
            long now = uptimeMs();
            PHASE_STATS[previous.ordinal()].wallMs.add(Math.max(0L, now - currentPhaseStartUptimeMs));
            currentPhase = next;
            currentPhaseStartUptimeMs = now;
            System.out.printf(
                    Locale.ROOT,
                    "BOOTOPTIM_VOXEL_SHAPES event=phase from=%s to=%s uptime_ms=%d%n",
                    previous.token,
                    next.token,
                    now);
        }
    }

    private static void closeCurrentPhase() {
        synchronized (PHASE_LOCK) {
            long now = uptimeMs();
            PHASE_STATS[currentPhase.ordinal()].wallMs.add(Math.max(0L, now - currentPhaseStartUptimeMs));
            currentPhaseStartUptimeMs = now;
        }
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private static long currentThreadCpuTime() {
        if (!THREAD_CPU_AVAILABLE) {
            return -1L;
        }
        try {
            return THREADS.getCurrentThreadCpuTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long elapsedCpu(long started) {
        if (started < 0L) {
            return -1L;
        }
        long now = currentThreadCpuTime();
        return now < 0L ? -1L : Math.max(0L, now - started);
    }

    private static String findExternalCaller() {
        try {
            return WALKER.walk(stream -> stream
                    .filter(frame -> !isProfilerFrame(frame.getClassName()))
                    .findFirst()
                    .map(frame -> frame.getClassName() + "|" + frame.getMethodName() + "|" + frame.getLineNumber())
                    .orElse("unknown|unknown|-1"));
        } catch (Throwable ignored) {
            return "unknown|unknown|-1";
        }
    }

    private static boolean isProfilerFrame(String className) {
        return className.startsWith("dev.wachipayox.bootoptim.")
                || className.startsWith("net.minecraft.world.phys.shapes.");
    }

    private static String[] splitCaller(String caller) {
        String[] parts = caller.split("\\|", -1);
        if (parts.length == 3) {
            return parts;
        }
        return new String[] {"unknown", "unknown", "-1"};
    }

    private static String floatTuple(Object... namesAndValues) {
        StringBuilder builder = new StringBuilder(96);
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            String name = String.valueOf(namesAndValues[i]);
            float value = ((Number) namesAndValues[i + 1]).floatValue();
            builder.append(name)
                    .append('=')
                    .append(Float.toString(value))
                    .append('@')
                    .append(Integer.toHexString(Float.floatToIntBits(value)));
        }
        return builder.toString();
    }

    private static int powerOfTwoInterval(String property, int fallback) {
        int value = Integer.getInteger(property, fallback);
        if (value < 16 || value > 4096 || (value & (value - 1)) != 0) {
            return fallback;
        }
        return value;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        return value.replace(' ', '_')
                .replace('\t', '_')
                .replace('\r', '_')
                .replace('\n', '_');
    }

    private enum Phase {
        PRE_MOD_ENTRYPOINT("pre_mod_entrypoint"),
        MOD_LOADING("mod_loading"),
        MODEL_RELOAD("model_reload"),
        POST_MODEL_RELOAD("post_model_reload");

        private final String token;

        Phase(String token) {
            this.token = token;
        }
    }

    private static final class PhaseStats {
        private final LongAdder wallMs = new LongAdder();
        private final LongAdder joinCalls = new LongAdder();
        private final LongAdder joinTaskNanos = new LongAdder();
        private final LongAccumulator joinMaxNanos = new LongAccumulator(Long::max, 0L);
        private final LongAdder joinSamples = new LongAdder();
        private final LongAdder joinCpuSamples = new LongAdder();
        private final LongAdder joinSampleTaskNanos = new LongAdder();
        private final LongAdder joinSampleCpuNanos = new LongAdder();
        private final LongAdder bitSetConstructors = new LongAdder();
    }

    private static final class HotThreadState {
        private int joinSequence;
        private int bitSetSequence;
        private int joinDepth;
        private long joinStartedNanos;
        private long joinCpuStartedNanos = -1L;
        private boolean joinSampled;
        private Phase joinPhase = Phase.PRE_MOD_ENTRYPOINT;
        private SiteKey joinSite;
    }

    private record SiteKey(
            String operation,
            Phase phase,
            String callerClass,
            String method,
            int line,
            String tuple) {
    }

    private static final class SiteStats {
        private final LongAdder samples = new LongAdder();
        private final LongAdder sampleTaskNanos = new LongAdder();
        private final LongAdder sampleCpuNanos = new LongAdder();
        private final LongAccumulator maxTaskNanos = new LongAccumulator(Long::max, 0L);
        private final LongAdder resultSameFirst = new LongAdder();
        private final LongAdder resultSameSecond = new LongAdder();
    }

    private record SourceKey(String kind, Phase phase, String blockClass, String tuple) {
    }

    private record SourceFrame(SourceKey key, Block owner, long startedNanos) {
    }

    private record SourceResultSummary(int slots, int uniqueShapeIdentities) {
    }

    private static final class SourceStats {
        private long calls;
        private long taskNanos;
        private long maxNanos;
        private long resultSlots;
        private long uniqueShapeIdentities;
        private long duplicateShapeSlots;
        private long resultContainerReuses;
        private boolean identityTrackingCapped;
        private final List<WeakReference<Object>> ownerIdentities = new ArrayList<>();
        private final List<WeakReference<Object>> resultIdentities = new ArrayList<>();

        private void observe(Block owner, Object result, SourceResultSummary resultSummary, long elapsed) {
            calls++;
            taskNanos += elapsed;
            maxNanos = Math.max(maxNanos, elapsed);
            resultSlots += resultSummary.slots;
            uniqueShapeIdentities += resultSummary.uniqueShapeIdentities;
            duplicateShapeSlots += Math.max(0, resultSummary.slots - resultSummary.uniqueShapeIdentities);

            trackIdentity(ownerIdentities, owner);
            if (result != null) {
                if (containsIdentity(resultIdentities, result)) {
                    resultContainerReuses++;
                } else if (!addIdentity(resultIdentities, result)) {
                    identityTrackingCapped = true;
                }
            }
        }

        private SourceSnapshot snapshot(NamespaceIndex namespaces) {
            LinkedHashSet<String> namespaceSet = new LinkedHashSet<>();
            LinkedHashSet<String> blockIds = new LinkedHashSet<>();
            int owners = 0;
            for (WeakReference<Object> reference : ownerIdentities) {
                Object value = reference.get();
                if (!(value instanceof Block block)) {
                    continue;
                }
                owners++;
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null) {
                    namespaceSet.add(id.getNamespace());
                    if (blockIds.size() < 8) {
                        blockIds.add(id.toString());
                    }
                }
            }
            if (namespaceSet.isEmpty()) {
                namespaceSet.add(namespaces.hint("unknown"));
            }
            return new SourceSnapshot(
                    calls,
                    taskNanos,
                    maxNanos,
                    owners,
                    resultIdentities.size(),
                    resultContainerReuses,
                    resultSlots,
                    uniqueShapeIdentities,
                    duplicateShapeSlots,
                    identityTrackingCapped || ownerIdentities.size() >= IDENTITY_LIMIT,
                    String.join("+", namespaceSet),
                    blockIds.isEmpty() ? "unresolved" : String.join("+", blockIds));
        }

        private void trackIdentity(List<WeakReference<Object>> references, Object value) {
            if (containsIdentity(references, value)) {
                return;
            }
            if (!addIdentity(references, value)) {
                identityTrackingCapped = true;
            }
        }

        private boolean containsIdentity(List<WeakReference<Object>> references, Object value) {
            for (int i = references.size() - 1; i >= 0; i--) {
                Object existing = references.get(i).get();
                if (existing == null) {
                    references.remove(i);
                    continue;
                }
                if (existing == value) {
                    return true;
                }
            }
            return false;
        }

        private boolean addIdentity(List<WeakReference<Object>> references, Object value) {
            if (references.size() >= IDENTITY_LIMIT) {
                return false;
            }
            references.add(new WeakReference<>(value));
            return true;
        }
    }

    private record SourceSnapshot(
            long calls,
            long taskNanos,
            long maxNanos,
            int ownerInstances,
            int resultContainers,
            long resultContainerReuses,
            long resultSlots,
            long uniqueShapeIdentities,
            long duplicateShapeSlots,
            boolean identityTrackingCapped,
            String namespaces,
            String blockIds) {
    }

    private static final class NamespaceIndex {
        private final Map<String, Set<String>> classNamespaces;
        private final Map<String, Set<String>> packageNamespaces;

        private NamespaceIndex(
                Map<String, Set<String>> classNamespaces,
                Map<String, Set<String>> packageNamespaces) {
            this.classNamespaces = classNamespaces;
            this.packageNamespaces = packageNamespaces;
        }

        private static NamespaceIndex build() {
            Map<String, Set<String>> classes = new HashMap<>();
            Map<String, Set<String>> packages = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) {
                    continue;
                }
                String namespace = id.getNamespace();
                Class<?> type = block.getClass();
                classes.computeIfAbsent(type.getName(), ignored -> new LinkedHashSet<>()).add(namespace);
                Package typePackage = type.getPackage();
                if (typePackage != null) {
                    packages.computeIfAbsent(typePackage.getName(), ignored -> new LinkedHashSet<>()).add(namespace);
                }
            }
            return new NamespaceIndex(classes, packages);
        }

        private String hint(String className) {
            if (className == null || className.equals("unknown")) {
                return "unknown";
            }
            if (className.startsWith("net.minecraft.")) {
                return "minecraft";
            }

            Set<String> exact = classNamespaces.get(className);
            if (exact != null && !exact.isEmpty()) {
                return String.join("+", exact);
            }

            int end = className.lastIndexOf('.');
            while (end > 0) {
                String packageName = className.substring(0, end);
                Set<String> packageMatch = packageNamespaces.get(packageName);
                if (packageMatch != null && !packageMatch.isEmpty()) {
                    return String.join("+", packageMatch) + "(package_hint)";
                }
                end = packageName.lastIndexOf('.');
            }
            return "unknown";
        }
    }
}
