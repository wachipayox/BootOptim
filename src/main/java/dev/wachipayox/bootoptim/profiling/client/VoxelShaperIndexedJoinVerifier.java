package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.BootOptimVoxelShapeIndexedJoin;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/** Diagnostic-only exact verifier/benchmark for the VoxelShaper accumulator OR single-box path. */
public final class VoxelShaperIndexedJoinVerifier {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.voxelShaperIndexedJoinVerifier");
    private static final LongAdder FOLDS = new LongAdder();
    private static final LongAdder MATCHES = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder STOCK_WALL = new LongAdder();
    private static final LongAdder STOCK_CPU = new LongAdder();
    private static final LongAdder CANDIDATE_WALL = new LongAdder();
    private static final LongAdder CANDIDATE_CPU = new LongAdder();
    private static final LongAdder COMPARE_WALL = new LongAdder();
    private static final LongAdder COMPARE_CPU = new LongAdder();
    private static volatile String firstFailure = "none";
    private static volatile String firstMismatch = "none";
    private static volatile boolean reported;
    private static volatile boolean versionChecked;
    private static volatile boolean versionCompatible;
    private static volatile String createVersion = "unknown";
    private static volatile String ponderVersion = "unknown";

    private VoxelShaperIndexedJoinVerifier() {}

    public static VoxelShape fold(VoxelShape accumulator, VoxelShape rotatedBox) {
        if (!ENABLED || !checkVersionGate()) {
            return Shapes.or(accumulator, rotatedBox);
        }
        FOLDS.increment();

        long stockCpu = cpuNow();
        long stockWall = System.nanoTime();
        VoxelShape stock = Shapes.or(accumulator, rotatedBox);
        STOCK_WALL.add(System.nanoTime() - stockWall);
        addCpu(STOCK_CPU, stockCpu);

        VoxelShape candidate;
        try {
            long candidateCpu = cpuNow();
            long candidateWall = System.nanoTime();
            candidate = BootOptimVoxelShapeIndexedJoin.or(accumulator, rotatedBox);
            CANDIDATE_WALL.add(System.nanoTime() - candidateWall);
            addCpu(CANDIDATE_CPU, candidateCpu);
        } catch (Throwable throwable) {
            FAILURES.increment();
            rememberFailure(throwable);
            return stock;
        }

        long compareCpu = cpuNow();
        long compareWall = System.nanoTime();
        String mismatch = compare(stock, candidate);
        COMPARE_WALL.add(System.nanoTime() - compareWall);
        addCpu(COMPARE_CPU, compareCpu);
        if (mismatch == null) {
            MATCHES.increment();
        } else {
            MISMATCHES.increment();
            rememberMismatch(mismatch);
        }
        return stock;
    }

