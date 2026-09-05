package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.mixin.client.accessor.ArrayVoxelShapeInvoker;
import dev.wachipayox.bootoptim.mixin.client.accessor.CubeVoxelShapeInvoker;
import dev.wachipayox.bootoptim.mixin.client.accessor.VoxelShapeAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Diagnostic-only exact consumer specialization for stock OR merger semantics. */
final class VoxelShapeIndexedJoinCandidate {
    private static final double EPSILON = 1.0E-7D;

    private VoxelShapeIndexedJoinCandidate() {}

    static VoxelShape or(VoxelShape first, VoxelShape second) {
        if (first == second) return first.optimize();
        if (first.isEmpty()) return second.optimize();
        if (second.isEmpty()) return first.optimize();

        MergePlan x = createPlan(1, first.getCoords(Direction.Axis.X), second.getCoords(Direction.Axis.X));
        MergePlan y = createPlan(x.intervals(), first.getCoords(Direction.Axis.Y), second.getCoords(Direction.Axis.Y));
        MergePlan z = createPlan(x.intervals() * y.intervals(), first.getCoords(Direction.Axis.Z), second.getCoords(Direction.Axis.Z));

        BitSetDiscreteVoxelShape merged = new BitSetDiscreteVoxelShape(x.intervals(), y.intervals(), z.intervals());
        DiscreteVoxelShape firstShape = ((VoxelShapeAccessor) first).bootoptim$getShape();
        DiscreteVoxelShape secondShape = ((VoxelShapeAccessor) second).bootoptim$getShape();

        for (int xi = 0; xi < x.intervals(); xi++) {
            int fx = x.first[xi];
            int sx = x.second[xi];
            for (int yi = 0; yi < y.intervals(); yi++) {
                int fy = y.first[yi];
                int sy = y.second[yi];
                for (int zi = 0; zi < z.intervals(); zi++) {
                    if (firstShape.isFullWide(fx, fy, z.first[zi])
                            || secondShape.isFullWide(sx, sy, z.second[zi])) {
                        merged.fill(xi, yi, zi);
                    }
                }
            }
        }

        VoxelShape unoptimized;
        if (x.discreteCube && y.discreteCube && z.discreteCube) {
            unoptimized = CubeVoxelShapeInvoker.bootoptim$create(merged);
        } else {
            unoptimized = ArrayVoxelShapeInvoker.bootoptim$create(merged, x.coords, y.coords, z.coords);
        }
        return unoptimized.optimize();
    }

    /** Mirrors Shapes.createIndexMerger + Lithium's IndirectMerger replacement. */
    private static MergePlan createPlan(int size, DoubleList a, DoubleList b) {
        int ai = a.size() - 1;
        int bi = b.size() - 1;
        if (a instanceof CubePointRange && b instanceof CubePointRange) {
            long lcm = lcm(ai, bi);
            if ((long) size * lcm <= 256L) return discrete(ai, bi, (int) lcm);
        }
        if (a.getDouble(ai) < b.getDouble(0) - EPSILON
                || b.getDouble(bi) < a.getDouble(0) - EPSILON) {
            throw UnsupportedMergeException.INSTANCE;
        }
        if (ai == bi && Objects.equals(a, b)) return identical(a);
        return lithiumIndirect(a, b);
    }

    private static MergePlan discrete(int ai, int bi, int intervals) {
        int gcd = gcd(ai, bi);
        int firstDiv = ai / gcd;
        int secondDiv = bi / gcd;
        int[] first = new int[intervals];
        int[] second = new int[intervals];
        for (int j = 0; j < intervals; j++) {
            first[j] = j / secondDiv;
            second[j] = j / firstDiv;
        }
        return new MergePlan(new CubePointRange(intervals), first, second, true);
    }

    private static MergePlan identical(DoubleList coords) {
        int intervals = coords.size() - 1;
        int[] first = new int[intervals];
        int[] second = new int[intervals];
        for (int i = 0; i < intervals; i++) first[i] = second[i] = i;
        return new MergePlan(coords, first, second, false);
    }

    /** Exact copy of LithiumDoublePairList.merge for OR's true,true flags. */
    private static MergePlan lithiumIndirect(DoubleList a, DoubleList b) {
        int aSize = a.size();
        int bSize = b.size();
        double[] merged = new double[aSize + bSize];
        int[] first = new int[merged.length];
        int[] second = new int[merged.length];
        int aIdx = 0;
        int bIdx = 0;
        double prev = 0.0D;
        int mappingSize = 0;
        int mergedSize = 0;
        while (true) {
            boolean aWithin = aIdx < aSize;
            boolean bWithin = bIdx < bSize;
            if (!aWithin && !bWithin) break;
            boolean flip = aWithin && (!bWithin || a.getDouble(aIdx) < b.getDouble(bIdx) + EPSILON);
            double value = flip ? a.getDouble(aIdx++) : b.getDouble(bIdx++);
            if (mergedSize == 0 || prev < value - EPSILON) {
                first[mappingSize] = aIdx - 1;
                second[mappingSize] = bIdx - 1;
                merged[mergedSize] = value;
                mappingSize++;
                mergedSize++;
                prev = value;
            } else if (mergedSize > 0) {
                first[mappingSize - 1] = aIdx - 1;
                second[mappingSize - 1] = bIdx - 1;
            }
        }
        if (mergedSize == 0) merged[mergedSize++] = Math.min(a.getDouble(aSize - 1), b.getDouble(bSize - 1));
        DoubleArrayList coords = DoubleArrayList.wrap(merged);
        coords.size(mergedSize);
        int intervals = mergedSize - 1;
        int[] trimmedFirst = new int[intervals];
        int[] trimmedSecond = new int[intervals];
        System.arraycopy(first, 0, trimmedFirst, 0, intervals);
        System.arraycopy(second, 0, trimmedSecond, 0, intervals);
        return new MergePlan(coords, trimmedFirst, trimmedSecond, false);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static long lcm(int a, int b) {
        return (long) a * (long) (b / gcd(a, b));
    }

    private record MergePlan(DoubleList coords, int[] first, int[] second, boolean discreteCube) {
        int intervals() { return first.length; }
    }

    static final class UnsupportedMergeException extends RuntimeException {
        static final UnsupportedMergeException INSTANCE = new UnsupportedMergeException();
        private UnsupportedMergeException() { super(null, null, false, false); }
    }
}
