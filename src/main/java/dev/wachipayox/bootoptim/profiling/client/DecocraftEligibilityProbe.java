package dev.wachipayox.bootoptim.profiling.client;

import dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge;
import java.util.Locale;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only eligibility probe for Decocraft 3.0.11 first/base geometry.
 *
 * <p>The probe never prepares geometry. It only records when the naturally-created BBGeometry
 * graph exists, when the stock block-model future declares the full source set ready, and when
 * each BBGeometry is first consumed by the stock bake path. All observations are scoped to the
 * first resource-reload generation and emitted into the #214 structured DAG.</p>
 */
public final class DecocraftEligibilityProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftEligibility");
    private static final String PROFILE_PROPERTY = "boot_optim.profileDecocraftEligibility";
    private static final String SUPPORTED_VERSION = "3.0.11";
    private static final Object LOCK = new Object();

    private static long generation;
    private static boolean active;
    private static String version = "unknown";
    private static int availableCount;
    private static int availableAtSourceSetReady;
    private static int availableAtFirstConsume;
    private static int baseConsumedCount;
    private static long firstAvailableNanos;
    private static long lastAvailableNanos;
    private static long sourceSetReadyNanos;
    private static long firstConsumeNanos;
    private static String firstAvailableThread = "none";
    private static String lastAvailableThread = "none";
    private static String sourceSetThread = "none";
    private static String firstConsumeThread = "none";

    private DecocraftEligibilityProbe() {
    }

    public static void beginGeneration(long reloadGeneration) {
        if (!Boolean.getBoolean(PROFILE_PROPERTY) || reloadGeneration != 1L) {
            return;
        }

        String detected = detectVersion();
        boolean supported = SUPPORTED_VERSION.equals(detected);
        synchronized (LOCK) {
            generation = reloadGeneration;
            active = supported;
            version = detected;
            availableCount = 0;
            availableAtSourceSetReady = 0;
            availableAtFirstConsume = 0;
            baseConsumedCount = 0;
            firstAvailableNanos = 0L;
            lastAvailableNanos = 0L;
            sourceSetReadyNanos = 0L;
            firstConsumeNanos = 0L;
            firstAvailableThread = "none";
            lastAvailableThread = "none";
            sourceSetThread = "none";
            firstConsumeThread = "none";
        }

        if (!supported) {
            RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                    "decocraft_eligibility_skipped", -1L, "decocraft", null,
                    reloadGeneration, "reason=unsupported_version;version=" + detected);
            return;
        }

        RegularBootTraceBridge.record("barrier_wait", 0L, 0L, null,
                "decocraft_geometry_source_set", -1L, "decocraft", null,
                reloadGeneration,
                "waiting_for_stock_loadBlockModels;boundary=loader_custom_data_to_BBGeometry;version=" + detected);
    }

    /** Called only from the natural BBGeometry constructor return; no graph is read or copied. */
    public static void geometryAvailable() {
        long now = System.nanoTime();
        String thread = Thread.currentThread().getName();
        boolean first;
        long currentGeneration;
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            currentGeneration = generation;
            availableCount++;
            first = firstAvailableNanos == 0L;
            if (first) {
                firstAvailableNanos = now;
                firstAvailableThread = thread;
            }
            lastAvailableNanos = now;
            lastAvailableThread = thread;
        }
        if (first) {
            RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                    "decocraft_geometry_first_available", -1L, "decocraft", null,
                    currentGeneration,
                    "boundary=BBGeometry_ctor_return;immutable_graph_exists=true;thread=" + thread);
        }
    }

    /**
     * Anchors Decocraft's full immutable-input availability to the stock block-model future that
     * already participates in #214's ModelManager DAG. The original future is not replaced.
     */
    public static void blockModelsReady(long reloadGeneration, Throwable failure) {
        if (reloadGeneration != 1L) {
            return;
        }
        long now = System.nanoTime();
        int count;
        long lastAvailable;
        String thread = Thread.currentThread().getName();
        synchronized (LOCK) {
            if (!active || generation != reloadGeneration) {
                return;
            }
            sourceSetReadyNanos = now;
            sourceSetThread = thread;
            availableAtSourceSetReady = availableCount;
            count = availableCount;
            lastAvailable = lastAvailableNanos;
        }
        RegularBootTraceBridge.record("barrier_open", 0L, 0L, null,
                "decocraft_geometry_source_set", -1L, "decocraft", null,
                reloadGeneration,
                "status=" + (failure == null ? "success" : "failed")
                        + ";geometry_count=" + count
                        + ";last_geometry_to_source_set_ms=" + ms(now - lastAvailable)
                        + ";thread=" + thread);
    }

    /** Called once per BBGeometry instance at the first natural stock bake invocation. */
    public static void firstBaseConsume() {
        long now = System.nanoTime();
        String thread = Thread.currentThread().getName();
        boolean firstGlobal;
        long currentGeneration;
        int available;
        long sourceReady;
        long lastAvailable;
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            currentGeneration = generation;
            baseConsumedCount++;
            firstGlobal = firstConsumeNanos == 0L;
            if (!firstGlobal) {
                return;
            }
            firstConsumeNanos = now;
            firstConsumeThread = thread;
            availableAtFirstConsume = availableCount;
            available = availableCount;
            sourceReady = sourceSetReadyNanos;
            lastAvailable = lastAvailableNanos;
        }
        RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                "decocraft_first_base_consume", -1L, "decocraft", null,
                currentGeneration,
                "boundary=BBGeometry_bake_head"
                        + ";available_count=" + available
                        + ";source_set_ready=" + (sourceReady != 0L && sourceReady <= now)
                        + ";source_set_to_consume_ms=" + deltaMs(sourceReady, now)
                        + ";last_available_to_consume_ms=" + deltaMs(lastAvailable, now)
                        + ";binding_edge=current_sprite_ModelState_context_overrides"
                        + ";thread=" + thread);
    }

    public static void finishModelBake() {
        Snapshot snapshot;
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            snapshot = new Snapshot(
                    generation,
                    version,
                    availableCount,
                    availableAtSourceSetReady,
                    availableAtFirstConsume,
                    baseConsumedCount,
                    firstAvailableNanos,
                    lastAvailableNanos,
                    sourceSetReadyNanos,
                    firstConsumeNanos,
                    firstAvailableThread,
                    lastAvailableThread,
                    sourceSetThread,
                    firstConsumeThread);
            active = false;
        }

        String detail = "version=" + snapshot.version
                + ";available=" + snapshot.available
                + ";available_at_source_set=" + snapshot.availableAtSourceSet
                + ";available_at_first_consume=" + snapshot.availableAtFirstConsume
                + ";base_consumed=" + snapshot.baseConsumed
                + ";source_set_before_first_consume=" + (snapshot.sourceSetReadyNanos != 0L
                        && snapshot.firstConsumeNanos != 0L
                        && snapshot.sourceSetReadyNanos <= snapshot.firstConsumeNanos)
                + ";first_available_to_first_consume_ms=" + deltaMs(snapshot.firstAvailableNanos, snapshot.firstConsumeNanos)
                + ";last_available_to_first_consume_ms=" + deltaMs(snapshot.lastAvailableNanos, snapshot.firstConsumeNanos)
                + ";source_set_to_first_consume_ms=" + deltaMs(snapshot.sourceSetReadyNanos, snapshot.firstConsumeNanos)
                + ";first_available_thread=" + snapshot.firstAvailableThread
                + ";last_available_thread=" + snapshot.lastAvailableThread
                + ";source_set_thread=" + snapshot.sourceSetThread
                + ";first_consume_thread=" + snapshot.firstConsumeThread;

        RegularBootTraceBridge.record("mod_callback", 0L, 0L, null,
                "decocraft_eligibility_summary", -1L, "decocraft", null,
                snapshot.generation, detail);
        LOGGER.info("BOOTOPTIM_DECOCRAFT_ELIGIBILITY status=enabled {}", detail);
    }

    private static String detectVersion() {
        try {
            return ModList.get().getModContainerById("decocraft")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("absent");
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String deltaMs(long start, long end) {
        if (start == 0L || end == 0L || end < start) {
            return "na";
        }
        return ms(end - start);
    }

    private static String ms(long nanos) {
        if (nanos < 0L) {
            return "na";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record Snapshot(
            long generation,
            String version,
            int available,
            int availableAtSourceSet,
            int availableAtFirstConsume,
            int baseConsumed,
            long firstAvailableNanos,
            long lastAvailableNanos,
            long sourceSetReadyNanos,
            long firstConsumeNanos,
            String firstAvailableThread,
            String lastAvailableThread,
            String sourceSetThread,
            String firstConsumeThread) {
    }
}
