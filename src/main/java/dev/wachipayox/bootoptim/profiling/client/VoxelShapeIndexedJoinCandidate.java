package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.mixin.client.accessor.ArrayVoxelShapeInvoker;
import dev.wachipayox.bootoptim.mixin.client.accessor.CubeVoxelShapeInvoker;
import dev.wachipayox.bootoptim.mixin.client.accessor.ShapesInvoker;
import dev.wachipayox.bootoptim.mixin.client.accessor.VoxelShapeAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.IndexMerger;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Diagnostic-only exact consumer specialization for stock OR mergers. */
final class VoxelShapeIndexedJoinCandidate {
    private static final String DISCRETE_CUBE_MERGER = "net.minecraft.world.phys.shapes.DiscreteCubeMerger";

    private VoxelShapeIndexedJoinCandidate() {}

    static VoxelShape or(VoxelShape first, VoxelShape second) {
        if (first == second) return first.optimize();
        if (first.isEmpty()) return second.optimize();
        if (second.isEmpty()) return first.optimize();

        IndexMerger xMerger = ShapesInvoker.bootoptim$createIndexMerger(
                1, first.getCoords(Direction.Axis.X), second.getCoords(Direction.Axis.X), true, true);
        IndexMerger yMerger = ShapesInvoker.bootoptim$createIndexMerger(
                xMerger.size() - 1,
                first.getCoords(Direction.Axis.Y), second.getCoords(Direction.Axis.Y), true, true);
        IndexMerger zMerger = ShapesInvoker.bootoptim$createIndexMerger(
                (xMerger.size() - 1) * (yMerger.size() - 1),
                first.getCoords(Direction.Axis.Z), second.getCoords(Direction.Axis.Z), true, true);

        AxisMap x = AxisMap.capture(xMerger);
        AxisMap y = AxisMap.capture(yMerger);
        AxisMap z = AxisMap.capture(zMerger);
        BitSetDiscreteVoxelShape merged = new BitSetDiscreteVoxelShape(x.first.length, y.first.length, z.first.length);
        DiscreteVoxelShape firstShape = ((VoxelShapeAccessor) first).bootoptim$getShape();
        DiscreteVoxelShape secondShape = ((VoxelShapeAccessor) second).bootoptim$getShape();

        for (int xi = 0; xi < x.first.length; xi++) {
            int fx = x.first[xi];
            int sx = x.second[xi];
            for (int yi = 0; yi < y.first.length; yi++) {
                int fy = y.first[yi];
                int sy = y.second[yi];
                for (int zi = 0; zi < z.first.length; zi++) {
                    if (firstShape.isFullWide(fx, fy, z.first[zi])
                            || secondShape.isFullWide(sx, sy, z.second[zi])) {
                        merged.fill(xi, yi, zi);
                    }
                }
            }
        }

        VoxelShape unoptimized;
        if (isDiscreteCubeMerger(xMerger) && isDiscreteCubeMerger(yMerger) && isDiscreteCubeMerger(zMerger)) {
            unoptimized = CubeVoxelShapeInvoker.bootoptim$create(merged);
        } else {
            unoptimized = ArrayVoxelShapeInvoker.bootoptim$create(
                    merged, xMerger.getList(), yMerger.getList(), zMerger.getList());
        }
        return unoptimized.optimize();
    }

    private static boolean isDiscreteCubeMerger(IndexMerger merger) {
        return DISCRETE_CUBE_MERGER.equals(merger.getClass().getName());
    }

    private record AxisMap(int[] first, int[] second) {
        static AxisMap capture(IndexMerger merger) {
            int intervals = merger.size() - 1;
            int[] first = new int[intervals];
            int[] second = new int[intervals];
            boolean complete = merger.forMergedIndexes((firstIndex, secondIndex, resultIndex) -> {
                first[resultIndex] = firstIndex;
                second[resultIndex] = secondIndex;
                return true;
            });
            if (!complete) throw new IllegalStateException("IndexMerger terminated unexpectedly");
            return new AxisMap(first, second);
        }
    }
}
