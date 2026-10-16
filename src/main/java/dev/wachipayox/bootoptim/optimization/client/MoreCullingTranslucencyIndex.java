package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Answers MoreCulling's {@code SpriteUtils.doesHaveTranslucency(image, null, x0, x1, y0, y1)} from a
 * per-image summed-area table of non-opaque pixels instead of rescanning the rectangle.
 *
 * <p>MoreCulling calls it for every quad of every block model when it resets its translucency
 * cache; most block textures are fully opaque, so each call scans the whole rectangle. Images are
 * the unmipmapped sprite images of the current atlas generation and are not mutated; the index is
 * keyed by image identity with weak keys, so a new generation builds new entries.</p>
 *
 * <p>Only the {@code orMatch == null} form is answered; the layered form stays stock. A runtime
 * differential guard compares against MoreCulling's own result (all warm-up calls, then a sample)
 * and falls back to stock for the rest of the session on any mismatch.</p>
 */
public final class MoreCullingTranslucencyIndex {
    public static final String PROPERTY = "boot_optim.moreCullingTranslucencyIndex";
    static final String MECHANISM = "moreculling-translucency-index";
    static final String MOD = "moreculling";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    /** Larger images keep the stock scan; the table would cost (w+1)(h+1) ints. */
    private static final int MAX_PIXELS = 512 * 512;
    private static final int WARMUP_SAMPLES = 2_048;
    private static final long SAMPLE_INTERVAL = 512L;
    private static final Map<NativeImage, int[]> TABLES = new WeakHashMap<>();
    private static final int[] FULLY_OPAQUE = new int[0];
    private static final AtomicInteger VERIFIED = new AtomicInteger();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final ThreadLocal<Integer> PENDING = new ThreadLocal<>();
    private static volatile boolean disabled = !ENABLED;

    private MoreCullingTranslucencyIndex() {}

    /**
     * At method HEAD. Returns the indexed answer to replace the stock call, or null to let
     * MoreCulling compute it (unsupported input, or this call is being verified).
     */
    public static Boolean replace(NativeImage image, Object orMatch, int x0, int x1, int y0, int y1) {
        PENDING.remove();
        if (disabled || orMatch != null || image == null) {
            return null;
        }
        Boolean answer = query(image, x0, x1, y0, y1);
        if (answer == null) {
            return null;
        }
        boolean verify = VERIFIED.get() < WARMUP_SAMPLES || CALLS.incrementAndGet() % SAMPLE_INTERVAL == 0;
        if (verify) {
            PENDING.set(answer ? 1 : 0);
            return null;
        }
        return answer;
    }

    /** At method RETURN of a call MoreCulling computed itself. */
    public static void verify(boolean stock) {
        Integer expected = PENDING.get();
        if (expected == null) {
            return;
        }
        PENDING.remove();
        if ((expected == 1) != stock) {
            disabled = true;
            synchronized (TABLES) {
                TABLES.clear();
            }
            CompatLog.fallback(MECHANISM, MOD, "result_mismatch stock=" + stock);
            return;
        }
        if (VERIFIED.incrementAndGet() == WARMUP_SAMPLES) {
            CompatLog.active(MECHANISM, MOD);
        }
    }

    static Boolean query(NativeImage image, int x0, int x1, int y0, int y1) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (x0 < 0 || y0 < 0 || x1 > width || y1 > height || x0 >= x1 || y0 >= y1) {
            return null;
        }
        if (!image.format().hasAlpha()) {
            return null;
        }
        int[] table;
        synchronized (TABLES) {
            table = TABLES.get(image);
        }
        if (table == null) {
            if ((long) width * height > MAX_PIXELS) {
                return null;
            }
            table = build(image, width, height);
            synchronized (TABLES) {
                TABLES.put(image, table);
            }
        }
        if (table == FULLY_OPAQUE) {
            return Boolean.FALSE;
        }
        int stride = width + 1;
        int count = table[y1 * stride + x1] - table[y0 * stride + x1] - table[y1 * stride + x0] + table[y0 * stride + x0];
        return count > 0;
    }

    /** Summed-area table of pixels whose alpha is not 255 (MoreCulling: getLuminanceOrAlpha != -1). */
    static int[] build(NativeImage image, int width, int height) {
        int stride = width + 1;
        int[] table = new int[stride * (height + 1)];
        boolean any = false;
        for (int y = 0; y < height; y++) {
            int row = 0;
            for (int x = 0; x < width; x++) {
                if (image.getLuminanceOrAlpha(x, y) != -1) {
                    row++;
                    any = true;
                }
                table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + row;
            }
        }
        return any ? table : FULLY_OPAQUE;
    }
}
