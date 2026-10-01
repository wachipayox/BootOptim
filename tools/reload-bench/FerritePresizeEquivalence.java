import dev.wachipayox.bootoptim.optimization.client.FerriteCoreQuadPresize;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Actual stock table, collisions/nulls/generation changes/canonical identity and lifetime. */
public final class FerritePresizeEquivalence {
    static final Hash.Strategy<int[]> HASH = new Hash.Strategy<>() {
        public int hashCode(int[] a) { return a == null ? 0 : a[0] & 63; }
        public boolean equals(int[] a, int[] b) { return Arrays.equals(a, b); }
    };
    static final class CustomSet extends ObjectOpenCustomHashSet<int[]> {
        CustomSet() { super(HASH); }
        public void ensureCapacity(int n) { throw new AssertionError("Unknown custom callback invoked"); }
    }
    static int slots(ObjectOpenCustomHashSet<?> s) throws Exception {
        Field f = ObjectOpenCustomHashSet.class.getDeclaredField("n"); f.setAccessible(true);
        return f.getInt(s);
    }
    static void empty(ObjectOpenCustomHashSet<?> s) throws Exception {
        Field f = ObjectOpenCustomHashSet.class.getDeclaredField("key"); f.setAccessible(true);
        for (Object key : (Object[])f.get(s)) if (key != null) throw new AssertionError("Retained key");
    }
    public static void main(String[] args) throws Exception {
        ObjectOpenCustomHashSet<int[]> control = new ObjectOpenCustomHashSet<>(HASH);
        ObjectOpenCustomHashSet<int[]> candidate = new ObjectOpenCustomHashSet<>(HASH);
        for (int generation = 0; generation < 4; generation++) {
            for (int i = 0; i < 40000; i++) {
                int[] fresh = { i % (10000 + generation * 500), generation };
                FerriteCoreQuadPresize.beforeInsert(candidate);
                if (control.addOrGet(fresh) != candidate.addOrGet(fresh)) throw new AssertionError("Canonical identity changed");
            }
            FerriteCoreQuadPresize.beforeInsert(candidate);
            if (control.addOrGet(null) != candidate.addOrGet(null)) throw new AssertionError("Null changed");
            int previous = candidate.size();
            control.clear(); candidate.clear();
            FerriteCoreQuadPresize.remember(candidate, previous, true);
            if (!control.trim() || !candidate.trim()) throw new AssertionError("Trim failed");
            empty(candidate);
            if (slots(candidate) != 1 || FerriteCoreQuadPresize.pendingExpected() != previous) throw new AssertionError("Storage not released");
            // No allocation during preparation: repeated inspection remains one slot.
            if (slots(candidate) != 1) throw new AssertionError("Early reservation");
        }
        FerriteCoreQuadPresize.remember(candidate, Integer.MAX_VALUE, true);
        if (FerriteCoreQuadPresize.pendingExpected() != 1 << 20) throw new AssertionError("Unbounded hint");
        FerriteCoreQuadPresize.beforeInsert(candidate);
        if (slots(candidate) > 1 << 21 || FerriteCoreQuadPresize.pendingExpected() != 0) throw new AssertionError("Unbounded allocation");
        empty(candidate);
        candidate.trim();
        FerriteCoreQuadPresize.remember(candidate, 50000, false);
        FerriteCoreQuadPresize.beforeInsert(candidate);
        if (slots(candidate) != 1) throw new AssertionError("Disabled path allocated");
        CustomSet custom = new CustomSet();
        FerriteCoreQuadPresize.remember(custom, 50000, true);
        FerriteCoreQuadPresize.beforeInsert(custom);
        candidate.add(new int[]{1, 0});
        FerriteCoreQuadPresize.remember(candidate, 50000, true);
        if (FerriteCoreQuadPresize.pendingExpected() != 0) throw new AssertionError("Nonempty eligible");
        System.out.println("Ferrite late-presize: 160000 identities, changed generations, null/collisions, stock trim, no retained keys, bounds and fail-open PASS");
    }
}
