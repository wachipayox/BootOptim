package dev.wachipayox.bootoptim.optimization.client;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact immutable geometry sharing: position, color, light and normal words of a quad snapshot are
 * split from the UV words, so snapshots that differ only in texture coordinates share one geometry
 * chunk. Words are copied bit for bit, never recomputed. The weak index holds at most 8,192 chunks
 * and never roots a sprite, a model or an old resource generation.
 */
final class SplitQuadGeometry {
    private static final int LIMIT = 8192;
    private static final Map<Integer, List<WeakReference<PalettedIntChunk>>> POOL = new HashMap<>();
    private static int entries;
    private static long sharedBytes;
    private static long hits;
    private static long attempts;

    private SplitQuadGeometry() {}

    /** Stock BLOCK vertex layout per vertex: xyz, color, uv, light, normal. */
    static void pack(int[] input, int[] geometry, int g, int[] uv, int u) {
        for (int v = 0; v < 4; v++) {
            int p = v * 8;
            geometry[g++] = input[p];
            geometry[g++] = input[p + 1];
            geometry[g++] = input[p + 2];
            geometry[g++] = input[p + 3];
            uv[u++] = input[p + 4];
            uv[u++] = input[p + 5];
            geometry[g++] = input[p + 6];
            geometry[g++] = input[p + 7];
        }
    }

    static void unpack(PalettedIntChunk geometry, int g, PalettedIntChunk uv, int u, int[] output) {
        for (int v = 0; v < 4; v++) {
            int p = v * 8;
            output[p] = geometry.get(g++);
            output[p + 1] = geometry.get(g++);
            output[p + 2] = geometry.get(g++);
            output[p + 3] = geometry.get(g++);
            output[p + 4] = uv.get(u++);
            output[p + 5] = uv.get(u++);
            output[p + 6] = geometry.get(g++);
            output[p + 7] = geometry.get(g++);
        }
    }

    static boolean matches(int[] input, PalettedIntChunk geometry, int g, PalettedIntChunk uv, int u) {
        for (int v = 0; v < 4; v++) {
            int p = v * 8;
            if (input[p] != geometry.get(g++) || input[p + 1] != geometry.get(g++)
                    || input[p + 2] != geometry.get(g++) || input[p + 3] != geometry.get(g++)
                    || input[p + 4] != uv.get(u++) || input[p + 5] != uv.get(u++)
                    || input[p + 6] != geometry.get(g++) || input[p + 7] != geometry.get(g++)) {
                return false;
            }
        }
        return true;
    }

    static synchronized PalettedIntChunk share(int[] candidate) {
        attempts++;
        int hash = Arrays.hashCode(candidate);
        List<WeakReference<PalettedIntChunk>> bucket = POOL.get(hash);
        if (bucket != null) {
            for (var iterator = bucket.iterator(); iterator.hasNext();) {
                PalettedIntChunk existing = iterator.next().get();
                if (existing == null) {
                    iterator.remove();
                    entries--;
                } else if (existing.matches(candidate)) {
                    hits++;
                    sharedBytes += (long) candidate.length * 4;
                    return existing;
                }
            }
        }
        if (entries >= LIMIT) {
            for (var iterator = POOL.values().iterator(); iterator.hasNext();) {
                var values = iterator.next();
                int before = values.size();
                values.removeIf(reference -> reference.get() == null);
                entries -= before - values.size();
                if (values.isEmpty()) {
                    iterator.remove();
                }
            }
            // Bounded index only: clearing weak entries never invalidates a live snapshot.
            if (entries >= LIMIT) {
                POOL.clear();
                entries = 0;
            }
        }
        PalettedIntChunk frozen = PalettedIntChunk.freeze(candidate);
        POOL.computeIfAbsent(hash, key -> new ArrayList<>()).add(new WeakReference<>(frozen));
        entries++;
        return frozen;
    }

    static synchronized String status() {
        return "geometry_chunks=" + attempts + " geometry_shared_chunks=" + hits
                + " cumulative_geometry_shared_bytes=" + sharedBytes;
    }
}
