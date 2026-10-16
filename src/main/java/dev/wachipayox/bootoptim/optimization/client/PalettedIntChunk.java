package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable raw-bit palette of an {@code int[]}. Encoding never rounds floats and never retains the
 * caller's array. A chunk is palette-encoded only when the payload shrinks by at least 12.5 % after
 * a conservative 96-byte allowance for the extra object/array headers; otherwise it is stored raw.
 * The dictionary is built in a primitive, bounded (1,024 values) workspace borrowed from a pool of at
 * most four, so freezing allocates only the final arrays.
 */
final class PalettedIntChunk {
    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong RAW_BYTES = new AtomicLong();
    private static final AtomicLong ENCODED_BYTES = new AtomicLong();
    private static final ArrayDeque<Workspace> WORKSPACES = new ArrayDeque<>();

    private final int length;
    private final int[] data;
    private final byte[] indices8;
    private final long[] packedIndices;
    private final int bits;
    private final int perWord;
    private final boolean raw;

    private static final class Workspace {
        final int[] keys = new int[2048];
        final int[] slots = new int[2048];
        final int[] palette = new int[1024];
        final short[] indices = new short[12288];
    }

    private static Workspace borrow() {
        synchronized (WORKSPACES) {
            Workspace value = WORKSPACES.pollFirst();
            if (value != null) {
                return value;
            }
        }
        return new Workspace();
    }

    private static void release(Workspace value) {
        synchronized (WORKSPACES) {
            if (WORKSPACES.size() < 4) {
                WORKSPACES.addFirst(value);
            }
        }
    }

    private PalettedIntChunk(int length, int[] data, byte[] indices8, long[] packedIndices, int bits, boolean raw) {
        this.length = length;
        this.data = data;
        this.indices8 = indices8;
        this.packedIndices = packedIndices;
        this.bits = bits;
        this.perWord = bits == 0 ? 0 : 64 / bits;
        this.raw = raw;
        CHUNKS.incrementAndGet();
        RAW_BYTES.addAndGet((long) length * 4);
        ENCODED_BYTES.addAndGet(payloadBytes());
    }

    static PalettedIntChunk freeze(int[] source) {
        if (source.length < 32 || source.length > 12288) {
            return raw(source);
        }
        Workspace workspace = borrow();
        try {
            int limit = Math.min(1024, source.length / 2);
            int capacity = 1;
            while (capacity < limit * 2) {
                capacity <<= 1;
            }
            int[] keys = workspace.keys;
            int[] slots = workspace.slots;
            int[] palette = workspace.palette;
            short[] indices = workspace.indices;
            Arrays.fill(slots, 0, capacity, 0);
            int count = 0;
            int mask = capacity - 1;
            for (int i = 0; i < source.length; i++) {
                int value = source[i];
                int hash = value * 0x9E3779B9;
                hash ^= hash >>> 16;
                int slot = hash & mask;
                while (slots[slot] != 0 && keys[slot] != value) {
                    slot = (slot + 1) & mask;
                }
                if (slots[slot] == 0) {
                    if (count == limit) {
                        return raw(source);
                    }
                    keys[slot] = value;
                    palette[count] = value;
                    slots[slot] = ++count;
                }
                indices[i] = (short) (slots[slot] - 1);
            }
            int bits = count <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
            int perWord = bits == 0 ? 0 : 64 / bits;
            // Entries never cross a word boundary: one load and an unsigned shift per lookup.
            // Byte addressing is kept when 8 bits is already optimal; every other width is packed.
            long indexBytes = bits == 0 ? 0 : bits == 8 ? source.length : ((long) source.length + perWord - 1) / perWord * 8;
            long encoded = (long) count * 4 + indexBytes;
            if (encoded + 96 >= (long) source.length * 4 * 7 / 8) {
                return raw(source);
            }
            int[] values = Arrays.copyOf(palette, count);
            if (count == 1) {
                return new PalettedIntChunk(source.length, values, null, null, 0, false);
            }
            if (bits == 8) {
                byte[] small = new byte[source.length];
                for (int i = 0; i < small.length; i++) {
                    small[i] = (byte) indices[i];
                }
                return new PalettedIntChunk(source.length, values, small, null, 8, false);
            }
            long[] packed = new long[(source.length + perWord - 1) / perWord];
            for (int i = 0; i < source.length; i++) {
                packed[i / perWord] |= (long) (indices[i] & 65535) << ((i % perWord) * bits);
            }
            return new PalettedIntChunk(source.length, values, null, packed, bits, false);
        } finally {
            release(workspace);
        }
    }

    private static PalettedIntChunk raw(int[] source) {
        return new PalettedIntChunk(source.length, source.clone(), null, null, 0, true);
    }

    int get(int index) {
        Objects.checkIndex(index, length);
        if (raw) {
            return data[index];
        }
        if (data.length == 1) {
            return data[0];
        }
        int slot = indices8 != null
                ? indices8[index] & 255
                : (int) ((packedIndices[index / perWord] >>> ((index % perWord) * bits)) & ((1L << bits) - 1));
        return data[slot];
    }

    boolean matches(int[] input) {
        if (input.length != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (input[i] != get(i)) {
                return false;
            }
        }
        return true;
    }

    long payloadBytes() {
        return (long) data.length * 4 + (indices8 == null ? 0 : indices8.length)
                + (packedIndices == null ? 0 : (long) packedIndices.length * 8);
    }

    static String status() {
        return "palette_chunks=" + CHUNKS.get() + " cumulative_palette_raw_bytes=" + RAW_BYTES.get()
                + " cumulative_palette_encoded_bytes=" + ENCODED_BYTES.get();
    }
}
