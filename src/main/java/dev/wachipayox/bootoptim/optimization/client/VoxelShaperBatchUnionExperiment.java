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

/** Experiment-only Ponder VoxelShaper batch-union implementation and verifier. */
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

    private VoxelShaperBatchUnionExperiment() {}

    public static void begin(VoxelShape source, Vec3 rotation) {
        CONTEXT.remove();
        if (!ENABLED || !checkVersionGate()) return;
        if (rotation.equals(Vec3.ZERO)) {
            stats().zeroRotationCalls.increment();
            return;
        }
        CallContext context = new CallContext(stats());
        context.callStartNanos = System.nanoTime();
        context.candidate = Shapes.empty();
        CONTEXT.set(context);
        context.stats.calls.increment();
        if (source.isEmpty()) context.stats.emptySourceCalls.increment();
    }

    /** Redirect for only Ponder's existing Shapes.or accumulator call. */
    public static VoxelShape fold(VoxelShape stockAccumulator, VoxelShape rotatedBox) {
        CallContext context = CONTEXT.get();
        if (context == null) return Shapes.or(stockAccumulator, rotatedBox);
        context.boxes++;
        context.stats.boxes.increment();

        if (VERIFIER) {
            long stockCpu = cpuNow();
            long stockWall = System.nanoTime();
            VoxelShape stockResult = Shapes.or(stockAccumulator, rotatedBox);
            context.stats.stockFoldWallNanos.add(System.nanoTime() - stockWall);
            addCpu(context.stats.stockFoldCpuNanos, stockCpu);

            long candidateCpu = cpuNow();
            long candidateWall = System.nanoTime();
            if (context.fallback) {
                context.stats.fallbackBoxes.increment();
                context.candidate = Shapes.or(context.candidate, rotatedBox);
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
            context.stats.candidateFoldWallNanos.add(System.nanoTime() - candidateWall);
            addCpu(context.stats.candidateFoldCpuNanos, candidateCpu);
            return stockResult;
        }

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

    public static VoxelShape finish(VoxelShape stockOrCandidateResult) {
        CallContext context = CONTEXT.get();
        if (context == null) return stockOrCandidateResult;
        CONTEXT.remove();
        try {
            if (context.fallback) context.stats.fallbackCalls.increment();
            else context.stats.pureBatchCalls.increment();

            if (VERIFIER) {
                VoxelShape candidateResult = context.candidate;
                if (!context.fallback && context.boxes > 0) {
                    long cpu = cpuNow();
                    long wall = System.nanoTime();
                    candidateResult = candidateResult.optimize();
                    context.stats.finalOptimizeWallNanos.add(System.nanoTime() - wall);
                    addCpu(context.stats.finalOptimizeCpuNanos, cpu);
                }
                long compareCpu = cpuNow();
                long compareWall = System.nanoTime();
                String mismatch = compare(stockOrCandidateResult, candidateResult);
                context.stats.compareWallNanos.add(System.nanoTime() - compareWall);
                addCpu(context.stats.compareCpuNanos, compareCpu);
                context.stats.verifiedCalls.increment();
                if (mismatch == null) context.stats.matches.increment();
                else {
                    context.stats.mismatches.increment();
                    rememberMismatch(mismatch + " boxes=" + context.boxes + " fallback=" + context.fallback);
                }
                return stockOrCandidateResult; // verifier always returns stock
            }

            if (context.boxes == 0 || context.fallback) return stockOrCandidateResult;
            long wall = System.nanoTime();
            VoxelShape optimized = stockOrCandidateResult.optimize();
            context.stats.finalOptimizeWallNanos.add(System.nanoTime() - wall);
            return optimized;
        } finally {
            context.stats.callWallNanos.add(System.nanoTime() - context.callStartNanos);
        }
    }

    /** Called after StartupProfiler has already timestamped the real main menu. */
    public static void onMainMenu() {
        if (reported) return;
        reported = true;
        if (!ENABLED) return;
        reportResourceState();
        if (VERIFIER && checkVersionGate()) runSelfTests();
        report("real", REAL);
        if (VERIFIER) report("self_test", TEST);
        if (!FIRST_MISMATCHES.isEmpty()) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_BATCH_UNION_MISMATCHES count={} first={}",
                    FIRST_MISMATCHES.size(), FIRST_MISMATCHES);
        }
    }

    private static Stats stats() {
        return Boolean.TRUE.equals(SELF_TEST.get()) ? TEST : REAL;
    }

    private static synchronized boolean checkVersionGate() {
        if (versionChecked) return versionCompatible;
        versionChecked = true;
        try {
            createVersion = ModList.get().getModContainerById("create")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
            ponderVersion = ModList.get().getModContainerById("ponder")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
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
            long firstIntervals = Math.max(0L, first.getCoords(axis).size() - 1L);
            long secondIntervals = Math.max(0L, second.getCoords(axis).size() - 1L);
            long mergedIntervals = firstIntervals + secondIntervals;
            if (mergedIntervals == 0L) continue;
            if (product > Long.MAX_VALUE / mergedIntervals) return Long.MAX_VALUE;
            product *= mergedIntervals;
        }
        return product;
    }

    /** XOR geometry plus observable coordinate/decomposition checks. */
    private static String compare(VoxelShape stock, VoxelShape candidate) {
        if (stock.isEmpty() != candidate.isEmpty()) return "empty";
        if (Shapes.joinIsNotEmpty(stock, candidate, BooleanOp.NOT_SAME)) return "xor_geometry";
        if (!stock.isEmpty() && !sameAabb(stock.bounds(), candidate.bounds())) return "bounds";
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList a = stock.getCoords(axis);
            DoubleList b = candidate.getCoords(axis);
            if (a.size() != b.size()) return "coord_count_" + axis.getName();
            for (int i = 0; i < a.size(); i++) {
                if (!sameDouble(a.getDouble(i), b.getDouble(i))) return "coord_bits_" + axis.getName() + "_" + i;
            }
        }
        List<AABB> a = stock.toAabbs();
        List<AABB> b = candidate.toAabbs();
        if (a.size() != b.size()) return "ordered_box_count";
        for (int i = 0; i < a.size(); i++) if (!sameAabb(a.get(i), b.get(i))) return "ordered_box_bits_" + i;
        if (!stock.getClass().getName().equals(candidate.getClass().getName())) return "representation_class";
        return null;
    }

    private static boolean sameAabb(AABB a, AABB b) {
        return sameDouble(a.minX, b.minX) && sameDouble(a.minY, b.minY) && sameDouble(a.minZ, b.minZ)
                && sameDouble(a.maxX, b.maxX) && sameDouble(a.maxY, b.maxY) && sameDouble(a.maxZ, b.maxZ);
    }

    private static boolean sameDouble(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static long cpuNow() {
        if (!VERIFIER || !THREAD_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_BEAN.isThreadCpuTimeEnabled()) return -1L;
        return THREAD_BEAN.getCurrentThreadCpuTime();
    }

    private static void addCpu(LongAdder destination, long start) {
        if (start < 0L) return;
        long end = THREAD_BEAN.getCurrentThreadCpuTime();
        if (end >= start) destination.add(end - start);
    }

    private static synchronized void rememberMismatch(String mismatch) {
        if (FIRST_MISMATCHES.size() < 8) FIRST_MISMATCHES.add(mismatch);
    }

    private static void report(String scope, Stats s) {
        long calls = s.calls.sum();
        long pure = s.pureBatchCalls.sum();
        double coverage = calls == 0 ? 0.0D : pure * 100.0D / calls;
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_BATCH_UNION status={} scope={} create_version={} ponder_version={} calls={} boxes={} verified={} matches={} mismatches={} pure_batch_calls={} fallback_calls={} pure_coverage_pct={} batch_boxes={} fallback_boxes={} empty_sources={} zero_rotation_calls={} max_grid_cells={} max_grid_guard={} stock_fold_wall_ms={} stock_fold_cpu_ms={} candidate_fold_wall_ms={} candidate_fold_cpu_ms={} final_optimize_wall_ms={} final_optimize_cpu_ms={} compare_wall_ms={} compare_cpu_ms={} call_wall_ms={}",
                VERIFIER ? "verifier_complete" : "candidate_complete", scope, createVersion, ponderVersion,
                calls, s.boxes.sum(), s.verifiedCalls.sum(), s.matches.sum(), s.mismatches.sum(), pure,
                s.fallbackCalls.sum(), format(coverage), s.batchBoxes.sum(), s.fallbackBoxes.sum(),
                s.emptySourceCalls.sum(), s.zeroRotationCalls.sum(), s.maxGridCells.get(), MAX_INTERMEDIATE_CELLS,
                millis(s.stockFoldWallNanos.sum()), millis(s.stockFoldCpuNanos.sum()),
                millis(s.candidateFoldWallNanos.sum()), millis(s.candidateFoldCpuNanos.sum()),
                millis(s.finalOptimizeWallNanos.sum()), millis(s.finalOptimizeCpuNanos.sum()),
                millis(s.compareWallNanos.sum()), millis(s.compareCpuNanos.sum()), millis(s.callWallNanos.sum()));
    }

    private static void runSelfTests() {
        int cases = 0;
        int reflectionFailures = 0;
        int identityFailures = 0;
        SELF_TEST.set(Boolean.TRUE);
        try {
            Class<?> type = Class.forName("net.createmod.catnip.math.VoxelShaper");
            Method directional = type.getMethod("forDirectional", VoxelShape.class, Direction.class);
            Method horizontal = type.getMethod("forHorizontal", VoxelShape.class, Direction.class);
            Method axisFactory = type.getMethod("forAxis", VoxelShape.class, Direction.Axis.class);
            Method horizontalAxis = type.getMethod("forHorizontalAxis", VoxelShape.class, Direction.Axis.class);
            Method get = type.getMethod("get", Direction.class);
            Method vertical = type.getMethod("withVerticalShapes", VoxelShape.class);
            for (VoxelShape shape : edgeCaseShapes()) {
                for (Direction facing : Direction.values()) {
                    Object shaper = directional.invoke(null, shape, facing);
                    cases++;
                    if (get.invoke(shaper, facing) != shape) identityFailures++;
                }
                for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                    Object shaper = horizontal.invoke(null, shape, facing);
                    cases++;
                    if (get.invoke(shaper, facing) != shape) identityFailures++;
                }
                for (Direction.Axis axis : Direction.Axis.values()) {
                    Object shaper = axisFactory.invoke(null, shape, axis);
                    cases++;
                    Direction identity = Direction.get(Direction.AxisDirection.POSITIVE, axis);
                    if (get.invoke(shaper, identity) != shape) identityFailures++;
                    horizontalAxis.invoke(null, shape, axis);
                    cases++;
                }
                Object shaper = horizontal.invoke(null, shape, Direction.SOUTH);
                vertical.invoke(shaper, shape);
                cases++;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            reflectionFailures++;
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_SELF_TEST status=reflection_failure", exception);
        } finally {
            SELF_TEST.remove();
        }
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_SELF_TEST status=complete cases={} verified_calls={} matches={} mismatches={} fallback_calls={} identity_failures={} reflection_failures={}",
                cases, TEST.verifiedCalls.sum(), TEST.matches.sum(), TEST.mismatches.sum(), TEST.fallbackCalls.sum(),
                identityFailures, reflectionFailures);
    }

    private static List<VoxelShape> edgeCaseShapes() {
        List<VoxelShape> shapes = new ArrayList<>();
        shapes.add(Shapes.empty());
        shapes.add(Shapes.box(0.125D, 0.1875D, 0.25D, 0.875D, 0.8125D, 0.75D));
        shapes.add(Shapes.joinUnoptimized(Shapes.box(0, 0, 0, 0.75, 0.75, 0.75),
                Shapes.box(0.25, 0.25, 0.25, 1, 1, 1), BooleanOp.OR));
        shapes.add(Shapes.joinUnoptimized(Shapes.box(0, 0, 0, 0.5, 0.5, 1),
                Shapes.box(0.5, 0.25, 0, 1, 0.75, 1), BooleanOp.OR));
        shapes.add(Shapes.box(-0.25, -0.125, 0, 1.25, 1.125, 1.5));
        shapes.add(Shapes.box(-0.5, -0.25, -0.125, 0.5, 0.75, 0.875));
        shapes.add(Shapes.box(-5.0E-8D, 0.125D, 0.25D, 5.00001E-8D, 0.875D, 0.75D));
        return shapes;
    }

    private static void reportResourceState() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Path options = mc.gameDirectory.toPath().resolve("options.txt");
            Set<String> selectedZip = new LinkedHashSet<>();
            for (String line : Files.readAllLines(options)) {
                if (!line.startsWith("resourcePacks:")) continue;
                JsonElement parsed = JsonParser.parseString(line.substring("resourcePacks:".length()));
                if (!parsed.isJsonArray()) continue;
                JsonArray array = parsed.getAsJsonArray();
                for (JsonElement e : array) {
                    String id = e.getAsString();
                    if (id.startsWith("file/") && id.toLowerCase(Locale.ROOT).endsWith(".zip")) selectedZip.add(id);
                }
            }
            List<PackResources> packs = mc.getResourceManager().listPacks().toList();
            Set<String> ids = new LinkedHashSet<>();
            for (PackResources pack : packs) ids.add(pack.packId());
            Set<String> missing = new LinkedHashSet<>(selectedZip);
            missing.removeAll(ids);
            long managerZip = ids.stream().filter(id -> id.startsWith("file/") && id.toLowerCase(Locale.ROOT).endsWith(".zip")).count();
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_RESOURCES status=complete options_exists={} options_zip_packs={} manager_packs={} manager_zip_packs={} matched_zip_packs={} missing_zip_packs={} manager_empty={} manager_class={}",
                    Files.isRegularFile(options), selectedZip.size(), packs.size(), managerZip,
                    selectedZip.size() - missing.size(), missing.size(), packs.isEmpty(), mc.getResourceManager().getClass().getName());
            if (!missing.isEmpty()) LOGGER.warn("BOOTOPTIM_VOXELSHAPER_RESOURCES_MISSING ids={}", missing);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_RESOURCES status=failed", exception);
        }
    }

    private static String millis(long nanos) { return format(nanos / 1_000_000.0D); }
    private static String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }

    private static final class CallContext {
        final Stats stats;
        VoxelShape candidate;
        int boxes;
        boolean fallback;
        long callStartNanos;
        CallContext(Stats stats) { this.stats = stats; }
    }

    private static final class Stats {
        final LongAdder calls = new LongAdder();
        final LongAdder boxes = new LongAdder();
        final LongAdder verifiedCalls = new LongAdder();
        final LongAdder matches = new LongAdder();
        final LongAdder mismatches = new LongAdder();
        final LongAdder pureBatchCalls = new LongAdder();
        final LongAdder fallbackCalls = new LongAdder();
        final LongAdder batchBoxes = new LongAdder();
        final LongAdder fallbackBoxes = new LongAdder();
        final LongAdder emptySourceCalls = new LongAdder();
        final LongAdder zeroRotationCalls = new LongAdder();
        final AtomicLong maxGridCells = new AtomicLong();
        final LongAdder stockFoldWallNanos = new LongAdder();
        final LongAdder stockFoldCpuNanos = new LongAdder();
        final LongAdder candidateFoldWallNanos = new LongAdder();
        final LongAdder candidateFoldCpuNanos = new LongAdder();
        final LongAdder finalOptimizeWallNanos = new LongAdder();
        final LongAdder finalOptimizeCpuNanos = new LongAdder();
        final LongAdder compareWallNanos = new LongAdder();
        final LongAdder compareCpuNanos = new LongAdder();
        final LongAdder callWallNanos = new LongAdder();
    }
}
