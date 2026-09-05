package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;

/**
 * Opt-in attribution for vanilla FilePackResources ZIP enumeration.
 *
 * <p>This is deliberately a diagnostic only. It records the wall time spent by
 * getNamespaces/listResources, which includes the ZIP enumeration and the
 * callback work performed by vanilla. It does not cache entries or change
 * resource ordering.</p>
 */
public final class FilePackResourcesProfiler {
    public static final String PROPERTY = "boot_optim.profileFilePackResources";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final ConcurrentHashMap<Key, Stats> STATS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private FilePackResourcesProfiler() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void begin(String operation, PackType packType, String packId) {
        if (!ENABLED) {
            return;
        }
        FRAMES.get().push(new Frame(operation, packType, packId, System.nanoTime()));
    }

    public static void finish(String operation, PackType packType, String packId, int resultCount) {
        if (!ENABLED) {
            return;
        }

        ArrayDeque<Frame> frames = FRAMES.get();
        Frame frame = frames.isEmpty() ? null : frames.pop();
        if (frame == null || !frame.operation.equals(operation)) {
            // Fail open if another transformer changes the call shape. Do not
            // let diagnostics affect resource loading.
            frames.clear();
            return;
        }

        long elapsed = Math.max(0L, System.nanoTime() - frame.startNanos);
        Key key = new Key(operation, packType == null ? "?" : packType.getDirectory(), packId);
        Stats stats = STATS.computeIfAbsent(key, ignored -> new Stats());
        stats.calls.increment();
        stats.wallNanos.add(elapsed);
        stats.resultItems.add(Math.max(0, resultCount));
    }

    /** Must run after the main-menu marker so report formatting is outside TTMM. */
    public static void reportAfterMainMenu() {
        if (!ENABLED || STATS.isEmpty()) {
            return;
        }

        List<Row> rows = new ArrayList<>(STATS.size());
        STATS.forEach((key, stats) -> rows.add(new Row(key, stats.calls.sum(), stats.wallNanos.sum(), stats.resultItems.sum())));
        rows.sort(Comparator.comparingLong(Row::wallNanos).reversed());
        LOGGER.info("BOOTOPTIM_FILEPACK_ENUM status=complete rows={}", rows.size());
        for (Row row : rows) {
            LOGGER.info(
                    "BOOTOPTIM_FILEPACK_ENUM operation={} pack_type={} pack={} calls={} wall_ms={} result_items={}",
                    row.key.operation,
                    row.key.packType,
                    row.key.packId,
                    row.calls,
                    String.format(java.util.Locale.ROOT, "%.3f", row.wallNanos / 1_000_000.0D),
                    row.resultItems);
        }
    }

    private record Frame(String operation, PackType packType, String packId, long startNanos) {
    }

    private record Key(String operation, String packType, String packId) {
    }

    private record Row(Key key, long calls, long wallNanos, long resultItems) {
    }

    private static final class Stats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder wallNanos = new LongAdder();
        private final LongAdder resultItems = new LongAdder();
    }
}
