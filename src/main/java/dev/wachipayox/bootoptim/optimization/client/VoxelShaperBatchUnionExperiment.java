package dev.wachipayox.bootoptim.optimization.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Experiment-only VoxelShaper batch-union implementation and verifier.
 *
 * <p>The Ponder mixin feeds this class the already-rotated boxes produced by stock
 * VoxelShaper. This class never re-runs VoxelShape.forAllBoxes or VecHelper.rotate,
 * so the verifier does not duplicate third-party callbacks or floating-point
 * rotation arithmetic.</p>
 */
public final class VoxelShaperBatchUnionExperiment {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

    private static final boolean VERIFIER = Boolean.getBoolean("boot_optim.voxelShaperBatchUnionVerifier");
    private static final boolean CANDIDATE = Boolean.getBoolean("boot_optim.voxelShaperBatchUnion");
    private static final boolean ENABLED = VERIFIER || CANDIDATE;
    private static final long MAX_INTERMEDIATE_CELLS = Math.max(
            1L, Long.getLong("boot_optim.voxelShaperBatchUnionMaxCells", 262_144L));

    private static final ThreadLocal<CallContext> CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SELF_TEST = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Stats REAL = new Stats();
    private static final Stats TEST = new Stats();
    private static final List<String> FIRST_MISMATCHES = new ArrayList<>();

    private static volatile boolean versionChecked;
    private static volatile boolean versionCompatible;
    private static volatile String createVersion = "unknown";
    private static volatile String ponderVersion = "unknown";
    private static volatile boolean reported;

    private VoxelShaperBatchUnionExperiment() {
    }

    /** Called from VoxelShaper.rotatedCopy HEAD. */
    public static void begin(VoxelShape source, Vec3 rotation) {
        CONTEXT.remove();
        if (!ENABLED || !checkVersionGate()) {
            return;
        }
        if (rotation.equals(Vec3.ZERO)) {
            stats().zeroRotationCalls.increment();
            return;
        }

        CallContext context = new CallContext(stats(), VERIFIER);
        context.callStartNanos = System.nanoTime();
        context.candidate = Shapes.empty();
        CONTEXT.set(context);
        context.stats.calls.increment();
        if (source.isEmpty()) {
            context.stats.emptySourceCalls.increment();
        }
    }

    /**
     * Replaces only Ponder's Shapes.or accumulator call. With both experiment
     * properties off, or after a compatibility failure, this is exactly stock.
     */
    public static VoxelShape fold(VoxelShape stockAccumulator, VoxelShape rotatedBox) {
        CallContext context = CONTEXT.get();
        if (context == null) {
            return Shapes.or(stockAccumulator, rotatedBox);
        }

        context.boxes++;
        context.stats.boxes.increment();

        if (VERIFIER) {
            long stockCpuStart = cpuNow();
            long stockWallStart = System.nanoTime();
            VoxelShape stockResult = Shapes.or(stockAccumulator, rotatedBox);
            context.stats.stockFoldWallNanos.add(System.nanoTime() - stockWallStart);
            addCpu(context.stats.stockFoldCpuNanos, stockCpuStart);

            long candidateCpuStart = cpuNow();
            long candidateWallStart = System.nanoTime();
            if (context.fallback) {
                context.candidate = Shapes.or(context.candidate, rotatedBox);
                context.stats.fallbackBoxes.increment();
            } else {
                long cells = mergedCellUpperBound(context.candidate, rotatedBox);
                context.stats.maxGridCells.accumulateAndGet(cells, Math::max);
                if (cells > MAX_INTERMEDIATE_CELLS) {
                    context.fallback = true;
                    context.stats.fallbackBoxes.increment();
                    context.candidate = Shapes.or(context.candidate, rotatedBox);
                } else {
                    context.stats.batchBoxes.increment();
                    context.candidate = Shapes.joinUnoptimized(context.candidate, rotatedBox, BooleanOp.OR);
                }
            }
            context.stats.candidateFoldWallNanos.add(System.nanoTime() - candidateWallStart);
            addCpu(context.stats.candidateFoldCpuNanos, candidateCpuStart);
            return stockResult;
        }

        // Candidate mode: no stock double calculation. The accumulator supplied by
        // Ponder is itself the candidate accumulator because this value is returned
        // directly into Ponder's MutableObject on every box.
        if (context.fallback) {
            context.stats.fallbackBoxes.increment();
            return Shapes.or(stockAccumulator, rotatedBox);
        }
        long cells = mergedCellUpperBound(stockAccumulator, rotatedBox);
        context.stats.maxGridCells.accumulateAndGet(cells, Math::max);
        if (cells > MAX_INTERMEDIATE_CELLS) {
            context.fallback = true;
            context.stats.fallbackBoxes.increment();
            return Shapes.or(stockAccumulator, rotatedBox);
        }
        context.stats.batchBoxes.increment();
        return Shapes.joinUnoptimized(stockAccumulator, rotatedBox, BooleanOp.OR);
    }

