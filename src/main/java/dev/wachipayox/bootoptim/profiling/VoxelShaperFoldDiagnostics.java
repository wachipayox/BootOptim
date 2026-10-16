package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.client.BlockShapeOrMemo;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * DIAGNOSTIC ONLY (never merged): a laboratory for the cost of Ponder's {@code VoxelShaper.rotatedCopy}
 * fold ({@code acc = Shapes.or(acc, box)}). Enabled with {@code -Dboot_optim.profileVoxelShaperFold=true}.
 * It never replaces a result.
 *
 * <p>For every call with at least two boxes, after stock has returned (and outside the stock timing),
 * the same recorded box sequence is rebuilt in-process with alternative constructions and each result is
 * compared exactly (concrete class, per-axis coordinate bits, ordered {@code toAabbs()} bits) with the
 * stock result, with wall time measured per construction:</p>
 * <ul>
 *   <li>{@code stock}: refold with {@code Shapes.or} (control; run twice to expose ordering/JIT bias);</li>
 *   <li>{@code split}: per step {@code joinUnoptimized} vs {@code optimize()} time on the real accumulators;</li>
 *   <li>{@code once}: chain of {@code joinUnoptimized(OR)}, a single {@code optimize()} at the end;</li>
 *   <li>{@code tree}: balanced pairwise {@code Shapes.or} (pairs of pairs).</li>
 * </ul>
 */