    public static void onMainMenu() {
        if (!ENABLED || reported) return;
        reported = true;
        SelfTestResult self = runSelfTests();
        long stockCpu = STOCK_CPU.sum();
        long candidateCpu = CANDIDATE_CPU.sum();
        double cpuRatio = stockCpu == 0 ? 0.0 : candidateCpu * 100.0 / stockCpu;
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_INDEXED_JOIN status=complete create_version={} ponder_version={} folds={} matches={} mismatches={} failures={} stock_wall_ms={} stock_cpu_ms={} candidate_wall_ms={} candidate_cpu_ms={} candidate_cpu_pct_of_stock={} compare_wall_ms={} compare_cpu_ms={} self_cases={} self_matches={} self_mismatches={} first_mismatch={} first_failure={} stock_always_returned=true",
                createVersion, ponderVersion, FOLDS.sum(), MATCHES.sum(), MISMATCHES.sum(), FAILURES.sum(),
                millis(STOCK_WALL.sum()), millis(stockCpu), millis(CANDIDATE_WALL.sum()), millis(candidateCpu), format(cpuRatio),
                millis(COMPARE_WALL.sum()), millis(COMPARE_CPU.sum()), self.cases, self.matches, self.mismatches,
                firstMismatch, firstFailure);
    }

    private static SelfTestResult runSelfTests() {
        List<List<VoxelShape>> streams = List.of(
                List.of(Shapes.box(0, 0, 0, 1, 0.25, 1), Shapes.box(0.0625, 0.25, 0.1875, 0.9375, 0.875, 0.8125), Shapes.box(0, 0.125, 0, 1, 0.25, 0.1875)),
                List.of(Shapes.box(-0.25, -0.125, 0, 0.75, 0.75, 1.25), Shapes.box(0.3333125, 0.2, 0.1, 1.125, 0.9000001, 1.5), Shapes.box(-5.0E-8, 0.125, 0.25, 5.00001E-8, 0.875, 0.75)),
                List.of(Shapes.box(0, 0, 0, 0.5, 0.5, 1), Shapes.box(0.5, 0.25, 0, 1, 0.75, 1), Shapes.box(0.25, 0, 0.25, 0.75, 1, 0.75), Shapes.box(0, 0.75, 0, 1, 1, 1))
        );
        int cases = 0;
        int matches = 0;
        int mismatches = 0;
        for (List<VoxelShape> stream : streams) {
            VoxelShape stock = Shapes.empty();
            VoxelShape candidate = Shapes.empty();
            for (VoxelShape box : stream) {
                stock = Shapes.or(stock, box);
                candidate = BootOptimVoxelShapeIndexedJoin.or(candidate, box);
                cases++;
                if (compare(stock, candidate) == null) matches++; else mismatches++;
            }
        }
        return new SelfTestResult(cases, matches, mismatches);
    }

    private static String compare(VoxelShape stock, VoxelShape candidate) {
        if (stock.isEmpty() != candidate.isEmpty()) return "empty";
        if (Shapes.joinIsNotEmpty(stock, candidate, BooleanOp.NOT_SAME)) return "xor_geometry";
        if (!stock.getClass().getName().equals(candidate.getClass().getName())) return "class";
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList a = stock.getCoords(axis);
            DoubleList b = candidate.getCoords(axis);
            if (a.size() != b.size()) return "coord_count_" + axis.getName();
            for (int i = 0; i < a.size(); i++) {
                if (Double.doubleToLongBits(a.getDouble(i)) != Double.doubleToLongBits(b.getDouble(i))) {
                    return "coord_bits_" + axis.getName() + "_" + i;
                }
            }
        }
        List<AABB> a = stock.toAabbs();
        List<AABB> b = candidate.toAabbs();
        if (a.size() != b.size()) return "box_count";
        for (int i = 0; i < a.size(); i++) {
            if (!sameAabb(a.get(i), b.get(i))) return "box_bits_" + i;
        }
        return null;
    }

    private static boolean sameAabb(AABB a, AABB b) {
        return bits(a.minX) == bits(b.minX) && bits(a.minY) == bits(b.minY) && bits(a.minZ) == bits(b.minZ)
                && bits(a.maxX) == bits(b.maxX) && bits(a.maxY) == bits(b.maxY) && bits(a.maxZ) == bits(b.maxZ);
    }

    private static long bits(double value) { return Double.doubleToLongBits(value); }

    private static synchronized boolean checkVersionGate() {
        if (versionChecked) return versionCompatible;
        versionChecked = true;
        try {
            createVersion = ModList.get().getModContainerById("create").map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
            ponderVersion = ModList.get().getModContainerById("ponder").map(c -> c.getModInfo().getVersion().toString()).orElse("missing");
            versionCompatible = "6.0.10".equals(createVersion) && ("1.0.82".equals(ponderVersion) || ponderVersion.startsWith("1.0.82+"));
        } catch (Throwable throwable) {
            versionCompatible = false;
            rememberFailure(throwable);
        }
        return versionCompatible;
    }

    private static long cpuNow() {
        return THREAD_BEAN.isCurrentThreadCpuTimeSupported() && THREAD_BEAN.isThreadCpuTimeEnabled()
                ? THREAD_BEAN.getCurrentThreadCpuTime() : -1L;
    }

    private static void addCpu(LongAdder adder, long start) {
        if (start < 0) return;
        long end = THREAD_BEAN.getCurrentThreadCpuTime();
        if (end >= start) adder.add(end - start);
    }

    private static synchronized void rememberFailure(Throwable throwable) {
        if ("none".equals(firstFailure)) firstFailure = throwable.getClass().getName() + ":" + String.valueOf(throwable.getMessage());
    }

    private static synchronized void rememberMismatch(String mismatch) {
        if ("none".equals(firstMismatch)) firstMismatch = mismatch;
    }

    private static String millis(long nanos) { return format(nanos / 1_000_000.0); }
    private static String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private record SelfTestResult(int cases, int matches, int mismatches) {}
}
