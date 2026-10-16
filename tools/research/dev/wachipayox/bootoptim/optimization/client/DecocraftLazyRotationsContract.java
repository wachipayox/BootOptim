package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Host-only contract for the deferred quarter-turn variants and their compact snapshots. Lives in the
 * product package only to reach package-private classes; compiled from tools/, never packaged.
 * Run with -Dboot_optim.decocraftLazyRotations=true.
 */
public final class DecocraftLazyRotationsContract {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    public static void main(String[] args) throws Exception {
        check(DecocraftLazyRotations.enabled(), "run with -Dboot_optim.decocraftLazyRotations=true");
        palettes();
        splitGeometry();
        deferredList();
        lazyEqualsEager();
        System.out.println("DECOCRAFT_LAZY_ROTATIONS_CONTRACT PASS checks=" + checks);
    }

    private static void palettes() {
        for (int unique : new int[] {1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513, 1024, 1025, 12000}) {
            int[] source = new int[12288];
            for (int i = 0; i < source.length; i++) {
                source[i] = Integer.rotateLeft(i % unique, 13) ^ 0x80000000;
            }
            int[] expected = source.clone();
            PalettedIntChunk chunk = PalettedIntChunk.freeze(source);
            Arrays.fill(source, 42);
            check(chunk.matches(expected), "frozen chunk must not alias the caller array: " + unique);
            for (int i = 0; i < expected.length; i++) {
                if (chunk.get(i) != expected[i]) {
                    throw new AssertionError("bits changed at " + i + " unique=" + unique);
                }
            }
            if (unique <= 257) {
                check(chunk.payloadBytes() <= expected.length * 3L, "palette benefit for " + unique);
            }
            for (int bad : new int[] {-1, source.length}) {
                try {
                    chunk.get(bad);
                    check(false, "index " + bad + " accepted");
                } catch (IndexOutOfBoundsException expectedFailure) {
                    check(true, "bounds");
                }
            }
        }
        Random random = new Random(3847);
        for (int i = 0; i < 2000; i++) {
            int[] raw = random.ints(i % 151).toArray();
            check(PalettedIntChunk.freeze(raw).matches(raw), "raw fallback");
        }
        for (int length : new int[] {0, 1, 31, 32, 63, 512, 12288}) {
            int[] raw = new int[length];
            Arrays.fill(raw, 0x7fc00001);
            check(PalettedIntChunk.freeze(raw).matches(raw), "NaN payload bits");
        }
        for (int unique : new int[] {2, 3, 5, 17, 33, 65, 129, 257, 513}) {
            for (int length : new int[] {32, 63, 64, 65, 127, 128, 129, 511, 512, 513, 12287, 12288}) {
                int[] input = new int[length];
                for (int i = 0; i < length; i++) {
                    input[i] = Integer.reverse(i % unique) ^ 0x7fc00001;
                }
                check(PalettedIntChunk.freeze(input).matches(input), "word edge " + unique + "/" + length);
            }
        }
        // Admission needs >= 12.5 % benefit after the 96-byte allowance: a 32-int chunk of 32 distinct values stays raw.
        int[] distinct = new int[32];
        for (int i = 0; i < distinct.length; i++) {
            distinct[i] = i * 0x01010101;
        }
        check(PalettedIntChunk.freeze(distinct).payloadBytes() == 32 * 4, "no admission without the minimum benefit");
    }

    private static int[] quadWords(Random random, boolean sameGeometry, int[] base) {
        int[] words = new int[32];
        for (int v = 0; v < 4; v++) {
            int p = v * 8;
            for (int k = 0; k < 8; k++) {
                words[p + k] = sameGeometry ? base[p + k] : random.nextInt();
            }
            if (sameGeometry) {
                words[p + 4] = random.nextInt();
                words[p + 5] = random.nextInt();
            }
        }
        return words;
    }

    private static void splitGeometry() {
        Random random = new Random(11);
        for (int n = 0; n < 10_000; n++) {
            int[] input = quadWords(random, false, null);
            int[] geometry = new int[24];
            int[] uv = new int[8];
            SplitQuadGeometry.pack(input, geometry, 0, uv, 0);
            PalettedIntChunk g = PalettedIntChunk.freeze(geometry);
            PalettedIntChunk u = PalettedIntChunk.freeze(uv);
            int[] output = new int[32];
            SplitQuadGeometry.unpack(g, 0, u, 0, output);
            if (!Arrays.equals(input, output) || !SplitQuadGeometry.matches(input, g, 0, u, 0)) {
                throw new AssertionError("raw-bit round trip failed at " + n);
            }
        }
        // Texture-only variants share the geometry chunk but keep independent UVs.
        int[] base = quadWords(random, false, null);
        int[] geometryA = new int[24 * 64];
        int[] geometryB = new int[24 * 64];
        for (int i = 0; i < 64; i++) {
            int[] a = base.clone();
            int[] b = base.clone();
            b[4] ^= 0x55555555;
            b[13] ^= 0x33333333;
            SplitQuadGeometry.pack(a, geometryA, i * 24, new int[8], 0);
            SplitQuadGeometry.pack(b, geometryB, i * 24, new int[8], 0);
        }
        check(Arrays.equals(geometryA, geometryB), "UV-only change keeps the geometry words identical");
        PalettedIntChunk first = SplitQuadGeometry.share(geometryA);
        PalettedIntChunk second = SplitQuadGeometry.share(geometryB);
        check(first == second, "equal geometry is shared");
        geometryA[0] ^= 1;
        check(SplitQuadGeometry.share(geometryA) != first, "different geometry is not shared");
        check(first.get(0) == geometryB[0], "shared chunk not mutated through the caller array");
    }

