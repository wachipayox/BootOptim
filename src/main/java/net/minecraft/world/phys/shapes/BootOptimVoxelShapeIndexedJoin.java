package net.minecraft.world.phys.shapes;

import net.minecraft.core.Direction;

/**
 * Diagnostic-only exact specialization of Shapes.join(..., OR).
 *
 * <p>This deliberately lives in the Minecraft shapes package so it can use the same package-private
 * representation classes as stock. It does not alter coordinate merging or optimize history: it asks
 * stock Shapes.createIndexMerger for the exact three mergers, materializes their interval maps once,
 * evaluates the same BooleanOp over the same discrete cells, wraps the same merged coordinate lists,
 * then calls stock optimize at the same point as Shapes.join.</p>
 */
public final class BootOptimVoxelShapeIndexedJoin {
    private BootOptimVoxelShapeIndexedJoin() {}

    public static VoxelShape or(VoxelShape first, VoxelShape second) {
        if (first == second) {
            return first.optimize();
        }
        if (first.isEmpty()) {
            return second.optimize();
        }
        if (second.isEmpty()) {
            return first.optimize();
        }

        IndexMerger xMerger = Shapes.createIndexMerger(
                1, first.getCoords(Direction.Axis.X), second.getCoords(Direction.Axis.X), true, true);
        IndexMerger yMerger = Shapes.createIndexMerger(
                xMerger.size() - 1,
                first.getCoords(Direction.Axis.Y), second.getCoords(Direction.Axis.Y), true, true);
        IndexMerger zMerger = Shapes.createIndexMerger(
                (xMerger.size() - 1) * (yMerger.size() - 1),
                first.getCoords(Direction.Axis.Z), second.getCoords(Direction.Axis.Z), true, true);

        AxisMap x = AxisMap.capture(xMerger);
        AxisMap y = AxisMap.capture(yMerger);
        AxisMap z = AxisMap.capture(zMerger);
        BitSetDiscreteVoxelShape merged = new BitSetDiscreteVoxelShape(x.first.length, y.first.length, z.first.length);
        DiscreteVoxelShape firstShape = first.shape;
        DiscreteVoxelShape secondShape = second.shape;

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
        if (xMerger instanceof DiscreteCubeMerger
                && yMerger instanceof DiscreteCubeMerger
                && zMerger instanceof DiscreteCubeMerger) {
            unoptimized = new CubeVoxelShape(merged);
        } else {
            unoptimized = new ArrayVoxelShape(
                    merged, xMerger.getList(), yMerger.getList(), zMerger.getList());
        }
        return unoptimized.optimize();
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
            if (!complete) {
                throw new IllegalStateException("IndexMerger terminated while materializing complete interval map");
            }
            return new AxisMap(first, second);
        }
    }
}
