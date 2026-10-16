package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Memoizes {@code getShape} of third-party blocks whose source computes the shape from the block
 * state alone, rebuilding it on every call (Create Deco {@code CatwalkRailingBlock}: up to four
 * {@code Shapes.join}; CreateFood {@code PizzaBlock}: a rotation of a static per-bite shape).
 * MoreCulling asks every state for its occlusion shape on each resource reload, and the game asks
 * again for outlines and collisions, so the same shapes are rebuilt repeatedly.
 *
 * <p>Key: the interned {@link BlockState}; value: the shape stock returned for it. Level, position
 * and collision context are ignored by those methods (checked in their source); the guard below
 * covers mods that change that. Differential guard: the first {@value #WARMUP} hits per block
 * class and one in {@value #SAMPLE} afterwards re-run the original method and compare the shapes
 * exactly ({@link BlockShapeOrMemo#sameShape}); a mismatch disables the memo for that mechanism
 * and logs {@code [WBOMBO-COMPAT] FALLBACK}. Kill switch: {@code -Dboot_optim.stateShapeMemo=false}.</p>
 */
public final class StateShapeMemo {
    public static final String PROPERTY = "boot_optim.stateShapeMemo";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final int WARMUP = 256;
    private static final long SAMPLE = 1_024L;

    public static final StateShapeMemo CATWALK_RAILING = new StateShapeMemo("createdeco-catwalk-railing-shape-memo", "createdeco");
    public static final StateShapeMemo PIZZA = new StateShapeMemo("createfood-pizza-shape-memo", "createfood");

    private final String mechanism;
    private final String modId;
    private final ConcurrentHashMap<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong verified = new AtomicLong();
    private final ThreadLocal<VoxelShape> pendingCheck = new ThreadLocal<>();
    private volatile boolean disabled = !ENABLED;

    private StateShapeMemo(String mechanism, String modId) {
        this.mechanism = mechanism;
        this.modId = modId;
    }

    /** At method HEAD: the memoized shape to return, or null to run the original method. */
    public VoxelShape lookup(BlockState state) {
        pendingCheck.remove();
        if (disabled || state == null) {
            return null;
        }
        VoxelShape cached = shapes.get(state);
        if (cached == null) {
            return null;
        }
        long n = hits.incrementAndGet();
        if (verified.get() < WARMUP || n % SAMPLE == 0) {
            pendingCheck.set(cached);
            return null;
        }
        return cached;
    }

    /** At method RETURN of a call the original method computed. */
    public void record(BlockState state, VoxelShape stock) {
        VoxelShape expected = pendingCheck.get();
        if (expected != null) {
            pendingCheck.remove();
            if (!BlockShapeOrMemo.sameShape(expected, stock)) {
                disabled = true;
                shapes.clear();
                CompatLog.fallback(mechanism, modId, "shape_mismatch state=" + state);
                return;
            }
            if (verified.incrementAndGet() == WARMUP) {
                CompatLog.active(mechanism, modId);
            }
            return;
        }
        if (!disabled && state != null && stock != null) {
            shapes.putIfAbsent(state, stock);
        }
    }
}
