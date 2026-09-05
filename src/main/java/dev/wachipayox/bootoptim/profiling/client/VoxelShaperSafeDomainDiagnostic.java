package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Diagnostic-only verifier for a deliberately narrow VoxelShaper delayed-optimize domain.
 * Natural Ponder calls always execute and return stock Shapes.or results. Candidate replay
 * happens only after the main-menu timestamp from captured stock-created rotated boxes.
 */
public final class VoxelShaperSafeDomainDiagnostic {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileVoxelShaperSafeDomain");
    private static final int MAX_BOXES_PER_CALL = 512;
    private static final int MAX_CAPTURED_CALLS = 16_384;
    private static final int MAX_CAPTURED_BOXES = 50_000;
    private static final int MAX_MISMATCH_DUMPS = 4;
    private static final int MAX_DUMP_BOXES = 64;
    private static final int MAX_DUMP_COORDS = 96;

    private static final ThreadLocal<Deque<CallContext>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final List<CallRecord> RECORDS = new ArrayList<>();
    private static final LongAdder NONZERO_CALLS = new LongAdder();
    private static final LongAdder ZERO_ROTATION_CALLS = new LongAdder();
    private static final LongAdder ZERO_ROTATION_IDENTITY_FAILURES = new LongAdder();
    private static final LongAdder CAPTURED_BOXES = new LongAdder();
    private static final LongAdder DROPPED_CALLS = new LongAdder();
    private static final LongAdder DROPPED_BOXES = new LongAdder();

    private static volatile boolean versionChecked;
    private static volatile boolean versionCompatible;
    private static volatile String createVersion = "unknown";
    private static volatile String ponderVersion = "unknown";
    private static volatile boolean reported;
    private static int retainedBoxCount;

    private VoxelShaperSafeDomainDiagnostic() {}

    public static void begin(VoxelShape source, Vec3 rotation) {
        if (!ENABLED || !checkVersionGate()) return;

        CallContext context = new CallContext(source, rotation);
        context.callWallStart = System.nanoTime();
        context.callCpuStart = cpuNow();
        context.zeroRotation = rotation.equals(Vec3.ZERO);
        CONTEXTS.get().push(context);
        if (context.zeroRotation) ZERO_ROTATION_CALLS.increment();
        else NONZERO_CALLS.increment();
    }

    /** Redirect target: the stock fold is timed in isolation and always returned. */
    public static VoxelShape fold(VoxelShape accumulator, VoxelShape rotatedBox) {
        Deque<CallContext> stack = CONTEXTS.get();
        CallContext context = stack.peek();
        if (context == null || context.zeroRotation) return Shapes.or(accumulator, rotatedBox);

        long stockCpuStart = cpuNow();
        long stockWallStart = System.nanoTime();
        VoxelShape stock = Shapes.or(accumulator, rotatedBox);
        context.stockFoldWallNanos += System.nanoTime() - stockWallStart;
        context.stockFoldCpuNanos += cpuDelta(stockCpuStart);

        long guardCpuStart = cpuNow();
        long guardWallStart = System.nanoTime();
        context.boxCount++;
        if (rotatedBox.getClass() != CubeVoxelShape.class) context.allExactCubeBoxes = false;
        if (context.rotatedBoxes.size() < MAX_BOXES_PER_CALL) {
            context.rotatedBoxes.add(rotatedBox);
        } else {
            context.localCaptureOverflow = true;
        }
        context.guardWallNanos += System.nanoTime() - guardWallStart;
        context.guardCpuNanos += cpuDelta(guardCpuStart);
        return stock;
    }

    /** RETURN hook. It records stock output only; it never substitutes a candidate. */
    public static void finish(VoxelShape stockResult) {
        if (!ENABLED) return;
        Deque<CallContext> stack = CONTEXTS.get();
        if (stack.isEmpty()) return;

        CallContext context = stack.pop();
        if (stack.isEmpty()) CONTEXTS.remove();

        context.callWallNanos = System.nanoTime() - context.callWallStart;
        context.callCpuNanos = cpuDelta(context.callCpuStart);
        if (context.zeroRotation) {
            if (stockResult != context.source) ZERO_ROTATION_IDENTITY_FAILURES.increment();
            return;
        }

        context.stockResult = stockResult;
        store(context);
    }

