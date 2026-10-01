package dev.wachipayox.bootoptim.optimization.client;

import it.unimi.dsi.fastutil.HashCommon;

/** Exact FerriteCore polynomial over four independent integer accumulators. No retained data. */
public final class FerriteQuadHashArithmetic {
    private FerriteQuadHashArithmetic() {}

    public static int hash(int[] values) {
        int h0 = 0, h1 = 0, h2 = 0, h3 = 0, i = 0;
        for (; i <= values.length - 4; i += 4) {
            h0 = 923521 * h0 + HashCommon.murmurHash3(values[i]);
            h1 = 923521 * h1 + HashCommon.murmurHash3(values[i + 1]);
            h2 = 923521 * h2 + HashCommon.murmurHash3(values[i + 2]);
            h3 = 923521 * h3 + HashCommon.murmurHash3(values[i + 3]);
        }
        int hash = h0 * 29791 + h1 * 961 + h2 * 31 + h3;
        for (; i < values.length; ++i) hash = 31 * hash + HashCommon.murmurHash3(values[i]);
        return hash;
    }
}