public final class VoxelShaperFoldDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileVoxelShaperFold");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    private static final String[] BUCKETS = {"2", "3-4", "5-8", "9-16", "17-32", "33+"};
    private static final int NV = 5;

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder SINGLE = new LongAdder();
    private static final LongAdder LAB_NS = new LongAdder();
    private static final LongAdder ORIG_WALL_NS = new LongAdder();
    private static final LongAdder STOCK_REPLAY_MISMATCH = new LongAdder();
    private static final LongAdder STEP_REPLAY_MISMATCH = new LongAdder();
    private static final LongAdder JOIN_NS = new LongAdder();
    private static final LongAdder OPT_NS = new LongAdder();
    private static final LongAdder FINAL_JOIN_NS = new LongAdder();
    private static final LongAdder FINAL_OPT_NS = new LongAdder();
    private static final LongAdder BOXSET_EQ = new LongAdder();
    private static final LongAdder BOXSET_EQ_ORDER = new LongAdder();
    private static final LongAdder BOXSET_NE = new LongAdder();
    private static final LongAdder SHAPE_ERR = new LongAdder();
    private static final long[][] NS = new long[BUCKETS.length][NV];
    private static final long[] CALLS_B = new long[BUCKETS.length];
    /** [bucket][0=once,1=tree] matches. */
    private static final long[][] MATCH = new long[BUCKETS.length][2];
    /** [bucket][0=once,1=tree] best stock lab ns minus candidate ns, summed over matching calls. */
    private static final long[][] SAVED_MATCH = new long[BUCKETS.length][2];
    private static final ConcurrentHashMap<String, long[]> BY_THREAD = new ConcurrentHashMap<>();
    private static volatile int examples;

    private VoxelShaperFoldDiagnostics() {
    }

    private static final class Session {
        final List<VoxelShape> boxes = new ArrayList<>();
        final List<VoxelShape> accs = new ArrayList<>();
        VoxelShape last = Shapes.empty();
        boolean off;
        long wall0;
        long origWall;
    }

    public static void callEnter(VoxelShape shape) {
        SESSION.set(new Session());
    }

    public static void orEnter(VoxelShape a, VoxelShape b) {
        Session s = SESSION.get();
        if (s == null || s.off) {
            return;
        }
        if (a != s.last) {
            s.off = true;
            return;
        }
        s.boxes.add(b);
        s.wall0 = System.nanoTime();
    }

    public static void orExit(VoxelShape result) {
        Session s = SESSION.get();
        if (s == null || s.off) {
            return;
        }
        s.origWall += System.nanoTime() - s.wall0;
        s.accs.add(result);
        s.last = result;
    }

    private static int bucket(int n) {
        return n <= 2 ? 0 : n <= 4 ? 1 : n <= 8 ? 2 : n <= 16 ? 3 : n <= 32 ? 4 : 5;
    }

    public static void callExit() {
        Session s = SESSION.get();
        if (s == null) {
            return;
        }
        SESSION.remove();
        CALLS.increment();
        int n = s.boxes.size();
        if (s.off || n < 2 || n != s.accs.size()) {
            SINGLE.increment();
            return;
        }
        long lab0 = System.nanoTime();
        try {
            lab(s, n);
        } catch (RuntimeException | StackOverflowError e) {
            SHAPE_ERR.increment();
        }
        LAB_NS.add(System.nanoTime() - lab0);
    }

    private static VoxelShape stockFold(List<VoxelShape> boxes) {
        VoxelShape acc = Shapes.empty();
        for (VoxelShape b : boxes) {
            acc = Shapes.or(acc, b);
        }
        return acc;
    }

    private static VoxelShape once(List<VoxelShape> boxes) {
        VoxelShape acc = Shapes.empty();
        for (VoxelShape b : boxes) {
            acc = Shapes.joinUnoptimized(acc, b, BooleanOp.OR);
        }
        return acc.optimize();
    }

    private static VoxelShape tree(List<VoxelShape> boxes) {
        List<VoxelShape> level = new ArrayList<>(boxes);
        while (level.size() > 1) {
            List<VoxelShape> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i + 1 < level.size(); i += 2) {
                next.add(Shapes.or(level.get(i), level.get(i + 1)));
            }
            if ((level.size() & 1) == 1) {
                next.add(level.get(level.size() - 1));
            }
            level = next;
        }
        return level.get(0);
    }

    private static void lab(Session s, int n) {
        int bk = bucket(n);
        VoxelShape stock = s.accs.get(n - 1);
        ORIG_WALL_NS.add(s.origWall);

        long t = System.nanoTime();
        VoxelShape r0 = stockFold(s.boxes);
        long tStock = System.nanoTime() - t;
        if (!BlockShapeOrMemo.sameShape(stock, r0)) {
            STOCK_REPLAY_MISMATCH.increment();
        }

        t = System.nanoTime();
        VoxelShape r1 = once(s.boxes);
        long tOnce = System.nanoTime() - t;

        t = System.nanoTime();
        VoxelShape r2 = tree(s.boxes);
        long tTree = System.nanoTime() - t;

        t = System.nanoTime();
        stockFold(s.boxes);
        long tStock2 = System.nanoTime() - t;

        long tJoin = 0;
        long tOpt = 0;
        VoxelShape acc = Shapes.empty();
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            VoxelShape j = Shapes.joinUnoptimized(acc, s.boxes.get(i), BooleanOp.OR);
            long b = System.nanoTime();
            VoxelShape o = j.optimize();
            long c = System.nanoTime();
            tJoin += b - a;
            tOpt += c - b;
            if (!BlockShapeOrMemo.sameShape(o, s.accs.get(i))) {
                STEP_REPLAY_MISMATCH.increment();
            }
            if (i == n - 1) {
                FINAL_JOIN_NS.add(b - a);
                FINAL_OPT_NS.add(c - b);
            }
            acc = s.accs.get(i);
        }
        JOIN_NS.add(tJoin);
        OPT_NS.add(tOpt);

        boolean m1 = BlockShapeOrMemo.sameShape(stock, r1);
        boolean m2 = BlockShapeOrMemo.sameShape(stock, r2);

        boxSets(s, stock);

        long ref = Math.min(tStock, tStock2);
        synchronized (NS) {
            CALLS_B[bk]++;
            NS[bk][0] += tStock;
            NS[bk][1] += tStock2;
            NS[bk][2] += tOnce;
            NS[bk][3] += tTree;
            NS[bk][4] += tJoin + tOpt;
            if (m1) {
                MATCH[bk][0]++;
                SAVED_MATCH[bk][0] += ref - tOnce;
            }
            if (m2) {
                MATCH[bk][1]++;
                SAVED_MATCH[bk][1] += ref - tTree;
            }
        }
        long[] th = BY_THREAD.computeIfAbsent(Thread.currentThread().getName(), k -> new long[8]);
        synchronized (th) {
            th[0]++;
            th[1] += s.origWall;
            th[2] += ref;
            th[3] += m1 ? ref - tOnce : 0;
            th[4] += m2 ? ref - tTree : 0;
            th[5] += tJoin;
            th[6] += tOpt;
            th[7] += (m1 ? 0 : 1) + (m2 ? 0 : 1) * 1000L;
        }
        if (!(m1 && m2) && examples < 3) {
            examples++;
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD example boxes={} once_match={} tree_match={} stock_boxes={} once_boxes={} tree_boxes={}",
                    n, m1, m2, stock.toAabbs().size(), r1.toAabbs().size(), r2.toAabbs().size());
        }
    }

    /** Does the stock result keep exactly the input boxes (as a set / in order)? Bears on any remap-by-rotation idea. */
    private static void boxSets(Session s, VoxelShape stock) {
        List<AABB> in = new ArrayList<>();
        for (VoxelShape b : s.boxes) {
            in.addAll(b.toAabbs());
        }
        List<AABB> out = stock.toAabbs();
        Set<List<Long>> a = new HashSet<>();
        Set<List<Long>> b = new HashSet<>();
        for (AABB x : in) {
            a.add(bits(x));
        }
        for (AABB x : out) {
            b.add(bits(x));
        }
        if (a.equals(b) && in.size() == out.size()) {
            BOXSET_EQ.increment();
            boolean order = true;
            for (int i = 0; i < in.size(); i++) {
                if (!bits(in.get(i)).equals(bits(out.get(i)))) {
                    order = false;
                    break;
                }
            }
            if (order) {
                BOXSET_EQ_ORDER.increment();
            }
        } else {
            BOXSET_NE.increment();
        }
    }

    private static List<Long> bits(AABB x) {
        return Arrays.asList(Double.doubleToLongBits(x.minX), Double.doubleToLongBits(x.minY), Double.doubleToLongBits(x.minZ),
                Double.doubleToLongBits(x.maxX), Double.doubleToLongBits(x.maxY), Double.doubleToLongBits(x.maxZ));
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.3f", ns / 1e6);
    }

    /** Called once before the main-menu marker. */
    public static void dump() {
        if (!ENABLED || CALLS.sum() == 0) {
            return;
        }
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD summary calls={} skipped_single_or_off={} lab_ms={} orig_fold_wall_ms={} "
                        + "stock_replay_mismatch={} step_replay_mismatch={} errors={} uptime_ms={}",
                CALLS.sum(), SINGLE.sum(), ms(LAB_NS.sum()), ms(ORIG_WALL_NS.sum()), STOCK_REPLAY_MISMATCH.sum(),
                STEP_REPLAY_MISMATCH.sum(), SHAPE_ERR.sum(), ManagementFactory.getRuntimeMXBean().getUptime());
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD split join_ms={} optimize_ms={} final_step_join_ms={} final_step_optimize_ms={}",
                ms(JOIN_NS.sum()), ms(OPT_NS.sum()), ms(FINAL_JOIN_NS.sum()), ms(FINAL_OPT_NS.sum()));
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD boxset stock_boxes_equal_input_set={} same_order={} different={}",
                BOXSET_EQ.sum(), BOXSET_EQ_ORDER.sum(), BOXSET_NE.sum());
        synchronized (NS) {
            for (int b = 0; b < BUCKETS.length; b++) {
                LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD bucket boxes={} calls={} stock_ms={} stock2_ms={} once_ms={} tree_ms={} split_sum_ms={} "
                                + "once_match={} once_saved_on_match_ms={} tree_match={} tree_saved_on_match_ms={}",
                        BUCKETS[b], CALLS_B[b], ms(NS[b][0]), ms(NS[b][1]), ms(NS[b][2]), ms(NS[b][3]), ms(NS[b][4]),
                        MATCH[b][0], ms(SAVED_MATCH[b][0]), MATCH[b][1], ms(SAVED_MATCH[b][1]));
            }
        }
        for (var e : new TreeMap<>(BY_THREAD).entrySet()) {
            long[] t = e.getValue();
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_FOLD thread={} calls={} orig_wall_ms={} stock_lab_ms={} once_saved_on_match_ms={} "
                            + "tree_saved_on_match_ms={} join_ms={} optimize_ms={} once_mismatches={} tree_mismatches={}",
                    e.getKey(), t[0], ms(t[1]), ms(t[2]), ms(t[3]), ms(t[4]), ms(t[5]), ms(t[6]), t[7] % 1000, t[7] / 1000);
        }
    }
}
