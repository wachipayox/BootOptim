package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * Evidence-only follow-up for the already-failed safe-domain diagnostic.
 * It changes no guard and no startup output. It only replays captured references
 * after TTMM to retain concrete eligible counterexamples that the first bounded
 * generic mismatch logger could miss.
 */
public final class VoxelShaperSafeDomainEvidenceDump {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileVoxelShaperSafeDomain");
    private static final int MAX_NATURAL_DUMPS = 2;
    private static final int MAX_BOXES = 64;
    private static final int MAX_COORDS = 96;
    private static boolean reported;

    private VoxelShaperSafeDomainEvidenceDump() {}

    public static void onMainMenu() {
        if (!ENABLED || reported) return;
        reported = true;
        dumpNaturalEligibleMismatches();
        dumpAdversarialEligibleMismatch();
    }

    private static void dumpNaturalEligibleMismatches() {
        try {
            Field recordsField = VoxelShaperSafeDomainDiagnostic.class.getDeclaredField("RECORDS");
            recordsField.setAccessible(true);
            List<?> records = (List<?>) recordsField.get(null);
            int dumped = 0;
            for (int callIndex = 0; callIndex < records.size() && dumped < MAX_NATURAL_DUMPS; callIndex++) {
                Object record = records.get(callIndex);
                Object domain = field(record, "domain");
                if (!"EPSILON_STABLE".equals(String.valueOf(domain))) continue;

                @SuppressWarnings("unchecked")
                List<VoxelShape> boxes = (List<VoxelShape>) field(record, "rotatedBoxes");
                VoxelShape stock = (VoxelShape) field(record, "stockResult");
                VoxelShape candidate = delayed(boxes);
                String mismatch = compare(stock, candidate);
                if (mismatch == null) continue;

                VoxelShape source = (VoxelShape) field(record, "source");
                Vec3 rotation = (Vec3) field(record, "rotation");
                Object guardFailure = field(record, "guardFailure");
                int boxCount = (Integer) field(record, "boxCount");
                Prefix prefix = firstPrefixDivergence(boxes);

                LOGGER.warn(
                        "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ELIGIBLE_MISMATCH dump={} call={} boxes={} reason={} guard_failure={} rotation={} first_divergent_prefix={} prefix_reason={} source={} rotated={} stock={} candidate={}",
                        dumped,
                        callIndex,
                        boxCount,
                        mismatch,
                        guardFailure == null ? "none" : guardFailure,
                        vec(rotation),
                        prefix.index,
                        prefix.reason,
                        aabbs(source.toAabbs()),
                        rotated(boxes),
                        shape(stock),
                        shape(candidate));
                if (prefix.index > 0) {
                    LOGGER.warn(
                            "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ELIGIBLE_PREFIX dump={} stock_prefix={} delayed_optimized_prefix={}",
                            dumped,
                            shape(prefix.stock),
                            shape(prefix.candidate));
                }
                dumped++;
            }
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ELIGIBLE_EVIDENCE natural_dumps={}", dumped);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ELIGIBLE_EVIDENCE status=failed", exception);
        }
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void dumpAdversarialEligibleMismatch() {
        int caseIndex = 0;

        VoxelShape weirdA = Shapes.box(-0.25D, 0.123456789D, 0.2D, 0.333333333D, 1.25D, 0.9000003D);
        VoxelShape weirdB = Shapes.box(0.500001D, -0.125D, 0.314159265D, 1.125D, 0.875D, 1.5D);
        if (dumpAdversarialIfMismatch(caseIndex++, List.of())) return;
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(weirdA))) return;
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(weirdA, weirdB))) return;

        List<VoxelShape> pool = new ArrayList<>();
        int[][] specs = new int[][] {
                {0,0,0,8,8,8}, {8,8,8,16,16,16}, {1,1,1,15,15,15},
                {0,4,4,16,12,12}, {4,0,4,12,16,12}, {4,4,0,12,12,16},
                {0,0,0,4,16,16}, {4,0,0,8,16,16}, {8,0,0,12,16,16},
                {12,0,0,16,16,16}, {0,0,0,16,4,16}, {0,4,0,16,8,16},
                {0,8,0,16,12,16}, {0,12,0,16,16,16}, {0,0,0,16,16,4},
                {0,0,4,16,16,8}, {0,0,8,16,16,12}, {0,0,12,16,16,16},
                {2,0,2,6,16,6}, {10,0,10,14,16,14}, {0,2,10,16,6,14},
                {6,6,6,10,10,10}, {2,6,2,14,10,14}, {1,3,5,9,11,15}
        };
        for (int[] s : specs) {
            pool.add(Shapes.box(
                    s[0] / 16.0D, s[1] / 16.0D, s[2] / 16.0D,
                    s[3] / 16.0D, s[4] / 16.0D, s[5] / 16.0D));
        }

        if (dumpAdversarialIfMismatch(caseIndex++, List.of(pool.get(0), pool.get(1), pool.get(2)))) return;
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(pool.get(6), pool.get(7), pool.get(8), pool.get(9)))) return;
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(pool.get(3), pool.get(4), pool.get(5), pool.get(21)))) return;

        long state = 0x4d595df4d0f33173L;
        for (int randomCase = 0; randomCase < 512; randomCase++) {
            int length = 3 + randomCase % 10;
            List<VoxelShape> sequence = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                int index = (int) Long.remainderUnsigned(state >>> 1, pool.size());
                sequence.add(pool.get(index));
            }
            if (dumpAdversarialIfMismatch(caseIndex++, sequence)) return;
        }

        VoxelShape stableA = Shapes.box(0.101D, 0.203D, 0.307D, 0.401D, 0.503D, 0.607D);
        VoxelShape stableB = Shapes.box(0.151D, 0.253D, 0.357D, 0.451D, 0.553D, 0.657D);
        VoxelShape stableC = Shapes.box(0.701D, 0.103D, 0.209D, 0.901D, 0.803D, 0.809D);
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(stableA, stableB, stableC))) return;

        VoxelShape outsideA = Shapes.box(-0.4D, -0.2D, 0.21D, -0.1D, 0.3D, 0.61D);
        VoxelShape outsideB = Shapes.box(1.2D, 0.17D, 0.29D, 1.6D, 0.47D, 0.69D);
        VoxelShape outsideC = Shapes.box(0.31D, 1.3D, -0.5D, 0.71D, 1.7D, -0.15D);
        if (dumpAdversarialIfMismatch(caseIndex++, List.of(outsideA, outsideB, outsideC))) return;

        LOGGER.info("BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ADVERSARIAL_ELIGIBLE_EVIDENCE status=no_mismatch_reproduced cases={}", caseIndex);
    }

    private static boolean dumpAdversarialIfMismatch(int caseIndex, List<VoxelShape> boxes) {
        VoxelShape stock = stock(boxes);
        VoxelShape candidate = delayed(boxes);
        String mismatch = compare(stock, candidate);
        if (mismatch == null) return false;
        Prefix prefix = firstPrefixDivergence(boxes);
        LOGGER.warn(
                "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ADVERSARIAL_ELIGIBLE_MISMATCH case={} boxes={} reason={} first_divergent_prefix={} prefix_reason={} input={} stock={} candidate={}",
                caseIndex,
                boxes.size(),
                mismatch,
                prefix.index,
                prefix.reason,
                rotated(boxes),
                shape(stock),
                shape(candidate));
        if (prefix.index > 0) {
            LOGGER.warn(
                    "BOOTOPTIM_VOXELSHAPER_SAFE_DOMAIN_ADVERSARIAL_ELIGIBLE_PREFIX case={} stock_prefix={} delayed_optimized_prefix={}",
                    caseIndex,
                    shape(prefix.stock),
                    shape(prefix.candidate));
        }
        return true;
    }

    private static VoxelShape stock(List<VoxelShape> boxes) {
        VoxelShape result = Shapes.empty();
        for (VoxelShape box : boxes) result = Shapes.or(result, box);
        return result;
    }

    private static VoxelShape delayed(List<VoxelShape> boxes) {
        if (boxes.isEmpty()) return Shapes.empty();
        VoxelShape result = Shapes.empty();
        for (VoxelShape box : boxes) result = Shapes.joinUnoptimized(result, box, BooleanOp.OR);
        return result.optimize();
    }

    private static Prefix firstPrefixDivergence(List<VoxelShape> boxes) {
        VoxelShape stock = Shapes.empty();
        VoxelShape delayed = Shapes.empty();
        for (int i = 0; i < boxes.size(); i++) {
            VoxelShape box = boxes.get(i);
            stock = Shapes.or(stock, box);
            delayed = Shapes.joinUnoptimized(delayed, box, BooleanOp.OR);
            VoxelShape candidate = delayed.optimize();
            String mismatch = compare(stock, candidate);
            if (mismatch != null) return new Prefix(i + 1, mismatch, stock, candidate);
        }
        return new Prefix(-1, "none", null, null);
    }

    private static String compare(VoxelShape stock, VoxelShape candidate) {
        if (stock.isEmpty() != candidate.isEmpty()) return "empty";
        if (Shapes.joinIsNotEmpty(stock, candidate, BooleanOp.NOT_SAME)) return "xor_geometry";
        if (!stock.isEmpty() && !same(stock.bounds(), candidate.bounds())) return "bounds";
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList a = stock.getCoords(axis);
            DoubleList b = candidate.getCoords(axis);
            if (a.size() != b.size()) return "coord_count_" + axis.getName();
            for (int i = 0; i < a.size(); i++) {
                if (bits(a.getDouble(i)) != bits(b.getDouble(i))) return "coord_bits_" + axis.getName() + '_' + i;
            }
        }
        List<AABB> a = stock.toAabbs();
        List<AABB> b = candidate.toAabbs();
        if (a.size() != b.size()) return "ordered_box_count";
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) return "ordered_box_bits_" + i;
        }
        if (stock.getClass() != candidate.getClass()) return "representation_class";
        return null;
    }

    private static boolean same(AABB a, AABB b) {
        return bits(a.minX) == bits(b.minX) && bits(a.minY) == bits(b.minY) && bits(a.minZ) == bits(b.minZ)
                && bits(a.maxX) == bits(b.maxX) && bits(a.maxY) == bits(b.maxY) && bits(a.maxZ) == bits(b.maxZ);
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static String vec(Vec3 value) {
        return '(' + number(value.x) + ',' + number(value.y) + ',' + number(value.z) + ')';
    }

    private static String rotated(List<VoxelShape> boxes) {
        List<AABB> result = new ArrayList<>();
        int limit = Math.min(boxes.size(), MAX_BOXES);
        for (int i = 0; i < limit; i++) {
            VoxelShape box = boxes.get(i);
            if (!box.isEmpty()) result.add(box.bounds());
        }
        return aabbs(result);
    }

    private static String shape(VoxelShape shape) {
        StringBuilder b = new StringBuilder("{class=").append(shape.getClass().getSimpleName());
        for (Direction.Axis axis : Direction.Axis.values()) {
            b.append(',').append(axis.getName()).append('=').append(coords(shape.getCoords(axis)));
        }
        return b.append(",boxes=").append(aabbs(shape.toAabbs())).append('}').toString();
    }

    private static String coords(DoubleList values) {
        StringBuilder b = new StringBuilder("[");
        int limit = Math.min(values.size(), MAX_COORDS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) b.append(',');
            b.append(number(values.getDouble(i)));
        }
        if (values.size() > limit) b.append(",...+").append(values.size() - limit);
        return b.append(']').toString();
    }

    private static String aabbs(List<AABB> values) {
        StringBuilder b = new StringBuilder("[");
        int limit = Math.min(values.size(), MAX_BOXES);
        for (int i = 0; i < limit; i++) {
            if (i > 0) b.append(',');
            AABB a = values.get(i);
            b.append('(').append(number(a.minX)).append(',').append(number(a.minY)).append(',').append(number(a.minZ))
                    .append("->").append(number(a.maxX)).append(',').append(number(a.maxY)).append(',').append(number(a.maxZ)).append(')');
        }
        if (values.size() > limit) b.append(",...+").append(values.size() - limit);
        return b.append(']').toString();
    }

    private static String number(double value) {
        return Double.toHexString(value) + '@' + Long.toHexString(bits(value));
    }

    private record Prefix(int index, String reason, VoxelShape stock, VoxelShape candidate) {}
}
