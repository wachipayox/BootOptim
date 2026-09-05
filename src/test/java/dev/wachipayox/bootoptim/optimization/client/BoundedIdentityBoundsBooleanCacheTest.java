package dev.wachipayox.bootoptim.optimization.client;

/** Lightweight no-dependency regression tests executed by Gradle check. */
public final class BoundedIdentityBoundsBooleanCacheTest {
    private BoundedIdentityBoundsBooleanCacheTest() {
    }

    public static void main(String[] args) {
        hitAndMissUseIdentityAndExactBounds();
        falseValuesRemainDistinguishableFromMisses();
        clearInvalidatesAllEntries();
        mutationAcrossReloadCannotReuseStaleResult();
        capacityIsBoundedWithoutEvictingExistingKeys();
        System.out.println("BoundedIdentityBoundsBooleanCacheTest PASS");
    }

    private static void hitAndMissUseIdentityAndExactBounds() {
        BoundedIdentityBoundsBooleanCache cache = new BoundedIdentityBoundsBooleanCache(4);
        Object image = new Object();
        Object sameContentDifferentIdentity = new Object();
        check(cache.get(image, 0, 16, 0, 16) == BoundedIdentityBoundsBooleanCache.MISS, "initial miss");
        check(cache.putIfAbsent(image, 0, 16, 0, 16, true) == BoundedIdentityBoundsBooleanCache.STORED, "store");
        check(cache.get(image, 0, 16, 0, 16) == BoundedIdentityBoundsBooleanCache.TRUE, "exact hit");
        check(cache.get(image, 0, 15, 0, 16) == BoundedIdentityBoundsBooleanCache.MISS, "different bounds miss");
        check(cache.get(sameContentDifferentIdentity, 0, 16, 0, 16) == BoundedIdentityBoundsBooleanCache.MISS,
                "different identity miss");
    }

    private static void falseValuesRemainDistinguishableFromMisses() {
        BoundedIdentityBoundsBooleanCache cache = new BoundedIdentityBoundsBooleanCache(2);
        Object image = new Object();
        cache.putIfAbsent(image, 1, 2, 3, 4, false);
        check(cache.get(image, 1, 2, 3, 4) == BoundedIdentityBoundsBooleanCache.FALSE, "false hit");
        check(cache.get(image, 1, 2, 3, 5) == BoundedIdentityBoundsBooleanCache.MISS, "false versus miss");
    }

    private static void clearInvalidatesAllEntries() {
        BoundedIdentityBoundsBooleanCache cache = new BoundedIdentityBoundsBooleanCache(2);
        Object image = new Object();
        cache.putIfAbsent(image, 0, 1, 0, 1, true);
        check(cache.size() == 1, "size before clear");
        cache.clear();
        check(cache.size() == 0, "size after clear");
        check(cache.get(image, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.MISS, "clear invalidates");
    }

    private static void mutationAcrossReloadCannotReuseStaleResult() {
        BoundedIdentityBoundsBooleanCache cache = new BoundedIdentityBoundsBooleanCache(2);
        MutableImage image = new MutableImage(255);
        boolean first = scan(image);
        cache.putIfAbsent(image, 0, 1, 0, 1, first);
        check(cache.get(image, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.FALSE, "opaque first generation");

        image.alpha = 0; // Models the same mutable NativeImage identity changing between generations.
        cache.clear();   // Resource reload boundary.
        check(cache.get(image, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.MISS, "mutation must miss after reload");
        boolean second = scan(image);
        cache.putIfAbsent(image, 0, 1, 0, 1, second);
        check(cache.get(image, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.TRUE, "recomputed mutated value");
    }

    private static void capacityIsBoundedWithoutEvictingExistingKeys() {
        BoundedIdentityBoundsBooleanCache cache = new BoundedIdentityBoundsBooleanCache(2);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        check(cache.putIfAbsent(first, 0, 1, 0, 1, true) == BoundedIdentityBoundsBooleanCache.STORED, "first store");
        check(cache.putIfAbsent(second, 0, 1, 0, 1, false) == BoundedIdentityBoundsBooleanCache.STORED, "second store");
        check(cache.putIfAbsent(third, 0, 1, 0, 1, true) == BoundedIdentityBoundsBooleanCache.FULL, "bounded full");
        check(cache.get(first, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.TRUE, "first retained");
        check(cache.get(second, 0, 1, 0, 1) == BoundedIdentityBoundsBooleanCache.FALSE, "second retained");
    }

    private static boolean scan(MutableImage image) {
        return image.alpha != 255;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MutableImage {
        private int alpha;

        private MutableImage(int alpha) {
            this.alpha = alpha;
        }
    }
}
