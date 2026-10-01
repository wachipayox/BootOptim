import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.util.Arrays;

/** Run with the exact fastutil 8.5.12 JAR. This checks storage semantics, not Mixin activation. */
public final class FerriteCapacityEquivalence {
    private static final int MAX_EXPECTED = 1 << 20;
    private static final Hash.Strategy<int[]> STRATEGY = new Hash.Strategy<>() {
        public int hashCode(int[] values) {
            int result = 0;
            for (int value : values) result = 31 * result + HashCommon.murmurHash3(value);
            return result;
        }
        public boolean equals(int[] left, int[] right) { return Arrays.equals(left, right); }
    };
    private static final class Set extends ObjectOpenCustomHashSet<int[]> {
        int growths;
        Set(int expected) { super(expected, STRATEGY); }
        protected void rehash(int capacity) { super.rehash(capacity); growths++; }
        int slots() { return n; }
        boolean noReferences() {
            // Use Object[]: the erased generic array is allocated as Object[] by fastutil.
            for (Object value : ((ObjectOpenCustomHashSet<?>) this).toArray()) {
                if (value != null) return false;
            }
            // Iterator checks only occupied entries, so also inspect the raw emptied storage.
            Object[] raw = rawKeys();
            for (Object value : raw) if (value != null) return false;
            return true;
        }
        Object[] rawKeys() { return key; }
    }

    public static void main(String[] args) {
        Set control = new Set(16);
        Set candidate = new Set(16);
        long comparisons = 0;
        for (int generation = 0; generation < 3; generation++) {
            int unique = new int[] {40000, 80000, 20000}[generation];
            for (int index = 0; index < unique * 3; index++) {
                int[] quad = new int[32];
                for (int lane = 0; lane < quad.length; lane++) {
                    quad[lane] = HashCommon.murmurHash3((index % unique) * 31 + lane + generation * 10000000);
                }
                // Both tables must return the exact same first representative object.
                require(control.addOrGet(quad) == candidate.addOrGet(quad), "canonical object changed");
                comparisons++;
            }
            require(control.size() == unique && candidate.size() == unique, "unique set differs");
            int previous = candidate.size();
            control.clear();
            candidate.clear();
            require(control.noReferences() && candidate.noReferences(), "generation data survived clear");
            require(control.trim(), "stock trim failed");
            require(candidate.trim(Math.min(previous, MAX_EXPECTED)), "bounded trim failed");
            require(candidate.slots() <= 1 << 21, "retained table exceeded slot bound");
        }
        Set oversized = new Set(2 * MAX_EXPECTED);
        require(oversized.trim(MAX_EXPECTED), "oversized shrink failed");
        require(oversized.slots() == 1 << 21 && oversized.noReferences(), "oversized bound/data failed");
        System.out.printf("PASS comparisons=%d stock_rehashes=%d retained_rehashes=%d max_slots=%d%n",
                comparisons, control.growths, candidate.growths, oversized.slots());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