    /** Called from VoxelShaper.rotatedCopy RETURN. */
    public static VoxelShape finish(VoxelShape stockOrCandidateResult) {
        CallContext context = CONTEXT.get();
        if (context == null) {
            return stockOrCandidateResult;
        }
        CONTEXT.remove();

        try {
            if (context.fallback) {
                context.stats.fallbackCalls.increment();
            } else {
                context.stats.pureBatchCalls.increment();
            }

            if (VERIFIER) {
                VoxelShape candidateResult = context.candidate;
                if (!context.fallback && context.boxes > 0) {
                    long cpuStart = cpuNow();
                    long wallStart = System.nanoTime();
                    candidateResult = candidateResult.optimize();
                    context.stats.finalOptimizeWallNanos.add(System.nanoTime() - wallStart);
                    addCpu(context.stats.finalOptimizeCpuNanos, cpuStart);
                }

                long compareCpuStart = cpuNow();
                long compareWallStart = System.nanoTime();
                String mismatch = compare(stockOrCandidateResult, candidateResult);
                context.stats.compareWallNanos.add(System.nanoTime() - compareWallStart);
                addCpu(context.stats.compareCpuNanos, compareCpuStart);
                context.stats.verifiedCalls.increment();
                if (mismatch == null) {
                    context.stats.matches.increment();
                } else {
                    context.stats.mismatches.increment();
                    rememberMismatch(mismatch + " boxes=" + context.boxes + " fallback=" + context.fallback);
                }
                // VERIFIER ALWAYS RETURNS STOCK.
                return stockOrCandidateResult;
            }

            if (context.boxes == 0 || context.fallback) {
                // Empty source retains Ponder's stock return. A fallback has already
                // crossed through Shapes.or(), which performed stock optimization.
                return stockOrCandidateResult;
            }
            long wallStart = System.nanoTime();
            VoxelShape optimized = stockOrCandidateResult.optimize();
            context.stats.finalOptimizeWallNanos.add(System.nanoTime() - wallStart);
            return optimized;
        } finally {
            context.stats.callWallNanos.add(System.nanoTime() - context.callStartNanos);
        }
    }

