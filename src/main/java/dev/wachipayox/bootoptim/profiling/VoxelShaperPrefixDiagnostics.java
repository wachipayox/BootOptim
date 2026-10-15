package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.client.BlockShapeOrMemo;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * DIAGNOSTIC ONLY (never merged): measures how much of Ponder's {@code VoxelShaper.rotatedCopy}
 * fold ({@code result = Shapes.or(result, rotatedBox)}) repeats an earlier fold step with the same
 * <em>lineage</em>: the same ordered sequence of exact rotated boxes since the empty shape.
 * Enabled with {@code -Dboot_optim.profileVoxelShaperPrefix=true}. It never replaces a result.
 *
 * <p>A fold step is a pure function of (accumulator, box). With equal ordered box prefixes the
 * accumulators are equal by induction, so an exact trie keyed by the box structure identifies every
 * repeat that a replay could serve. Per repeat the stock step time is recorded, bucketed by
 * occurrence index (the 2nd occurrence would be the verification run of a replay design) and by
 * whether the step is the final one of its call (an externally returned shape, which a replay design
 * would not alias between blocks). Key construction happens outside the timed interval.</p>
 */
public final class VoxelShaperPrefixDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileVoxelShaperPrefix");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU = THREADS.isCurrentThreadCpuTimeSupported();

    private static final Node ROOT = new Node();
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    private static final AtomicInteger NODES = new AtomicInteger();

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder FOLDS = new LongAdder();
    private static final LongAdder BROKEN_CHAINS = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static final LongAdder KEY_NS = new LongAdder();
    private static final LongAdder CALL_WALL_NS = new LongAdder();
    private static final LongAdder FOLD_WALL_NS = new LongAdder();
    private static final LongAdder FOLD_CPU_NS = new LongAdder();
    /** all occ>=2, all occ>=3, interior occ>=2, interior occ>=3. */
    private static final LongAdder[] REPEAT_WALL_NS = adders(4);
    private static final LongAdder[] REPEAT_CPU_NS = adders(4);
    private static final LongAdder[] REPEAT_COUNT = adders(4);
    private static final LongAdder FINAL_FOLD_WALL_NS = new LongAdder();
    private static final LongAdder WHOLE_CALL_REPEATS = new LongAdder();
    private static final ConcurrentHashMap<String, long[]> BY_THREAD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder> BOX_HISTOGRAM = new ConcurrentHashMap<>();
    private static volatile long firstUptimeMs = Long.MAX_VALUE;
    private static volatile long lastUptimeMs;

    private VoxelShaperPrefixDiagnostics() {
    }

    private static LongAdder[] adders(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) {
            a[i] = new LongAdder();
        }
        return a;
    }

    private static final class Node {
        final ConcurrentHashMap<BoxKey, Node> children = new ConcurrentHashMap<>();
        final AtomicInteger visits = new AtomicInteger();
        volatile VoxelShape first;
    }

    private static final class BoxKey {
        final Class<?> type;
        final long[] bits;
        final int hash;

        BoxKey(Class<?> type, long[] bits) {
            this.type = type;
            this.bits = bits;
            this.hash = Arrays.hashCode(bits) * 31 + type.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BoxKey k && k.type == type && Arrays.equals(k.bits, bits);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Session {
        Node node = ROOT;
        VoxelShape last = Shapes.empty();
        int step;
        int total;
        boolean off;
        BoxKey pendingKey;
        long wall0;
        long cpu0;
        final long callStart = System.nanoTime();
    }

    public static void callEnter(VoxelShape shape) {
        Session s = new Session();
        try {
            s.total = shape.toAabbs().size();
        } catch (RuntimeException e) {
            s.off = true;
        }
        CALLS.increment();
        SESSION.set(s);
    }

    public static void callExit() {
        Session s = SESSION.get();
        if (s == null) {
            return;
        }
        SESSION.remove();
        CALL_WALL_NS.add(System.nanoTime() - s.callStart);
        if (!s.off && s.step > 0) {
            BOX_HISTOGRAM.computeIfAbsent(s.step, k -> new LongAdder()).increment();
            if (s.node.visits.get() >= 2) {
                WHOLE_CALL_REPEATS.increment();
            }
        }
    }

    public static void orEnter(VoxelShape a, VoxelShape b) {
        Session s = SESSION.get();
        if (s == null || s.off) {
            return;
        }
        if (a != s.last) {
            s.off = true;
            BROKEN_CHAINS.increment();
            return;
        }
        long k0 = System.nanoTime();
        s.pendingKey = key(b);
        KEY_NS.add(System.nanoTime() - k0);
        s.cpu0 = CPU ? THREADS.getCurrentThreadCpuTime() : 0L;
        s.wall0 = System.nanoTime();
    }

    public static void orExit(VoxelShape result) {
        Session s = SESSION.get();
        if (s == null || s.off || s.pendingKey == null) {
            return;
        }
        long wall = System.nanoTime() - s.wall0;
        long cpu = CPU ? THREADS.getCurrentThreadCpuTime() - s.cpu0 : 0L;
        BoxKey key = s.pendingKey;
        s.pendingKey = null;
        Node child = s.node.children.computeIfAbsent(key, k -> {
            NODES.incrementAndGet();
            return new Node();
        });
        int occ = child.visits.incrementAndGet();
        s.step++;
        boolean fin = s.step == s.total;
        FOLDS.increment();
        FOLD_WALL_NS.add(wall);
        FOLD_CPU_NS.add(cpu);
        if (fin) {
            FINAL_FOLD_WALL_NS.add(wall);
        }
        if (occ == 1) {
            child.first = result;
        } else {
            for (int i = 0; i < 4; i++) {
                boolean interiorOnly = i >= 2;
                int minOcc = (i & 1) == 0 ? 2 : 3;
                if (occ >= minOcc && !(interiorOnly && fin)) {
                    REPEAT_WALL_NS[i].add(wall);
                    REPEAT_CPU_NS[i].add(cpu);
                    REPEAT_COUNT[i].increment();
                }
            }
            if (occ == 2 && !BlockShapeOrMemo.sameShape(child.first, result)) {
                MISMATCHES.increment();
            }
        }
        long[] t = BY_THREAD.computeIfAbsent(Thread.currentThread().getName(), n -> new long[3]);
        synchronized (t) {
            t[0]++;
            t[1] += wall;
            t[2] += cpu;
        }
        long up = ManagementFactory.getRuntimeMXBean().getUptime();
        if (up < firstUptimeMs) {
            firstUptimeMs = up;
        }
        if (up > lastUptimeMs) {
            lastUptimeMs = up;
        }
        s.node = child;
        s.last = result;
    }

    private static BoxKey key(VoxelShape b) {
        List<AABB> boxes = b.toAabbs();
        long[] bits = new long[64 + boxes.size() * 6];
        int n = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList c = b.getCoords(axis);
            if (n + 1 + c.size() > bits.length) {
                bits = Arrays.copyOf(bits, bits.length + c.size() + 8);
            }
            bits[n++] = c.size();
            for (int i = 0; i < c.size(); i++) {
                bits[n++] = Double.doubleToLongBits(c.getDouble(i));
            }
        }
        if (n + 1 + boxes.size() * 6 > bits.length) {
            bits = Arrays.copyOf(bits, n + 1 + boxes.size() * 6);
        }
        bits[n++] = boxes.size();
        for (AABB x : boxes) {
            bits[n++] = Double.doubleToLongBits(x.minX);
            bits[n++] = Double.doubleToLongBits(x.minY);
            bits[n++] = Double.doubleToLongBits(x.minZ);
            bits[n++] = Double.doubleToLongBits(x.maxX);
            bits[n++] = Double.doubleToLongBits(x.maxY);
            bits[n++] = Double.doubleToLongBits(x.maxZ);
        }
        return new BoxKey(b.getClass(), Arrays.copyOf(bits, n));
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.3f", ns / 1e6);
    }

    /** Called once before the main-menu marker. */
    public static void dump() {
        if (!ENABLED || FOLDS.sum() == 0) {
            return;
        }
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_PREFIX summary calls={} folds={} nodes={} chain_breaks={} output_mismatches={} "
                        + "call_wall_ms={} fold_wall_ms={} fold_cpu_ms={} final_step_wall_ms={} key_ms={} whole_call_repeats={} "
                        + "first_uptime_ms={} last_uptime_ms={}",
                CALLS.sum(), FOLDS.sum(), NODES.get(), BROKEN_CHAINS.sum(), MISMATCHES.sum(),
                ms(CALL_WALL_NS.sum()), ms(FOLD_WALL_NS.sum()), ms(FOLD_CPU_NS.sum()), ms(FINAL_FOLD_WALL_NS.sum()),
                ms(KEY_NS.sum()), WHOLE_CALL_REPEATS.sum(), firstUptimeMs, lastUptimeMs);
        String[] names = {"all_occ2plus", "all_occ3plus", "interior_occ2plus", "interior_occ3plus"};
        for (int i = 0; i < 4; i++) {
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_PREFIX ceiling bucket={} steps={} wall_ms={} cpu_ms={}",
                    names[i], REPEAT_COUNT[i].sum(), ms(REPEAT_WALL_NS[i].sum()), ms(REPEAT_CPU_NS[i].sum()));
        }
        for (var e : new TreeMap<>(BY_THREAD).entrySet()) {
            long[] t = e.getValue();
            LOGGER.info("BOOTOPTIM_VOXELSHAPER_PREFIX thread={} folds={} wall_ms={} cpu_ms={}",
                    e.getKey(), t[0], ms(t[1]), ms(t[2]));
        }
        StringBuilder h = new StringBuilder();
        for (var e : new TreeMap<>(BOX_HISTOGRAM).entrySet()) {
            h.append(e.getKey()).append(':').append(e.getValue().sum()).append(' ');
        }
        LOGGER.info("BOOTOPTIM_VOXELSHAPER_PREFIX boxes_per_call_histogram {}", h.toString().trim());
    }
}