    private static void deferredList() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var list = new DeferredImmutableList<Integer>(3, () -> {
            calls.incrementAndGet();
            return new ArrayList<>(List.of(1, 2, 3));
        });
        check(list.size() == 3 && calls.get() == 0, "size without evaluation");
        var executor = Executors.newFixedThreadPool(8);
        List<Future<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            final int index = i % 3;
            jobs.add(executor.submit((Callable<Integer>) () -> list.get(index)));
        }
        for (Future<Integer> job : jobs) {
            job.get();
        }
        executor.shutdown();
        check(calls.get() == 1, "computed exactly once under concurrency");
        check(list.equals(List.of(1, 2, 3)) && Arrays.equals(list.toArray(), new Object[] {1, 2, 3}), "List semantics");
        try {
            list.add(4);
            check(false, "mutation accepted");
        } catch (UnsupportedOperationException expected) {
            check(true, "immutable");
        }
        var drift = new DeferredImmutableList<Integer>(2, () -> List.of(1));
        try {
            drift.get(0);
            check(false, "size drift accepted");
        } catch (IllegalStateException expected) {
            check(true, "size drift rejected");
        }
    }

    private static Matrix4f turn(int quarter) {
        return new Matrix4f().rotateY((float) (Math.PI / 2 * quarter));
    }

    private static void lazyEqualsEager() {
        Random random = new Random(29);
        for (int round = 0; round < 6; round++) {
            int count = round == 0 ? 0 : 1 + random.nextInt(1500);
            List<BakedQuad> source = new ArrayList<>();
            int sharedTint = random.nextInt(3) - 1;
            for (int i = 0; i < count; i++) {
                int[] words = quadWords(random, false, null);
                // Plausible block-space positions and normals; any bit pattern must still survive a round trip.
                for (int v = 0; v < 4; v++) {
                    words[v * 8] = Float.floatToRawIntBits(random.nextFloat());
                    words[v * 8 + 1] = Float.floatToRawIntBits(random.nextFloat());
                    words[v * 8 + 2] = Float.floatToRawIntBits(random.nextFloat());
                    words[v * 8 + 7] = (random.nextInt(255) << 24) | (random.nextInt() & 0xFFFFFF);
                }
                source.add(new BakedQuad(words, round % 2 == 0 ? sharedTint : random.nextInt(4) - 1,
                        Direction.values()[random.nextInt(6)], null, random.nextBoolean(), random.nextBoolean()));
            }
            source = List.copyOf(source);
            Matrix4f matrix = turn(1 + round % 3);
            List<BakedQuad> eager = DecocraftRotatedQuadReuse.rotateQuads(source, matrix);
            List<BakedQuad> lazy = DecocraftLazyRotations.defer(source, matrix, DecocraftRotatedQuadReuse::rotateQuads);
            check(lazy instanceof DeferredImmutableList<?>, "deferred type");
            check(lazy.size() == eager.size(), "size known up front");
            // Mutating the source arrays after the plan must not leak into the variant.
            int[][] before = new int[source.size()][];
            for (int i = 0; i < source.size(); i++) {
                before[i] = source.get(i).getVertices().clone();
            }
            for (int i = 0; i < eager.size(); i++) {
                BakedQuad a = eager.get(i);
                BakedQuad b = lazy.get(i);
                if (!Arrays.equals(a.getVertices(), b.getVertices()) || a.getTintIndex() != b.getTintIndex()
                        || a.getDirection() != b.getDirection() || a.getSprite() != b.getSprite()
                        || a.isShade() != b.isShade() || a.hasAmbientOcclusion() != b.hasAmbientOcclusion()) {
                    throw new AssertionError("lazy != eager at quad " + i + " round " + round);
                }
                check(lazy.get(i) == b, "second access returns the realized quad");
                check(b.getClass() == BakedQuad.class, "result quads are plain BakedQuads, never the cursor");
            }
            for (int i = 0; i < source.size(); i++) {
                check(Arrays.equals(before[i], source.get(i).getVertices()), "source inputs untouched");
            }
        }
        // Unknown quad implementation keeps the eager path.
        BakedQuad odd = new BakedQuad(new int[32], -1, Direction.UP, null, true, true) {};
        List<BakedQuad> oddList = List.of(odd);
        check(!(DecocraftLazyRotations.defer(oddList, turn(1), DecocraftRotatedQuadReuse::rotateQuads) instanceof DeferredImmutableList<?>),
                "subclassed quad stays eager");
    }
}