    /** Called after StartupProfiler has marked the real main menu. */
    public static void onMainMenu() {
        if (reported) {
            return;
        }
        reported = true;
        if (!ENABLED) {
            return;
        }

        reportResourceState();
        if (VERIFIER && checkVersionGate()) {
            runSelfTests();
        }
        report("real", REAL);
        if (VERIFIER) {
            report("self_test", TEST);
        }
        if (!FIRST_MISMATCHES.isEmpty()) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_BATCH_UNION_MISMATCHES count={} first={}",
                    FIRST_MISMATCHES.size(), FIRST_MISMATCHES);
        }
    }

    private static Stats stats() {
        return Boolean.TRUE.equals(SELF_TEST.get()) ? TEST : REAL;
    }

    private static synchronized boolean checkVersionGate() {
        if (versionChecked) {
            return versionCompatible;
        }
        versionChecked = true;
        try {
            createVersion = ModList.get().getModContainerById("create")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("missing");
            ponderVersion = ModList.get().getModContainerById("ponder")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("missing");
            versionCompatible = "6.0.10".equals(createVersion)
                    && ("1.0.82".equals(ponderVersion) || ponderVersion.startsWith("1.0.82+"));
        } catch (RuntimeException exception) {
            versionCompatible = false;
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_BATCH_UNION status=disabled reason=version_gate_failure", exception);
        }
        return versionCompatible;
    }

    private static long mergedCellUpperBound(VoxelShape first, VoxelShape second) {
        long product = 1L;
        for (Direction.Axis axis : Direction.Axis.values()) {
            long firstIntervals = Math.max(0, first.getCoords(axis).size() - 1L);
            long secondIntervals = Math.max(0, second.getCoords(axis).size() - 1L);
            long mergedIntervals = firstIntervals + secondIntervals;
            if (mergedIntervals == 0L) {
                continue;
            }
            if (product > Long.MAX_VALUE / mergedIntervals) {
                return Long.MAX_VALUE;
            }
            product *= mergedIntervals;
        }
        return product;
    }

    /**
     * Equality deliberately exceeds XOR-volume equality. Public bounds, the exact
     * coordinate grids and ordered forAllBoxes/toAabbs decomposition must also be
     * bit-identical, because collision, clip/raycast and third-party forAllBoxes
     * consumers can observe those representations/orderings.
     */
    private static String compare(VoxelShape stock, VoxelShape candidate) {
        if (stock.isEmpty() != candidate.isEmpty()) {
            return "empty";
        }
        if (!Shapes.equal(stock, candidate)) {
            return "xor_geometry";
        }
        if (!stock.isEmpty() && !sameAabb(stock.bounds(), candidate.bounds())) {
            return "bounds";
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList stockCoords = stock.getCoords(axis);
            DoubleList candidateCoords = candidate.getCoords(axis);
            if (stockCoords.size() != candidateCoords.size()) {
                return "coord_count_" + axis.getName();
            }
            for (int index = 0; index < stockCoords.size(); index++) {
                if (!sameDouble(stockCoords.getDouble(index), candidateCoords.getDouble(index))) {
                    return "coord_bits_" + axis.getName() + "_" + index;
                }
            }
        }

        List<AABB> stockBoxes = stock.toAabbs();
        List<AABB> candidateBoxes = candidate.toAabbs();
        if (stockBoxes.size() != candidateBoxes.size()) {
            return "ordered_box_count";
        }
        for (int index = 0; index < stockBoxes.size(); index++) {
            if (!sameAabb(stockBoxes.get(index), candidateBoxes.get(index))) {
                return "ordered_box_bits_" + index;
            }
        }
        if (!stock.getClass().getName().equals(candidate.getClass().getName())) {
            return "representation_class";
        }
        return null;
    }

    private static boolean sameAabb(AABB first, AABB second) {
        return sameDouble(first.minX, second.minX)
                && sameDouble(first.minY, second.minY)
                && sameDouble(first.minZ, second.minZ)
                && sameDouble(first.maxX, second.maxX)
                && sameDouble(first.maxY, second.maxY)
                && sameDouble(first.maxZ, second.maxZ);
    }

    private static boolean sameDouble(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static long cpuNow() {
        if (!VERIFIER || !THREAD_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_BEAN.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        return THREAD_BEAN.getCurrentThreadCpuTime();
    }

    private static void addCpu(LongAdder destination, long start) {
        if (start >= 0L) {
            long end = THREAD_BEAN.getCurrentThreadCpuTime();
            if (end >= start) {
                destination.add(end - start);
            }
        }
    }

    private static synchronized void rememberMismatch(String mismatch) {
        if (FIRST_MISMATCHES.size() < 8) {
            FIRST_MISMATCHES.add(mismatch);
        }
    }

    private static void report(String scope, Stats stats) {
        long calls = stats.calls.sum();
        long pure = stats.pureBatchCalls.sum();
        double pureCoverage = calls == 0L ? 0.0D : pure * 100.0D / calls;
        LOGGER.info(
                "BOOTOPTIM_VOXELSHAPER_BATCH_UNION status={} scope={} create_version={} ponder_version={} calls={} boxes={} verified={} matches={} mismatches={} pure_batch_calls={} fallback_calls={} pure_coverage_pct={} batch_boxes={} fallback_boxes={} empty_sources={} zero_rotation_calls={} max_grid_cells={} max_grid_guard={} stock_fold_wall_ms={} stock_fold_cpu_ms={} candidate_fold_wall_ms={} candidate_fold_cpu_ms={} final_optimize_wall_ms={} final_optimize_cpu_ms={} compare_wall_ms={} compare_cpu_ms={} call_wall_ms={}",
                VERIFIER ? "verifier_complete" : "candidate_complete",
                scope,
                createVersion,
                ponderVersion,
                calls,
                stats.boxes.sum(),
                stats.verifiedCalls.sum(),
                stats.matches.sum(),
                stats.mismatches.sum(),
                pure,
                stats.fallbackCalls.sum(),
                format(pureCoverage),
                stats.batchBoxes.sum(),
                stats.fallbackBoxes.sum(),
                stats.emptySourceCalls.sum(),
                stats.zeroRotationCalls.sum(),
                stats.maxGridCells.get(),
                MAX_INTERMEDIATE_CELLS,
                millis(stats.stockFoldWallNanos.sum()),
                millis(stats.stockFoldCpuNanos.sum()),
                millis(stats.candidateFoldWallNanos.sum()),
                millis(stats.candidateFoldCpuNanos.sum()),
                millis(stats.finalOptimizeWallNanos.sum()),
                millis(stats.finalOptimizeCpuNanos.sum()),
                millis(stats.compareWallNanos.sum()),
                millis(stats.compareCpuNanos.sum()),
                millis(stats.callWallNanos.sum()));
    }

    private static void runSelfTests() {
        int cases = 0;
        int reflectionFailures = 0;
        int identityFailures = 0;
        SELF_TEST.set(Boolean.TRUE);
        try {
            Class<?> shaperClass = Class.forName("net.createmod.catnip.math.VoxelShaper");
            Method forDirectional = shaperClass.getMethod("forDirectional", VoxelShape.class, Direction.class);
            Method forHorizontal = shaperClass.getMethod("forHorizontal", VoxelShape.class, Direction.class);
            Method forAxis = shaperClass.getMethod("forAxis", VoxelShape.class, Direction.Axis.class);
            Method forHorizontalAxis = shaperClass.getMethod("forHorizontalAxis", VoxelShape.class, Direction.Axis.class);
            Method getDirection = shaperClass.getMethod("get", Direction.class);
            Method withVerticalShapes = shaperClass.getMethod("withVerticalShapes", VoxelShape.class);

            for (VoxelShape shape : edgeCaseShapes()) {
                for (Direction facing : Direction.values()) {
                    Object shaper = forDirectional.invoke(null, shape, facing);
                    cases++;
                    if (getDirection.invoke(shaper, facing) != shape) {
                        identityFailures++;
                    }
                }
                for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                    Object shaper = forHorizontal.invoke(null, shape, facing);
                    cases++;
                    if (getDirection.invoke(shaper, facing) != shape) {
                        identityFailures++;
                    }
                }
                for (Direction.Axis axis : Direction.Axis.values()) {
                    Object shaper = forAxis.invoke(null, shape, axis);
                    cases++;
                    Direction identityDirection = Direction.get(Direction.AxisDirection.POSITIVE, axis);
                    if (getDirection.invoke(shaper, identityDirection) != shape) {
                        identityFailures++;
                    }

                    // Exercise the exact horizontal-axis factory for every Axis too;
                    // this includes its unusual Y input rather than normalizing it.
                    forHorizontalAxis.invoke(null, shape, axis);
                    cases++;
                }
                Object vertical = forHorizontal.invoke(null, shape, Direction.SOUTH);
                withVerticalShapes.invoke(vertical, shape);
                cases++;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            reflectionFailures++;
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_SELF_TEST status=reflection_failure", exception);
        } finally {
            SELF_TEST.remove();
        }

        LOGGER.info(
                "BOOTOPTIM_VOXELSHAPER_SELF_TEST status=complete cases={} verified_calls={} matches={} mismatches={} fallback_calls={} identity_failures={} reflection_failures={}",
                cases,
                TEST.verifiedCalls.sum(),
                TEST.matches.sum(),
                TEST.mismatches.sum(),
                TEST.fallbackCalls.sum(),
                identityFailures,
                reflectionFailures);
    }

    private static List<VoxelShape> edgeCaseShapes() {
        List<VoxelShape> shapes = new ArrayList<>();
        shapes.add(Shapes.empty());
        shapes.add(Shapes.box(0.125D, 0.1875D, 0.25D, 0.875D, 0.8125D, 0.75D));

        // Overlap: keep the input as a non-optimized OR so Ponder receives the
        // actual union decomposition chosen by vanilla VoxelShape.forAllBoxes.
        shapes.add(Shapes.joinUnoptimized(
                Shapes.box(0.0D, 0.0D, 0.0D, 0.75D, 0.75D, 0.75D),
                Shapes.box(0.25D, 0.25D, 0.25D, 1.0D, 1.0D, 1.0D),
                BooleanOp.OR));

        // Face-adjacent but not reducible to one rectangular prism.
        shapes.add(Shapes.joinUnoptimized(
                Shapes.box(0.0D, 0.0D, 0.0D, 0.5D, 0.5D, 1.0D),
                Shapes.box(0.5D, 0.25D, 0.0D, 1.0D, 0.75D, 1.0D),
                BooleanOp.OR));

        shapes.add(Shapes.box(-0.25D, -0.125D, 0.0D, 1.25D, 1.125D, 1.5D));
        shapes.add(Shapes.box(-0.5D, -0.25D, -0.125D, 0.5D, 0.75D, 0.875D));

        // Width is just above Shapes.EPSILON; no epsilon snapping or coordinate
        // normalization is performed by the experiment.
        shapes.add(Shapes.box(-5.0E-8D, 0.125D, 0.25D, 5.00001E-8D, 0.875D, 0.75D));
        return shapes;
    }

    private static void reportResourceState() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Path optionsPath = minecraft.gameDirectory.toPath().resolve("options.txt");
            Set<String> optionZipPacks = new LinkedHashSet<>();
            for (String line : Files.readAllLines(optionsPath)) {
                if (!line.startsWith("resourcePacks:")) {
                    continue;
                }
                JsonElement parsed = JsonParser.parseString(line.substring("resourcePacks:".length()));
                if (parsed.isJsonArray()) {
                    JsonArray array = parsed.getAsJsonArray();
                    for (JsonElement element : array) {
                        String id = element.getAsString();
                        if (id.startsWith("file/") && id.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                            optionZipPacks.add(id);
                        }
                    }
                }
            }

            List<PackResources> managerPacks = minecraft.getResourceManager().listPacks().toList();
            Set<String> managerIds = new LinkedHashSet<>();
            for (PackResources pack : managerPacks) {
                managerIds.add(pack.packId());
            }
            Set<String> missing = new LinkedHashSet<>(optionZipPacks);
            missing.removeAll(managerIds);
            long managerZipPacks = managerIds.stream()
                    .filter(id -> id.startsWith("file/") && id.toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .count();

            LOGGER.info(
                    "BOOTOPTIM_VOXELSHAPER_RESOURCES status=complete options_exists={} options_zip_packs={} manager_packs={} manager_zip_packs={} matched_zip_packs={} missing_zip_packs={} manager_empty={} manager_class={}",
                    Files.isRegularFile(optionsPath),
                    optionZipPacks.size(),
                    managerPacks.size(),
                    managerZipPacks,
                    optionZipPacks.size() - missing.size(),
                    missing.size(),
                    managerPacks.isEmpty(),
                    minecraft.getResourceManager().getClass().getName());
            if (!missing.isEmpty()) {
                LOGGER.warn("BOOTOPTIM_VOXELSHAPER_RESOURCES_MISSING ids={}", missing);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_RESOURCES status=failed", exception);
        }
    }

    private static String millis(long nanos) {
        return format(nanos / 1_000_000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class CallContext {
        private final Stats stats;
        private final boolean verifier;
        private VoxelShape candidate;
        private int boxes;
        private boolean fallback;
        private long callStartNanos;

        private CallContext(Stats stats, boolean verifier) {
            this.stats = stats;
            this.verifier = verifier;
        }
    }

    private static final class Stats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder boxes = new LongAdder();
        private final LongAdder verifiedCalls = new LongAdder();
        private final LongAdder matches = new LongAdder();
        private final LongAdder mismatches = new LongAdder();
        private final LongAdder pureBatchCalls = new LongAdder();
        private final LongAdder fallbackCalls = new LongAdder();
        private final LongAdder batchBoxes = new LongAdder();
        private final LongAdder fallbackBoxes = new LongAdder();
        private final LongAdder emptySourceCalls = new LongAdder();
        private final LongAdder zeroRotationCalls = new LongAdder();
        private final AtomicLong maxGridCells = new AtomicLong();
        private final LongAdder stockFoldWallNanos = new LongAdder();
        private final LongAdder stockFoldCpuNanos = new LongAdder();
        private final LongAdder candidateFoldWallNanos = new LongAdder();
        private final LongAdder candidateFoldCpuNanos = new LongAdder();
        private final LongAdder finalOptimizeWallNanos = new LongAdder();
        private final LongAdder finalOptimizeCpuNanos = new LongAdder();
        private final LongAdder compareWallNanos = new LongAdder();
        private final LongAdder compareCpuNanos = new LongAdder();
        private final LongAdder callWallNanos = new LongAdder();
    }
}
