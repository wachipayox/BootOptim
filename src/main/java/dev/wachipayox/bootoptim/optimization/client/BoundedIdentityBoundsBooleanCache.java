package dev.wachipayox.bootoptim.optimization.client;

import java.util.Arrays;

/**
 * Fixed-capacity boolean cache keyed by object identity plus four integer bounds.
 *
 * <p>This deliberately avoids allocating a key object on every lookup. Empty slots are represented by
 * a null image reference; callers must not pass null as an identity key.</p>
 */
final class BoundedIdentityBoundsBooleanCache {
    static final int MISS = -1;
    static final int FALSE = 0;
    static final int TRUE = 1;

    static final int STORED = 0;
    static final int PRESENT = 1;
    static final int FULL = 2;

    private final int maxEntries;
    private final Object[] images;
    private final int[] minWidths;
    private final int[] maxWidths;
    private final int[] minHeights;
    private final int[] maxHeights;
    private final byte[] values;
    private final int mask;
    private int size;

    BoundedIdentityBoundsBooleanCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        int capacity = 2;
        long requested = Math.max(2L, (long) maxEntries * 2L);
        while (capacity < requested) {
            capacity <<= 1;
            if (capacity <= 0) {
                throw new IllegalArgumentException("maxEntries is too large");
            }
        }
        this.images = new Object[capacity];
        this.minWidths = new int[capacity];
        this.maxWidths = new int[capacity];
        this.minHeights = new int[capacity];
        this.maxHeights = new int[capacity];
        this.values = new byte[capacity];
        this.mask = capacity - 1;
    }

    int get(Object image, int minWidth, int maxWidth, int minHeight, int maxHeight) {
        int slot = startSlot(image, minWidth, maxWidth, minHeight, maxHeight);
        while (true) {
            Object existing = images[slot];
            if (existing == null) {
                return MISS;
            }
            if (existing == image
                    && minWidths[slot] == minWidth
                    && maxWidths[slot] == maxWidth
                    && minHeights[slot] == minHeight
                    && maxHeights[slot] == maxHeight) {
                return values[slot] == 0 ? FALSE : TRUE;
            }
            slot = (slot + 1) & mask;
        }
    }

    int putIfAbsent(
            Object image,
            int minWidth,
            int maxWidth,
            int minHeight,
            int maxHeight,
            boolean value) {
        int slot = startSlot(image, minWidth, maxWidth, minHeight, maxHeight);
        while (true) {
            Object existing = images[slot];
            if (existing == null) {
                if (size >= maxEntries) {
                    return FULL;
                }
                images[slot] = image;
                minWidths[slot] = minWidth;
                maxWidths[slot] = maxWidth;
                minHeights[slot] = minHeight;
                maxHeights[slot] = maxHeight;
                values[slot] = (byte) (value ? 1 : 0);
                size++;
                return STORED;
            }
            if (existing == image
                    && minWidths[slot] == minWidth
                    && maxWidths[slot] == maxWidth
                    && minHeights[slot] == minHeight
                    && maxHeights[slot] == maxHeight) {
                return PRESENT;
            }
            slot = (slot + 1) & mask;
        }
    }

    int size() {
        return size;
    }

    void clear() {
        Arrays.fill(images, null);
        size = 0;
    }

    private int startSlot(Object image, int minWidth, int maxWidth, int minHeight, int maxHeight) {
        int hash = System.identityHashCode(image);
        hash = 31 * hash + minWidth;
        hash = 31 * hash + maxWidth;
        hash = 31 * hash + minHeight;
        hash = 31 * hash + maxHeight;
        hash ^= hash >>> 16;
        return hash & mask;
    }
}
