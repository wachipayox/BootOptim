package dev.wachipayox.bootoptim.profiling;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * DIAGNOSTIC ONLY (never merged): counts {@code Shapes.joinUnoptimized} calls, how many repeat an
 * earlier call with structurally identical inputs, and the stock time they cost. Enabled with
 * {@code -Dboot_optim.profileShapeJoins=true}. It never replaces a result.
 *
 * <p>Join keys are a 128-bit digest of (boolean-op truth table, per-axis coordinate bits, ordered
 * {@code toAabbs()} bits of both inputs). {@code joinUnoptimized} is a pure function of those, so equal
 * keys mean an exact memo could have served the call. Key computation happens outside the timed
 * interval and is reported separately ({@code key_cost_ms}).</p>
 */
public final class ShapeJoinDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.profileShapeJoins");
    private static final int MAX_ITEMS = 512;
    private static final int SAMPLE_MASK = 31;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU = THREADS.isCurrentThreadCpuTimeSupported();

    private static final ConcurrentHashMap<Key, Long> SEEN = new ConcurrentHashMap<>(1 << 16);
    private static final ConcurrentHashMap<String, Stats> JOINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Stats> SAMPLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ScopeStats> SCOPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> UNSCOPED_BY_THREAD = new ConcurrentHashMap<>();
    private static final AtomicLong SCOPE_IDS = new AtomicLong(1);
    private static final LongAdder UNFINISHED = new LongAdder();
    private static final ThreadLocal<TState> STATE = ThreadLocal.withInitial(TState::new);

    private ShapeJoinDiagnostics() {
    }

    private record Key(long a, long b) {
    }

    private record IdPair(VoxelShape a, VoxelShape b, int op) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IdPair p && p.a == a && p.b == b && p.op == op;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(a) * 31 + System.identityHashCode(b) * 7 + op;
        }
    }

    private static final class TState {
        String kind;
        String owner;
        long scopeId;
        long wallStart;
        long cpuStart;
        long diagNs;
        long joinCount;
        boolean pending;
        long joinStart;
        boolean dup;
        boolean dupSame;
        boolean sampled;
        boolean keyed;
        String label;
        String bucket;
        final Set<IdPair> identity = new HashSet<>();
    }

    private static final class Stats {
        final LongAdder joins = new LongAdder();
        final LongAdder dupSame = new LongAdder();
        final LongAdder dupOther = new LongAdder();
        final LongAdder identityDup = new LongAdder();
        final LongAdder elapsedNs = new LongAdder();
        final LongAdder dupElapsedNs = new LongAdder();
        final LongAdder keyCostNs = new LongAdder();
        final LongAdder oversize = new LongAdder();
    }

    private static final class ScopeStats {
        final LongAdder calls = new LongAdder();
        final LongAdder grossWallNs = new LongAdder();
        final LongAdder grossCpuNs = new LongAdder();
        final LongAdder diagNs = new LongAdder();
        final LongAdder shapesReturned = new LongAdder();
        final Set<Long> params = ConcurrentHashMap.newKeySet();
        final Set<Long> results = ConcurrentHashMap.newKeySet();
        volatile long firstUptimeMs = Long.MAX_VALUE;
        volatile long lastUptimeMs;
        volatile String thread = "";
    }

    // ---- scopes (block shape builders) ----

    public static void scopeEnter(String kind, Object owner, long paramBits) {
        TState s = STATE.get();
        s.kind = kind;
        s.owner = owner.getClass().getName();
        s.scopeId = SCOPE_IDS.getAndIncrement();
        s.diagNs = 0;
        s.identity.clear();
        SCOPES.computeIfAbsent(kind + "|" + s.owner, k -> new ScopeStats()).params.add(paramBits);
        s.cpuStart = CPU ? THREADS.getCurrentThreadCpuTime() : 0L;
        s.wallStart = System.nanoTime();
    }

    public static void scopeExit(Collection<VoxelShape> returned) {
        TState s = STATE.get();
        if (s.kind == null) {
            return;
        }
        long wall = System.nanoTime() - s.wallStart;
        long cpu = CPU ? THREADS.getCurrentThreadCpuTime() - s.cpuStart : 0L;
        long diagStart = System.nanoTime();
        ScopeStats st = SCOPES.computeIfAbsent(s.kind + "|" + s.owner, k -> new ScopeStats());
        st.calls.increment();
        st.grossWallNs.add(wall);
        st.grossCpuNs.add(cpu);
        long[] h = new long[] {0x1234567L, 0x7654321L};
        int n = 0;
        for (VoxelShape shape : returned) {
            n++;
            if (!sig(shape, h)) {
                mix(h, -1L);
            }
        }
        st.shapesReturned.add(n);
        st.results.add(h[0] ^ Long.rotateLeft(h[1], 17));
        long up = ManagementFactory.getRuntimeMXBean().getUptime();
        if (up < st.firstUptimeMs) {
            st.firstUptimeMs = up;
        }
        st.lastUptimeMs = up;
        st.thread = Thread.currentThread().getName();
        st.diagNs.add(s.diagNs + (System.nanoTime() - diagStart));
        s.kind = null;
    }

    // ---- joins ----

    public static void joinEnter(VoxelShape a, VoxelShape b, BooleanOp op) {
        TState s = STATE.get();
        if (s.pending) {
            UNFINISHED.increment();
        }
        long k0 = System.nanoTime();
        s.joinCount++;
        s.sampled = false;
        s.label = null;
        int opBits = (op.apply(false, true) ? 1 : 0) | (op.apply(true, false) ? 2 : 0) | (op.apply(true, true) ? 4 : 0)
                | (op.apply(false, false) ? 8 : 0);
        long[] h = new long[] {0x9E3779B1L + opBits, 0x85EBCA6BL ^ opBits};
        boolean ok = sig(a, h) && sig(b, h);
        s.keyed = ok;
        s.dup = false;
        s.dupSame = false;
        if (s.kind != null) {
            s.bucket = s.kind;
        } else {
            s.bucket = "unscoped";
            UNSCOPED_BY_THREAD.computeIfAbsent(Thread.currentThread().getName(), t -> new LongAdder()).increment();
            if ((s.joinCount & SAMPLE_MASK) == 0) {
                s.sampled = true;
                s.label = classify();
            }
        }
        if (ok) {
            Key key = new Key(h[0], h[1]);
            long scope = s.kind != null ? s.scopeId : -Thread.currentThread().threadId();
            Long prev = SEEN.putIfAbsent(key, scope);
            if (prev != null) {
                s.dup = true;
                s.dupSame = prev == scope;
            }
        }
        Stats bucket = JOINS.computeIfAbsent(s.bucket, x -> new Stats());
        if (s.kind != null && !s.identity.add(new IdPair(a, b, opBits))) {
            bucket.identityDup.increment();
        }
        long cost = System.nanoTime() - k0;
        s.diagNs += cost;
        bucket.keyCostNs.add(cost);
        s.pending = true;
        s.joinStart = System.nanoTime();
    }

    public static void joinExit() {
        TState s = STATE.get();
        if (!s.pending) {
            return;
        }
        long elapsed = System.nanoTime() - s.joinStart;
        s.pending = false;
        record(JOINS.computeIfAbsent(s.bucket, x -> new Stats()), s, elapsed);
        if (s.sampled && s.label != null) {
            record(SAMPLED.computeIfAbsent(s.label, x -> new Stats()), s, elapsed);
        }
    }

    private static void record(Stats st, TState s, long elapsed) {
        st.joins.increment();
        st.elapsedNs.add(elapsed);
        if (!s.keyed) {
            st.oversize.increment();
        } else if (s.dup) {
            (s.dupSame ? st.dupSame : st.dupOther).increment();
            st.dupElapsedNs.add(elapsed);
        }
    }

    private static String classify() {
        List<String> frames = StackWalker.getInstance().walk(stream -> stream.limit(48)
                .map(f -> f.getClassName() + "." + f.getMethodName()).toList());
        for (String f : frames) {
            if (f.startsWith("net.createmod.catnip.math.VoxelShaper")) {
                return "VoxelShaper";
            }
        }
        for (String f : frames) {
            if (f.startsWith("net.minecraft.world.phys.shapes.") || f.startsWith("net.caffeinemc.") || f.startsWith("it.unimi.")
                    || f.startsWith("java.") || f.startsWith("jdk.") || f.startsWith("dev.wachipayox.")) {
                continue;
            }
            return f;
        }
        return "?";
    }

    // ---- structural digest ----

    private static void mix(long[] h, long v) {
        h[0] = (h[0] ^ v) * 0x9E3779B97F4A7C15L;
        h[0] ^= h[0] >>> 29;
        h[1] = (h[1] + v) * 0xC2B2AE3D27D4EB4FL;
        h[1] ^= h[1] >>> 31;
    }

    private static boolean sig(VoxelShape shape, long[] h) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList coords = shape.getCoords(axis);
            int n = coords.size();
            if (n > MAX_ITEMS) {
                return false;
            }
            mix(h, n);
            for (int i = 0; i < n; i++) {
                mix(h, Double.doubleToLongBits(coords.getDouble(i)));
            }
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() > MAX_ITEMS) {
            return false;
        }
        mix(h, boxes.size());
        for (AABB b : boxes) {
            mix(h, Double.doubleToLongBits(b.minX));
            mix(h, Double.doubleToLongBits(b.minY));
            mix(h, Double.doubleToLongBits(b.minZ));
            mix(h, Double.doubleToLongBits(b.maxX));
            mix(h, Double.doubleToLongBits(b.maxY));
            mix(h, Double.doubleToLongBits(b.maxZ));
        }
        return true;
    }

    // ---- report ----

    public static void dump() {
        if (!ENABLED) {
            return;
        }
        long total = 0;
        long totalDup = 0;
        for (Map.Entry<String, Stats> e : new TreeMap<>(JOINS).entrySet()) {
            Stats s = e.getValue();
            total += s.joins.sum();
            totalDup += s.dupSame.sum() + s.dupOther.sum();
            LOGGER.info("BOOTOPTIM_JOIN_DIAG category={} joins={} dup_same_scope={} dup_other_scope={} identity_dup_in_scope={} "
                            + "elapsed_ms={} dup_elapsed_ms={} key_cost_ms={} oversize={}",
                    e.getKey(), s.joins.sum(), s.dupSame.sum(), s.dupOther.sum(), s.identityDup.sum(),
                    ms(s.elapsedNs.sum()), ms(s.dupElapsedNs.sum()), ms(s.keyCostNs.sum()), s.oversize.sum());
        }
        LOGGER.info("BOOTOPTIM_JOIN_DIAG category=ALL joins={} dup={} distinct_keys={} unfinished={} cpu_time_supported={}",
                total, totalDup, SEEN.size(), UNFINISHED.sum(), CPU);
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(UNSCOPED_BY_THREAD).entrySet()) {
            LOGGER.info("BOOTOPTIM_JOIN_THREAD thread={} unscoped_joins={}", e.getKey(), e.getValue().sum());
        }
        List<Map.Entry<String, ScopeStats>> scopes = new ArrayList<>(SCOPES.entrySet());
        scopes.sort((x, y) -> Long.compare(y.getValue().grossWallNs.sum(), x.getValue().grossWallNs.sum()));
        int shownScopes = 0;
        for (Map.Entry<String, ScopeStats> e : scopes) {
            if (shownScopes++ >= 60) {
                break;
            }
            ScopeStats s = e.getValue();
            LOGGER.info("BOOTOPTIM_JOIN_SCOPE scope={} calls={} distinct_params={} distinct_results={} shapes={} gross_wall_ms={} "
                            + "gross_cpu_ms={} diag_ms={} net_wall_ms={} net_cpu_ms={} first_uptime_ms={} last_uptime_ms={} thread={}",
                    e.getKey(), s.calls.sum(), s.params.size(), s.results.size(), s.shapesReturned.sum(),
                    ms(s.grossWallNs.sum()), ms(s.grossCpuNs.sum()), ms(s.diagNs.sum()),
                    ms(s.grossWallNs.sum() - s.diagNs.sum()), ms(s.grossCpuNs.sum() - s.diagNs.sum()),
                    s.firstUptimeMs, s.lastUptimeMs, s.thread);
        }
        List<Map.Entry<String, Stats>> sampled = new ArrayList<>(SAMPLED.entrySet());
        sampled.sort((x, y) -> Long.compare(y.getValue().elapsedNs.sum(), x.getValue().elapsedNs.sum()));
        int shown = 0;
        for (Map.Entry<String, Stats> e : sampled) {
            if (shown++ >= 40) {
                break;
            }
            Stats s = e.getValue();
            LOGGER.info("BOOTOPTIM_JOIN_SAMPLED_CALLER caller={} sampled_joins={} dup={} elapsed_ms={} dup_elapsed_ms={}",
                    e.getKey(), s.joins.sum(), s.dupSame.sum() + s.dupOther.sum(), ms(s.elapsedNs.sum()), ms(s.dupElapsedNs.sum()));
        }
        LOGGER.info("BOOTOPTIM_JOIN_DIAG interpretation: elapsed/dup_elapsed are stock joinUnoptimized wall (key cost excluded, task-sum "
                + "across threads, not critical path); scoped categories are exact, unscoped callers are a 1-in-32 per-thread systematic sample.");
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0);
    }
}