    /** Called after StartupProfiler has already timestamped the first title screen. */
    public static void onMainMenu() {
        if (!ENABLED || reported) return;
        reported = true;
        if (!checkVersionGate()) {
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN status=disabled create_version={} ponder_version={}",
                    createVersion, ponderVersion);
            return;
        }

        List<CallRecord> records;
        synchronized (RECORDS) {
            records = List.copyOf(RECORDS);
        }

        Aggregate aggregate = replay(records);
        AdversarialStats adversarial = runAdversarialTests();
        report(aggregate, adversarial, records.size());
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
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN status=disabled reason=version_gate_failure", exception);
        }
        return versionCompatible;
    }

    private static void store(CallContext context) {
        if (context.localCaptureOverflow) {
            DROPPED_CALLS.increment();
            DROPPED_BOXES.add(context.boxCount);
            return;
        }
        synchronized (RECORDS) {
            if (RECORDS.size() >= MAX_CAPTURED_CALLS
                    || retainedBoxCount + context.rotatedBoxes.size() > MAX_CAPTURED_BOXES) {
                DROPPED_CALLS.increment();
                DROPPED_BOXES.add(context.boxCount);
                return;
            }
            retainedBoxCount += context.rotatedBoxes.size();
            CAPTURED_BOXES.add(context.rotatedBoxes.size());
            RECORDS.add(new CallRecord(context));
        }
    }

    private static Aggregate replay(List<CallRecord> records) {
        Aggregate aggregate = new Aggregate();
        int mismatchDumpIndex = 0;
        for (int index = 0; index < records.size(); index++) {
            CallRecord record = records.get(index);
            Domain domain = domain(record.boxCount, record.allExactCubeBoxes);
            Bucket bucket = domain == Domain.REJECTED ? aggregate.rejected : aggregate.eligible;
            bucket.calls++;
            bucket.boxes += record.boxCount;
            bucket.stockFoldWallNanos += record.stockFoldWallNanos;
            bucket.stockFoldCpuNanos += record.stockFoldCpuNanos;
            bucket.guardWallNanos += record.guardWallNanos;
            bucket.guardCpuNanos += record.guardCpuNanos;
            bucket.callWallNanos += record.callWallNanos;
            bucket.callCpuNanos += record.callCpuNanos;
            if (domain == Domain.SMALL_COUNT) aggregate.smallCountCalls++;
            if (domain == Domain.EXACT_CUBE) aggregate.exactCubeCalls++;

            CandidateResult candidate = buildCandidate(record.rotatedBoxes, record.stockResult);
            bucket.candidateFoldWallNanos += candidate.foldWallNanos;
            bucket.candidateFoldCpuNanos += candidate.foldCpuNanos;
            bucket.finalOptimizeWallNanos += candidate.optimizeWallNanos;
            bucket.finalOptimizeCpuNanos += candidate.optimizeCpuNanos;
            bucket.compareWallNanos += candidate.compareWallNanos;
            bucket.compareCpuNanos += candidate.compareCpuNanos;

            if (record.boxCount == 0) {
                bucket.zeroBoxCalls++;
                if (candidate.shape == record.stockResult) bucket.zeroBoxIdentityMatches++;
            }

            if (candidate.mismatch == null) {
                bucket.matches++;
            } else {
                bucket.mismatches++;
                if (mismatchDumpIndex < MAX_MISMATCH_DUMPS) {
                    dumpMismatch(mismatchDumpIndex++, index, domain, record, candidate);
                }
            }
        }
        return aggregate;
    }

    private static CandidateResult buildCandidate(List<VoxelShape> boxes, VoxelShape stockResult) {
        if (boxes.isEmpty()) {
            long compareCpuStart = cpuNow();
            long compareWallStart = System.nanoTime();
            String mismatch = compare(stockResult, stockResult);
            return new CandidateResult(stockResult, 0L, 0L, 0L, 0L,
                    System.nanoTime() - compareWallStart, cpuDelta(compareCpuStart), mismatch);
        }

        VoxelShape candidate = Shapes.empty();
        long foldCpuStart = cpuNow();
        long foldWallStart = System.nanoTime();
        for (VoxelShape box : boxes) {
            candidate = Shapes.joinUnoptimized(candidate, box, BooleanOp.OR);
        }
        long foldWall = System.nanoTime() - foldWallStart;
        long foldCpu = cpuDelta(foldCpuStart);

        long optimizeCpuStart = cpuNow();
        long optimizeWallStart = System.nanoTime();
        candidate = candidate.optimize();
        long optimizeWall = System.nanoTime() - optimizeWallStart;
        long optimizeCpu = cpuDelta(optimizeCpuStart);

        long compareCpuStart = cpuNow();
        long compareWallStart = System.nanoTime();
        String mismatch = compare(stockResult, candidate);
        long compareWall = System.nanoTime() - compareWallStart;
        long compareCpu = cpuDelta(compareCpuStart);
        return new CandidateResult(candidate, foldWall, foldCpu, optimizeWall, optimizeCpu,
                compareWall, compareCpu, mismatch);
    }

    private static Domain domain(int boxCount, boolean allExactCubeBoxes) {
        if (boxCount <= 2) return Domain.SMALL_COUNT;
        return allExactCubeBoxes ? Domain.EXACT_CUBE : Domain.REJECTED;
    }

    private static String compare(VoxelShape stock, VoxelShape candidate) {
        if (stock.isEmpty() != candidate.isEmpty()) return "empty";
        if (Shapes.joinIsNotEmpty(stock, candidate, BooleanOp.NOT_SAME)) return "xor_geometry";
        if (!stock.isEmpty() && !sameAabb(stock.bounds(), candidate.bounds())) return "bounds";
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList a = stock.getCoords(axis);
            DoubleList b = candidate.getCoords(axis);
            if (a.size() != b.size()) return "coord_count_" + axis.getName();
            for (int i = 0; i < a.size(); i++) {
                if (!sameDouble(a.getDouble(i), b.getDouble(i))) {
                    return "coord_bits_" + axis.getName() + "_" + i;
                }
            }
        }
        List<AABB> a = stock.toAabbs();
        List<AABB> b = candidate.toAabbs();
        if (a.size() != b.size()) return "ordered_box_count";
        for (int i = 0; i < a.size(); i++) {
            if (!sameAabb(a.get(i), b.get(i))) return "ordered_box_bits_" + i;
        }
        if (stock.getClass() != candidate.getClass()) return "representation_class";
        return null;
    }

    private static boolean sameAabb(AABB a, AABB b) {
        return sameDouble(a.minX, b.minX) && sameDouble(a.minY, b.minY) && sameDouble(a.minZ, b.minZ)
                && sameDouble(a.maxX, b.maxX) && sameDouble(a.maxY, b.maxY) && sameDouble(a.maxZ, b.maxZ);
    }

    private static boolean sameDouble(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static void dumpMismatch(
            int dumpIndex,
            int callIndex,
            Domain domain,
            CallRecord record,
            CandidateResult candidate) {
        PrefixDivergence prefix = firstPrefixDivergence(record.rotatedBoxes);
        LOGGER.warn(
                "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_MISMATCH dump={} call={} domain={} boxes={} reason={} rotation={} first_divergent_prefix={} prefix_reason={} source={} rotated={} stock={} candidate={}",
                dumpIndex,
                callIndex,
                domain,
                record.boxCount,
                candidate.mismatch,
                describeVec(record.rotation),
                prefix.prefix,
                prefix.reason,
                describeAabbs(record.source.toAabbs()),
                describeRotated(record.rotatedBoxes),
                describeShape(record.stockResult),
                describeShape(candidate.shape));
        if (prefix.prefix > 0) {
            LOGGER.warn(
                    "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_PREFIX dump={} stock_prefix={} delayed_optimized_prefix={}",
                    dumpIndex,
                    describeShape(prefix.stock),
                    describeShape(prefix.candidate));
        }
    }

    /**
     * Replays only bounded failing calls after TTMM and asks at which prefix delayed.optimize()
     * first stops being strictly identical to the stock repeated Shapes.or fold.
     */
    private static PrefixDivergence firstPrefixDivergence(List<VoxelShape> boxes) {
        VoxelShape stock = Shapes.empty();
        VoxelShape delayed = Shapes.empty();
        for (int i = 0; i < boxes.size(); i++) {
            VoxelShape box = boxes.get(i);
            stock = Shapes.or(stock, box);
            delayed = Shapes.joinUnoptimized(delayed, box, BooleanOp.OR);
            VoxelShape optimized = delayed.optimize();
            String mismatch = compare(stock, optimized);
            if (mismatch != null) return new PrefixDivergence(i + 1, mismatch, stock, optimized);
        }
        return new PrefixDivergence(-1, "none", null, null);
    }

    private static String describeShape(VoxelShape shape) {
        StringBuilder builder = new StringBuilder();
        builder.append('{').append("class=").append(shape.getClass().getSimpleName());
        builder.append(",empty=").append(shape.isEmpty());
        for (Direction.Axis axis : Direction.Axis.values()) {
            builder.append(',').append(axis.getName()).append('=')
                    .append(describeCoords(shape.getCoords(axis)));
        }
        builder.append(",boxes=").append(describeAabbs(shape.toAabbs())).append('}');
        return builder.toString();
    }

    private static String describeCoords(DoubleList coords) {
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(coords.size(), MAX_DUMP_COORDS);
        for (int i = 0; i < limit; i++) {
            if (i != 0) builder.append(',');
            builder.append(bits(coords.getDouble(i)));
        }
        if (coords.size() > limit) builder.append(",...+").append(coords.size() - limit);
        return builder.append(']').toString();
    }

    private static String describeAabbs(List<AABB> boxes) {
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(boxes.size(), MAX_DUMP_BOXES);
        for (int i = 0; i < limit; i++) {
            if (i != 0) builder.append(',');
            AABB box = boxes.get(i);
            builder.append('(')
                    .append(bits(box.minX)).append(',').append(bits(box.minY)).append(',').append(bits(box.minZ))
                    .append("->")
                    .append(bits(box.maxX)).append(',').append(bits(box.maxY)).append(',').append(bits(box.maxZ))
                    .append(')');
        }
        if (boxes.size() > limit) builder.append(",...+").append(boxes.size() - limit);
        return builder.append(']').toString();
    }

    private static String describeRotated(List<VoxelShape> boxes) {
        List<AABB> bounds = new ArrayList<>(Math.min(boxes.size(), MAX_DUMP_BOXES));
        int limit = Math.min(boxes.size(), MAX_DUMP_BOXES);
        for (int i = 0; i < limit; i++) {
            VoxelShape shape = boxes.get(i);
            if (!shape.isEmpty()) bounds.add(shape.bounds());
        }
        return describeAabbs(bounds);
    }

    private static String describeVec(Vec3 vec) {
        return "(" + bits(vec.x) + ',' + bits(vec.y) + ',' + bits(vec.z) + ')';
    }

    private static String bits(double value) {
        return Double.toHexString(value) + "@" + Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static AdversarialStats runAdversarialTests() {
        AdversarialStats stats = new AdversarialStats();

        // The <=2-box theorem is independent of coordinate quality and unit-cube membership.
        VoxelShape weirdA = Shapes.box(-0.25D, 0.123456789D, 0.2D, 0.333333333D, 1.25D, 0.9000003D);
        VoxelShape weirdB = Shapes.box(0.500001D, -0.125D, 0.314159265D, 1.125D, 0.875D, 1.5D);
        verifyAdversarial(List.of(), true, stats);
        verifyAdversarial(List.of(weirdA), true, stats);
        verifyAdversarial(List.of(weirdA, weirdB), true, stats);

        List<VoxelShape> cubePool = new ArrayList<>();
        int[][] specs = new int[][] {
                {0,0,0,4,4,4}, {4,4,4,8,8,8}, {0,0,0,8,8,8},
                {1,1,1,7,7,7}, {0,2,2,8,6,6}, {2,0,2,6,8,6},
                {2,2,0,6,6,8}, {0,0,0,2,8,8}, {2,0,0,4,8,8},
                {4,0,0,6,8,8}, {6,0,0,8,8,8}, {0,0,0,8,2,8},
                {0,2,0,8,4,8}, {0,4,0,8,6,8}, {0,6,0,8,8,8},
                {0,0,0,8,8,2}, {0,0,2,8,8,4}, {0,0,4,8,8,6},
                {0,0,6,8,8,8}, {1,0,1,3,8,3}, {5,0,5,7,8,7},
                {0,1,5,8,3,7}, {3,3,3,5,5,5}, {1,3,1,7,5,7}
        };
        for (int[] s : specs) {
            VoxelShape shape = Shapes.box(
                    s[0] / 8.0D, s[1] / 8.0D, s[2] / 8.0D,
                    s[3] / 8.0D, s[4] / 8.0D, s[5] / 8.0D);
            if (shape.getClass() != CubeVoxelShape.class) stats.setupFailures++;
            cubePool.add(shape);
        }

        verifyAdversarial(List.of(cubePool.get(0), cubePool.get(1), cubePool.get(3)), true, stats);
        verifyAdversarial(List.of(cubePool.get(7), cubePool.get(8), cubePool.get(9), cubePool.get(10)), true, stats);
        verifyAdversarial(List.of(cubePool.get(4), cubePool.get(5), cubePool.get(6), cubePool.get(22)), true, stats);
        verifyAdversarial(List.of(cubePool.get(2), cubePool.get(3), cubePool.get(22), cubePool.get(23)), true, stats);

        long state = 0x4d595df4d0f33173L;
        for (int caseIndex = 0; caseIndex < 512; caseIndex++) {
            int length = 3 + caseIndex % 10;
            List<VoxelShape> sequence = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                int index = (int) Long.remainderUnsigned(state >>> 1, cubePool.size());
                sequence.add(cubePool.get(index));
            }
            verifyAdversarial(sequence, true, stats);
        }

        // Numeric values close to ordinary-looking fractions are deliberately rejected at 3+ boxes
        // unless stock Shapes.box itself has already canonicalized every one to CubeVoxelShape.
        VoxelShape nonCubeA = Shapes.box(0.0D, 0.0D, 0.0D, 0.500001D, 0.75D, 0.75D);
        VoxelShape nonCubeB = Shapes.box(0.125D, 0.111111111D, 0.125D, 0.875D, 0.888888889D, 0.875D);
        VoxelShape nonCubeC = Shapes.box(-0.000001D, 0.25D, 0.25D, 0.75D, 0.75D, 1.000001D);
        verifyAdversarial(List.of(nonCubeA, nonCubeB, nonCubeC), false, stats);
        verifyAdversarial(List.of(cubePool.get(3), nonCubeA, cubePool.get(22)), false, stats);
        return stats;
    }

    private static void verifyAdversarial(List<VoxelShape> boxes, boolean expectedEligible, AdversarialStats stats) {
        stats.cases++;
        boolean allCube = true;
        for (VoxelShape box : boxes) if (box.getClass() != CubeVoxelShape.class) allCube = false;
        Domain domain = domain(boxes.size(), allCube);
        boolean eligible = domain != Domain.REJECTED;
        if (eligible != expectedEligible) stats.guardExpectationFailures++;

        VoxelShape stock = Shapes.empty();
        VoxelShape delayed = Shapes.empty();
        for (VoxelShape box : boxes) {
            stock = Shapes.or(stock, box);
            delayed = Shapes.joinUnoptimized(delayed, box, BooleanOp.OR);
        }
        VoxelShape candidate = boxes.isEmpty() ? stock : delayed.optimize();
        String mismatch = compare(stock, candidate);
        if (eligible) {
            stats.eligibleCases++;
            if (mismatch == null) stats.eligibleMatches++;
            else stats.eligibleMismatches++;
        } else {
            stats.rejectedCases++;
            if (mismatch == null) stats.rejectedMatches++;
            else stats.rejectedMismatches++;
        }
    }

    private static void report(Aggregate a, AdversarialStats t, int capturedCalls) {
        long totalStockCpu = a.eligible.stockFoldCpuNanos + a.rejected.stockFoldCpuNanos;
        long totalStockWall = a.eligible.stockFoldWallNanos + a.rejected.stockFoldWallNanos;
        double eligibleCpuPct = totalStockCpu <= 0L ? 0.0D : a.eligible.stockFoldCpuNanos * 100.0D / totalStockCpu;
        double eligibleWallPct = totalStockWall <= 0L ? 0.0D : a.eligible.stockFoldWallNanos * 100.0D / totalStockWall;
        long totalGuardCpu = a.eligible.guardCpuNanos + a.rejected.guardCpuNanos;
        long totalGuardWall = a.eligible.guardWallNanos + a.rejected.guardWallNanos;
        long eligibleCandidateCpu = a.eligible.candidateFoldCpuNanos + a.eligible.finalOptimizeCpuNanos;
        long eligibleCandidateWall = a.eligible.candidateFoldWallNanos + a.eligible.finalOptimizeWallNanos;

        LOGGER.info(
                "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN status=complete create_version={} ponder_version={} natural_nonzero_calls={} zero_rotation_calls={} zero_rotation_identity_failures={} captured_calls={} captured_boxes={} dropped_calls={} dropped_boxes={} small_count_calls={} exact_cube_calls={} eligible_calls={} eligible_boxes={} rejected_calls={} rejected_boxes={} eligible_matches={} eligible_mismatches={} rejected_matches={} rejected_mismatches={} total_stock_fold_cpu_ms={} eligible_stock_fold_cpu_ms={} rejected_stock_fold_cpu_ms={} eligible_stock_cpu_pct={} total_stock_fold_wall_ms={} eligible_stock_fold_wall_ms={} rejected_stock_fold_wall_ms={} eligible_stock_wall_pct={} guard_cpu_ms={} guard_wall_ms={} eligible_candidate_fold_cpu_ms={} eligible_candidate_final_opt_cpu_ms={} eligible_candidate_total_cpu_ms={} eligible_candidate_fold_wall_ms={} eligible_candidate_final_opt_wall_ms={} eligible_candidate_total_wall_ms={} eligible_compare_cpu_ms={} eligible_compare_wall_ms={} eligible_call_cpu_ms={} rejected_call_cpu_ms={} capture_limits={}/{}/{} adversarial_cases={} adversarial_eligible={} adversarial_eligible_matches={} adversarial_eligible_mismatches={} adversarial_rejected={} adversarial_rejected_matches={} adversarial_rejected_mismatches={} adversarial_guard_failures={} adversarial_setup_failures={}",
                createVersion, ponderVersion,
                NONZERO_CALLS.sum(), ZERO_ROTATION_CALLS.sum(), ZERO_ROTATION_IDENTITY_FAILURES.sum(),
                capturedCalls, CAPTURED_BOXES.sum(), DROPPED_CALLS.sum(), DROPPED_BOXES.sum(),
                a.smallCountCalls, a.exactCubeCalls,
                a.eligible.calls, a.eligible.boxes, a.rejected.calls, a.rejected.boxes,
                a.eligible.matches, a.eligible.mismatches, a.rejected.matches, a.rejected.mismatches,
                millis(totalStockCpu), millis(a.eligible.stockFoldCpuNanos), millis(a.rejected.stockFoldCpuNanos), format(eligibleCpuPct),
                millis(totalStockWall), millis(a.eligible.stockFoldWallNanos), millis(a.rejected.stockFoldWallNanos), format(eligibleWallPct),
                millis(totalGuardCpu), millis(totalGuardWall),
                millis(a.eligible.candidateFoldCpuNanos), millis(a.eligible.finalOptimizeCpuNanos), millis(eligibleCandidateCpu),
                millis(a.eligible.candidateFoldWallNanos), millis(a.eligible.finalOptimizeWallNanos), millis(eligibleCandidateWall),
                millis(a.eligible.compareCpuNanos), millis(a.eligible.compareWallNanos),
                millis(a.eligible.callCpuNanos), millis(a.rejected.callCpuNanos),
                MAX_BOXES_PER_CALL, MAX_CAPTURED_CALLS, MAX_CAPTURED_BOXES,
                t.cases, t.eligibleCases, t.eligibleMatches, t.eligibleMismatches,
                t.rejectedCases, t.rejectedMatches, t.rejectedMismatches,
                t.guardExpectationFailures, t.setupFailures);
    }

    private static long cpuNow() {
        if (!THREAD_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_BEAN.isThreadCpuTimeEnabled()) return -1L;
        return THREAD_BEAN.getCurrentThreadCpuTime();
    }

    private static long cpuDelta(long start) {
        if (start < 0L) return 0L;
        long end = THREAD_BEAN.getCurrentThreadCpuTime();
        return end >= start ? end - start : 0L;
    }

    private static String millis(long nanos) {
        return format(nanos / 1_000_000.0D);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private enum Domain {
        SMALL_COUNT,
        EXACT_CUBE,
        REJECTED
    }

    private static final class CallContext {
        private final VoxelShape source;
        private final Vec3 rotation;
        private final List<VoxelShape> rotatedBoxes = new ArrayList<>();
        private boolean zeroRotation;
        private boolean allExactCubeBoxes = true;
        private boolean localCaptureOverflow;
        private int boxCount;
        private long callWallStart;
        private long callCpuStart;
        private long callWallNanos;
        private long callCpuNanos;
        private long stockFoldWallNanos;
        private long stockFoldCpuNanos;
        private long guardWallNanos;
        private long guardCpuNanos;
        private VoxelShape stockResult;

        private CallContext(VoxelShape source, Vec3 rotation) {
            this.source = source;
            this.rotation = rotation;
        }
    }

    private static final class CallRecord {
        private final VoxelShape source;
        private final Vec3 rotation;
        private final List<VoxelShape> rotatedBoxes;
        private final VoxelShape stockResult;
        private final boolean allExactCubeBoxes;
        private final int boxCount;
        private final long stockFoldWallNanos;
        private final long stockFoldCpuNanos;
        private final long guardWallNanos;
        private final long guardCpuNanos;
        private final long callWallNanos;
        private final long callCpuNanos;

        private CallRecord(CallContext context) {
            this.source = context.source;
            this.rotation = context.rotation;
            this.rotatedBoxes = List.copyOf(context.rotatedBoxes);
            this.stockResult = context.stockResult;
            this.allExactCubeBoxes = context.allExactCubeBoxes;
            this.boxCount = context.boxCount;
            this.stockFoldWallNanos = context.stockFoldWallNanos;
            this.stockFoldCpuNanos = context.stockFoldCpuNanos;
            this.guardWallNanos = context.guardWallNanos;
            this.guardCpuNanos = context.guardCpuNanos;
            this.callWallNanos = context.callWallNanos;
            this.callCpuNanos = context.callCpuNanos;
        }
    }

    private static final class CandidateResult {
        private final VoxelShape shape;
        private final long foldWallNanos;
        private final long foldCpuNanos;
        private final long optimizeWallNanos;
        private final long optimizeCpuNanos;
        private final long compareWallNanos;
        private final long compareCpuNanos;
        private final String mismatch;

        private CandidateResult(
                VoxelShape shape,
                long foldWallNanos,
                long foldCpuNanos,
                long optimizeWallNanos,
                long optimizeCpuNanos,
                long compareWallNanos,
                long compareCpuNanos,
                String mismatch) {
            this.shape = shape;
            this.foldWallNanos = foldWallNanos;
            this.foldCpuNanos = foldCpuNanos;
            this.optimizeWallNanos = optimizeWallNanos;
            this.optimizeCpuNanos = optimizeCpuNanos;
            this.compareWallNanos = compareWallNanos;
            this.compareCpuNanos = compareCpuNanos;
            this.mismatch = mismatch;
        }
    }

    private static final class Bucket {
        private long calls;
        private long boxes;
        private long matches;
        private long mismatches;
        private long zeroBoxCalls;
        private long zeroBoxIdentityMatches;
        private long stockFoldWallNanos;
        private long stockFoldCpuNanos;
        private long guardWallNanos;
        private long guardCpuNanos;
        private long callWallNanos;
        private long callCpuNanos;
        private long candidateFoldWallNanos;
        private long candidateFoldCpuNanos;
        private long finalOptimizeWallNanos;
        private long finalOptimizeCpuNanos;
        private long compareWallNanos;
        private long compareCpuNanos;
    }

    private static final class Aggregate {
        private final Bucket eligible = new Bucket();
        private final Bucket rejected = new Bucket();
        private long smallCountCalls;
        private long exactCubeCalls;
    }

    private static final class AdversarialStats {
        private long cases;
        private long eligibleCases;
        private long eligibleMatches;
        private long eligibleMismatches;
        private long rejectedCases;
        private long rejectedMatches;
        private long rejectedMismatches;
        private long guardExpectationFailures;
        private long setupFailures;
    }

    private static final class PrefixDivergence {
        private final int prefix;
        private final String reason;
        private final VoxelShape stock;
        private final VoxelShape candidate;

        private PrefixDivergence(int prefix, String reason, VoxelShape stock, VoxelShape candidate) {
            this.prefix = prefix;
            this.reason = reason;
            this.stock = stock;
            this.candidate = candidate;
        }
    }
}
