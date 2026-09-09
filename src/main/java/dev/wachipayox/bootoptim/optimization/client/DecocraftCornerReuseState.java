package dev.wachipayox.bootoptim.optimization.client;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reload-call-local state for the Decocraft 3.0.11 element-corner common-subexpression experiment.
 *
 * <p>No Decocraft types appear here so the normal ModelBakery lifecycle can report/reset this state
 * without turning the optional mod into a runtime linkage dependency.</p>
 */
public final class DecocraftCornerReuseState {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftCornerReuse");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.experimentalDecocraftCornerReuse");
    private static final boolean PROFILE = Boolean.getBoolean("boot_optim.profileDecocraftCornerReuse");
    private static final String SUPPORTED_VERSION = "3.0.11";
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static final LongAdder MODELS = new LongAdder();
    private static final LongAdder ELEMENTS = new LongAdder();
    private static final LongAdder PREPARE_CALLS = new LongAdder();
    private static final LongAdder COMMIT_HITS = new LongAdder();
    private static final LongAdder OVERFLOW_MISSES = new LongAdder();
    private static volatile int supportState;

    private DecocraftCornerReuseState() {}

    public static void beginModel() {
        if (!ENABLED || !supportedRuntime()) {
            CONTEXT.remove();
            return;
        }
        CONTEXT.set(new Context());
    }

    public static Context current() {
        return ENABLED ? CONTEXT.get() : null;
    }

    public static void endModel() {
        Context context = CONTEXT.get();
        CONTEXT.remove();
        if (context == null) return;
        MODELS.increment();
        ELEMENTS.add(context.elements);
        PREPARE_CALLS.add(context.prepareCalls);
        COMMIT_HITS.add(context.commitHits);
        OVERFLOW_MISSES.add(context.overflowMisses);
    }

    public static void finishModelBake() {
        CONTEXT.remove();
        if (!PROFILE) return;
        long models = MODELS.sumThenReset();
        long elements = ELEMENTS.sumThenReset();
        long prepare = PREPARE_CALLS.sumThenReset();
        long commit = COMMIT_HITS.sumThenReset();
        long overflow = OVERFLOW_MISSES.sumThenReset();
        if (models == 0L && prepare == 0L && commit == 0L) return;
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_CORNER_REUSE status={} version={} models={} elements={} prepare_calls={} commit_calls={} overflow_misses={} avoided_stock_rotations={} reuse_pct={}",
                ENABLED ? "enabled" : "disabled",
                supportedVersion(), models, elements, prepare, commit, overflow, commit,
                pct(commit, prepare + commit));
    }

    private static boolean supportedRuntime() {
        int state = supportState;
        if (state != 0) return state > 0;
        synchronized (DecocraftCornerReuseState.class) {
            if (supportState != 0) return supportState > 0;
            supportState = SUPPORTED_VERSION.equals(supportedVersion()) ? 1 : -1;
            return supportState > 0;
        }
    }

    private static String supportedVersion() {
        try {
            var container = ModList.get().getModContainerById("decocraft");
            if (container.isEmpty()) return "missing";
            return String.valueOf(container.get().getModInfo().getVersion());
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String pct(long numerator, long denominator) {
        if (denominator == 0L) return "0.000";
        return String.format(Locale.ROOT, "%.3f", numerator * 100.0D / denominator);
    }

    /** One streaming element at a time: buildQuads visits all faces of an element contiguously. */
    public static final class Context {
        private static final int MAX_CORNERS = 8;
        private Object currentElement;
        private final int[] inputBits = new int[MAX_CORNERS * 3];
        private final float[] output = new float[MAX_CORNERS * 3];
        private int cornerCount;
        private boolean pendingMiss;
        private int pendingX;
        private int pendingY;
        private int pendingZ;
        long elements;
        long prepareCalls;
        long commitHits;
        long overflowMisses;

        /** Returns cached output offset, or -1 when stock applyElementRotation must execute. */
        public int lookup(Object element, float x, float y, float z) {
            if (currentElement != element) {
                currentElement = element;
                cornerCount = 0;
                pendingMiss = false;
                elements++;
            }
            int xb = Float.floatToRawIntBits(x);
            int yb = Float.floatToRawIntBits(y);
            int zb = Float.floatToRawIntBits(z);
            for (int corner = 0; corner < cornerCount; corner++) {
                int offset = corner * 3;
                if (inputBits[offset] == xb && inputBits[offset + 1] == yb && inputBits[offset + 2] == zb) {
                    pendingMiss = false;
                    commitHits++;
                    return offset;
                }
            }
            pendingMiss = cornerCount < MAX_CORNERS;
            pendingX = xb;
            pendingY = yb;
            pendingZ = zb;
            if (!pendingMiss) overflowMisses++;
            return -1;
        }

        /** Records the exact stock output bits from the first occurrence of a cube corner. */
        public void recordStock(Object element, float x, float y, float z) {
            if (!pendingMiss || currentElement != element) return;
            int offset = cornerCount * 3;
            inputBits[offset] = pendingX;
            inputBits[offset + 1] = pendingY;
            inputBits[offset + 2] = pendingZ;
            output[offset] = x;
            output[offset + 1] = y;
            output[offset + 2] = z;
            cornerCount++;
            prepareCalls++;
            pendingMiss = false;
        }

        public float outputX(int offset) { return output[offset]; }
        public float outputY(int offset) { return output[offset + 1]; }
        public float outputZ(int offset) { return output[offset + 2]; }
    }
}
