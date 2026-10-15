package dev.wachipayox.bootoptim.optimization.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * Replays the {@code Shapes.or} results of the vanilla static block-shape builders
 * ({@code WallBlock.makeShapes}, {@code CrossCollisionBlock.makeShapes}, {@code PipeBlock.makeShapes}).
 *
 * <p>Each builder is a pure function of its float parameters: vanilla hard-codes them in the
 * constructors, so every wall, fence, pane or pipe block of the pack repeats the same two or three
 * parameter tuples and with them about two thousand identical {@code joinUnoptimized} calls per block.
 * The first call per (builder, parameters) records the sequence of {@code Shapes.or} results; later
 * calls return the recorded shapes at the same call sites, so control flow, state-map construction
 * and every other call of the stock method run unchanged.</p>
 *
 * <p>Guards: the first replay for each (builder, parameters, block class) is verified against a fresh
 * stock evaluation (exact class, coordinate and box bits); with
 * {@code -Dboot_optim.blockShapeOrMemo.audit=true} every replay is verified. A mismatch or a
 * sequence-length difference disables the mechanism for the rest of the run and logs
 * {@code [WBOMBO-COMPAT] FALLBACK}. Kill switch: {@code -Dboot_optim.blockShapeOrMemo=false}.</p>
 */
public final class BlockShapeOrMemo {
    public static final String MECHANISM = "block-shape-or-memo";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("boot_optim.blockShapeOrMemo", "true"));
    private static final boolean AUDIT = Boolean.getBoolean("boot_optim.blockShapeOrMemo.audit");
    private static final int MAX_KEYS = 64;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ConcurrentHashMap<String, VoxelShape[]> MEMO = new ConcurrentHashMap<>();
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, Counters> COUNTERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();

    private BlockShapeOrMemo() {
    }

    private enum Mode { RECORD, REPLAY, VERIFY, OFF }

    private static final class Counters {
        final LongAdder calls = new LongAdder();
        final LongAdder replayed = new LongAdder();
        final LongAdder verified = new LongAdder();
        final LongAdder wallNs = new LongAdder();
    }

    private static final class Session {
        final String key;
        final String counterKey;
        final Mode mode;
        final VoxelShape[] entry;
        final List<VoxelShape> recorded;
        final long startNs = System.nanoTime();
        int cursor;
        boolean failed;

        Session(String key, String counterKey, Mode mode, VoxelShape[] entry) {
            this.key = key;
            this.counterKey = counterKey;
            this.mode = mode;
            this.entry = entry;
            this.recorded = mode == Mode.RECORD || mode == Mode.VERIFY ? new ArrayList<>() : null;
        }
    }

    /** Start of a builder call. {@code kind} names the builder; {@code params} are its exact float bits. */
    public static void enter(String kind, Object owner, int... params) {
        if (!ENABLED || DISABLED.get()) {
            SESSION.remove();
            return;
        }
        StringBuilder key = new StringBuilder(kind);
        for (int p : params) {
            key.append('|').append(p);
        }
        String k = key.toString();
        String verifyKey = k + "@" + owner.getClass().getName();
        VoxelShape[] entry = MEMO.get(k);
        Mode mode;
        if (entry == null) {
            mode = MEMO.size() >= MAX_KEYS ? Mode.OFF : Mode.RECORD;
        } else if (AUDIT || !VERIFIED.contains(verifyKey)) {
            mode = Mode.VERIFY;
        } else {
            mode = Mode.REPLAY;
        }
        SESSION.set(mode == Mode.OFF ? null : new Session(k, kind + "@" + owner.getClass().getName(), mode, entry));
        if (mode == Mode.OFF) {
            counters(kind + "@" + owner.getClass().getName()).calls.increment();
        }
    }

    /** Wraps one {@code Shapes.or} call site inside a covered builder. */
    public static VoxelShape or(VoxelShape a, VoxelShape b, Operation<VoxelShape> original) {
        Session s = SESSION.get();
        if (s == null || s.failed) {
            return original.call(a, b);
        }
        switch (s.mode) {
            case REPLAY -> {
                if (s.cursor < s.entry.length) {
                    return s.entry[s.cursor++];
                }
                fail(s, "replay_sequence_longer_than_recording");
                return original.call(a, b);
            }
            case VERIFY -> {
                VoxelShape result = original.call(a, b);
                s.recorded.add(result);
                if (s.cursor >= s.entry.length || !sameShape(result, s.entry[s.cursor])) {
                    fail(s, "verification_mismatch index=" + s.cursor);
                }
                s.cursor++;
                return result;
            }
            case RECORD -> {
                VoxelShape result = original.call(a, b);
                s.recorded.add(result);
                return result;
            }
            default -> {
                return original.call(a, b);
            }
        }
    }

    /** End of a builder call (normal return). */
    public static void exit() {
        Session s = SESSION.get();
        if (s == null) {
            return;
        }
        SESSION.remove();
        Counters c = counters(s.counterKey);
        c.calls.increment();
        c.wallNs.add(System.nanoTime() - s.startNs);
        if (s.failed) {
            return;
        }
        switch (s.mode) {
            case RECORD -> {
                if (!s.recorded.isEmpty()) {
                    MEMO.putIfAbsent(s.key, s.recorded.toArray(new VoxelShape[0]));
                    CompatLog.active(MECHANISM, null);
                }
            }
            case REPLAY -> {
                if (s.cursor != s.entry.length) {
                    fail(s, "replay_sequence_shorter_than_recording");
                } else {
                    c.replayed.increment();
                }
            }
            case VERIFY -> {
                if (s.cursor != s.entry.length) {
                    fail(s, "verification_length_mismatch");
                } else {
                    VERIFIED.add(s.key + "@" + s.counterKey.substring(s.counterKey.indexOf('@') + 1));
                    c.verified.increment();
                }
            }
            default -> {
            }
        }
    }

    /** Aborted call (exception): drop the session without recording anything. */
    public static void abort() {
        SESSION.remove();
    }

    private static void fail(Session s, String reason) {
        s.failed = true;
        if (DISABLED.compareAndSet(false, true)) {
            CompatLog.fallback(MECHANISM, null, reason + " key=" + s.key);
        }
    }

    private static Counters counters(String key) {
        return COUNTERS.computeIfAbsent(key, k -> new Counters());
    }

    /** One line per builder/class, logged before the main-menu marker. */
    public static void report() {
        if (COUNTERS.isEmpty()) {
            return;
        }
        long wallNs = 0;
        long calls = 0;
        for (var e : new java.util.TreeMap<>(COUNTERS).entrySet()) {
            Counters c = e.getValue();
            wallNs += c.wallNs.sum();
            calls += c.calls.sum();
            LOGGER.info("BOOTOPTIM_BLOCK_SHAPE_MEMO builder={} calls={} replayed={} verified={} wall_ms={}",
                    e.getKey(), c.calls.sum(), c.replayed.sum(), c.verified.sum(), String.format("%.3f", c.wallNs.sum() / 1e6));
        }
        LOGGER.info("BOOTOPTIM_BLOCK_SHAPE_MEMO total calls={} wall_ms={} enabled={} audit={} disabled={} memo_keys={} recorded_shapes={}",
                calls, String.format("%.3f", wallNs / 1e6), ENABLED, AUDIT, DISABLED.get(), MEMO.size(),
                MEMO.values().stream().mapToInt(a -> a.length).sum());
    }

    // ---- exact structural comparison (class, grids, boxes), never a hash ----

    static boolean sameShape(VoxelShape x, VoxelShape y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null || x.getClass() != y.getClass()) {
            return false;
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            DoubleList cx = x.getCoords(axis);
            DoubleList cy = y.getCoords(axis);
            if (cx.size() != cy.size()) {
                return false;
            }
            for (int i = 0; i < cx.size(); i++) {
                if (Double.doubleToLongBits(cx.getDouble(i)) != Double.doubleToLongBits(cy.getDouble(i))) {
                    return false;
                }
            }
        }
        List<AABB> bx = x.toAabbs();
        List<AABB> by = y.toAabbs();
        if (bx.size() != by.size()) {
            return false;
        }
        for (int i = 0; i < bx.size(); i++) {
            AABB p = bx.get(i);
            AABB q = by.get(i);
            if (Double.doubleToLongBits(p.minX) != Double.doubleToLongBits(q.minX)
                    || Double.doubleToLongBits(p.minY) != Double.doubleToLongBits(q.minY)
                    || Double.doubleToLongBits(p.minZ) != Double.doubleToLongBits(q.minZ)
                    || Double.doubleToLongBits(p.maxX) != Double.doubleToLongBits(q.maxX)
                    || Double.doubleToLongBits(p.maxY) != Double.doubleToLongBits(q.maxY)
                    || Double.doubleToLongBits(p.maxZ) != Double.doubleToLongBits(q.maxZ)) {
                return false;
            }
        }
        return true;
    }
}
