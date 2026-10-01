import dev.wachipayox.bootoptim.profiling.client.MeasuredQuadSet;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Actual diagnostic subclass versus actual fastutil; not a synthetic timing claim. */
public final class MeasuredQuadSetEquivalence {
    private static final Hash.Strategy<int[]> HASH = new Hash.Strategy<>() {
        public int hashCode(int[] a) {
            if (a == null) return 0;
            int h = 0;
            for (int v : a) h = 31 * h + HashCommon.murmurHash3(v);
            return h;
        }
        public boolean equals(int[] a, int[] b) { return Arrays.equals(a, b); }
    };
    public static void main(String[] args) throws Exception {
        MeasuredQuadSet<int[]> measured = new MeasuredQuadSet<>(HASH);
        ObjectOpenCustomHashSet<int[]> stock = new ObjectOpenCustomHashSet<>(HASH);
        Field keys = ObjectOpenCustomHashSet.class.getDeclaredField("key");
        keys.setAccessible(true);
        long comparisons = 0;
        for (int generation = 0; generation < 4; generation++) {
            measured.resetMetrics();
            int unique = new int[]{40000, 80000, 20000, 100000}[generation];
            for (int i = 0; i < unique * 3; i++) {
                int[] a = new int[32];
                for (int lane = 0; lane < a.length; lane++)
                    a[lane] = HashCommon.murmurHash3((i % unique) * 31 + lane + generation * 10000000);
                measured.countInsertion();
                require(stock.addOrGet(a) == measured.addOrGet(a), "First canonical representative changed");
                comparisons++;
            }
            require(stock.size() == measured.size(), "Unique count differs");
            int prior = measured.size();
            stock.clear(); measured.clear();
            require(stock.trim(), "Stock trim failed");
            // Exercise the candidate trim overload and control trim's virtual delegation.
            require(generation % 2 == 0 ? measured.trim() : measured.trim(Math.min(prior, 1 << 20)), "Measured trim failed");
            var m = measured.snapshot();
            require(m.valid() && m.clears() == 1 && m.trims() == 1 && m.size() == 0 && m.previousUnique() == unique,
                    "Invalid/nonexclusive phase counters");
            require(!MeasuredQuadSet.INSERTION_TIMING && m.insertionCpu() == -1 && m.insertionWall() == -1 && m.insertions() == unique * 3, "Cheap insertion scope/count changed");
            require(m.growthCpu() >= 0 && m.clearCpu() >= 0 && m.trimCpu() >= 0, "Clock invalid");
            if (generation == 2) require(m.growths() == 0 && m.growthCpu() == 0 && m.growthWall() == 0,
                    "Retained-capacity generation should have no growth interval");
            else require(m.growths() > 0, "Expected actual growth calls");
            require(m.slots() <= 1 << 21, "Storage bound exceeded");
            for (Object v : (Object[]) keys.get(measured)) require(v == null, "Model data survived clear");
        }
        measured.resetMetrics();
        measured.addOrGet(new int[]{1});
        measured.resetMetrics();
        require(!measured.snapshot().valid(), "Reset on a live generation must invalidate the trace");
        System.out.println("PASS canonical_identity_checks=" + comparisons + " clocks=valid trim_count=exclusive clear_references=empty reset_guard=valid");
    }
    private static void require(boolean test, String message) { if (!test) throw new AssertionError(message); }
}
