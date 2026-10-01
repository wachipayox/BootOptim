import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import dev.wachipayox.bootoptim.optimization.client.FerriteQuadHashArithmetic;

/** Offline arithmetic premise only. Synthetic data is not real-pack performance evidence. */
public final class FerriteHashArithmeticAudit {
    static volatile long sink;
    // Exact recurrence transcribed from installed FerriteCore 7.0.3 bytecode.
    static int stock(int[] a) {
        int h = 0;
        for (int v : a) h = 31 * h + HashCommon.murmurHash3(v);
        return h;
    }
    static int expanded(int[] a) {
        int h = 0, i = 0;
        for (; i <= a.length - 4; i += 4) {
            int x0 = HashCommon.murmurHash3(a[i]);
            int x1 = HashCommon.murmurHash3(a[i + 1]);
            int x2 = HashCommon.murmurHash3(a[i + 2]);
            int x3 = HashCommon.murmurHash3(a[i + 3]);
            h = h * 923521 + x0 * 29791 + x1 * 961 + x2 * 31 + x3;
        }
        for (; i < a.length; ++i) h = 31 * h + HashCommon.murmurHash3(a[i]);
        return h;
    }
    static int grouped(int[] a) {
        return FerriteQuadHashArithmetic.hash(a);
    }
    static Hash.Strategy<int[]> strategy(boolean candidate) {
        return new Hash.Strategy<>() {
            public int hashCode(int[] a) { return candidate ? grouped(a) : stock(a); }
            public boolean equals(int[] a, int[] b) { return Arrays.equals(a, b); }
        };
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Random r = new Random(0xFE7717EL);
        long checks = 0;
        for (int length = 0; length <= 256; ++length) {
            for (int sample = 0; sample < 2000; ++sample) {
                int[] a = new int[length];
                for (int i = 0; i < length; ++i) a[i] = r.nextInt();
                require(stock(a) == grouped(a), "hash mismatch length=" + length);
                require(stock(a) == expanded(a), "expanded hash mismatch length=" + length);
                ++checks;
            }
            for (int value : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                int[] a = new int[length]; Arrays.fill(a, value);
                require(stock(a) == grouped(a), "constant hash mismatch");
                ++checks;
            }
        }
        try { grouped(null); throw new AssertionError("null accepted"); }
        catch (NullPointerException expected) { }
        ObjectOpenCustomHashSet<int[]> c = new ObjectOpenCustomHashSet<>(strategy(false));
        ObjectOpenCustomHashSet<int[]> b = new ObjectOpenCustomHashSet<>(strategy(true));
        for (int generation = 0; generation < 3; ++generation) {
            int[][] originals = new int[20000][];
            for (int i = 0; i < originals.length; ++i) {
                int[] a = originals[i] = new int[i % 65];
                for (int j = 0; j < a.length; ++j) a[j] = r.nextInt();
                require(c.addOrGet(a) == b.addOrGet(a), "new representative differs");
                require(c.addOrGet(a.clone()) == b.addOrGet(a.clone()), "equal representative differs");
                ++checks;
            }
            require(c.size() == b.size(), "size differs");
            require(Arrays.deepEquals(c.toArray(), b.toArray()), "table iteration changed");
            c.clear(); b.clear(); c.trim(); b.trim();
        }
        System.out.println("PASS exact_hash_and_canonical_checks=" + checks);
        if (args.length > 0 && args[0].equals("check-only")) return;
        // Two data domains, with equal warmed loop/checksum, C/B/B/C CPU blocks.
        for (String domain : new String[] {"random32", "quadlike32"}) {
            int[][] corpus = new int[32768][32];
            for (int[] a : corpus) for (int i = 0; i < a.length; ++i) {
                a[i] = domain.equals("random32") ? r.nextInt()
                    : switch (i % 8) {
                        case 0, 1, 2, 4, 5 -> Float.floatToRawIntBits(r.nextInt(33) / 16f);
                        case 3 -> -1;
                        case 6 -> 0;
                        default -> 0x7f0000;
                    };
            }
            for (int warm = 0; warm < 50; ++warm) { run(corpus, false, 8); run(corpus, true, 8); }
            for (int repeat = 0; repeat < 3; ++repeat) {
                for (boolean candidate : new boolean[] {false, true, true, false}) {
                    long start = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                    long sum = run(corpus, candidate, 512);
                    long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - start;
                    sink = sum;
                    System.out.printf("domain=%s repeat=%d candidate=%s calls=%d cpu_ns=%d checksum=%d%n",
                        domain, repeat, candidate, corpus.length * 512, cpu, sum);
                }
            }
        }
    }
    static long run(int[][] corpus, boolean candidate, int rounds) {
        long sum = 0;
        sink = 0;
        if (candidate) {
            for (int round = 0; round < rounds; ++round) {
                int offset = (int) sink & (corpus.length - 1);
                for (int i = 0; i < corpus.length; ++i)
                    sum = 31 * sum + grouped(corpus[(i + offset) & (corpus.length - 1)]);
                sink = sum;
            }
        } else {
            for (int round = 0; round < rounds; ++round) {
                int offset = (int) sink & (corpus.length - 1);
                for (int i = 0; i < corpus.length; ++i)
                    sum = 31 * sum + stock(corpus[(i + offset) & (corpus.length - 1)]);
                sink = sum;
            }
        }
        sink = sum;
        return sum;
    }
}
