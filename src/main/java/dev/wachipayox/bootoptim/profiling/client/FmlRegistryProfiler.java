package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

/**
 * Diagnostic-only ownership accounting inside NeoForge registry initialization.
 *
 * <p>Per-registry CPU/class/JIT measurements are emitted through {@link FmlLifecycleProfiler}.
 * This class intentionally keeps the much more frequent per-ModContainer accounting to wall time
 * only so the diagnostic does not turn MXBean queries into a material part of registration.</p>
 */
public final class FmlRegistryProfiler {
    private static final boolean ENABLED = Boolean.getBoolean(FmlLifecycleProfiler.PROFILE_PROPERTY);
    private static final int TOP_MODS = 20;

    private static final ConcurrentMap<String, Totals> REGISTRY_TOTALS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<RegistryModKey, Totals> MOD_TOTALS = new ConcurrentHashMap<>();
    private static final LongAdder REGISTER_EVENT_NANOS = new LongAdder();
    private static final ThreadLocal<ArrayDeque<RegistryFrame>> ACTIVE_REGISTRIES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<ModFrame>> ACTIVE_MOD_POSTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile long postRegisterEventsStartedNanos = -1L;

    private FmlRegistryProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void beginPostRegisterEvents() {
        if (!ENABLED) {
            return;
        }
        try {
            REGISTRY_TOTALS.clear();
            MOD_TOTALS.clear();
            REGISTER_EVENT_NANOS.reset();
            ACTIVE_REGISTRIES.remove();
            ACTIVE_MOD_POSTS.remove();
            postRegisterEventsStartedNanos = System.nanoTime();
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    public static void beginRegisterEvent(RegisterEvent event) {
        if (!ENABLED) {
            return;
        }
        try {
            ACTIVE_REGISTRIES.get().push(new RegistryFrame(registryName(event), System.nanoTime()));
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    public static void endRegisterEvent(RegisterEvent event) {
        if (!ENABLED) {
            return;
        }
        try {
            ArrayDeque<RegistryFrame> stack = ACTIVE_REGISTRIES.get();
            if (stack.isEmpty()) {
                return;
            }
            RegistryFrame frame = stack.pop();
            long elapsed = nonNegativeDelta(frame.startedNanos(), System.nanoTime());
            String registry = frame.registry();
            REGISTRY_TOTALS.computeIfAbsent(registry, ignored -> new Totals()).add(elapsed);
            REGISTER_EVENT_NANOS.add(elapsed);
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    /**
     * Observe the already-active ModContainer immediately before ModernFix calls ModContainer.acceptEvent.
     * This method never mutates ModLoadingContext or invokes the event bus.
     */
    public static void beginActiveModContainerPost() {
        if (!ENABLED) {
            return;
        }
        try {
            ArrayDeque<RegistryFrame> registries = ACTIVE_REGISTRIES.get();
            if (registries.isEmpty()) {
                return;
            }
            ModContainer active = ModLoadingContext.get().getActiveContainer();
            ACTIVE_MOD_POSTS.get().push(new ModFrame(registries.peek().registry(), active.getModId(), System.nanoTime()));
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    /** Observe return from the same existing ModContainer.acceptEvent call. */
    public static void endActiveModContainerPost() {
        if (!ENABLED) {
            return;
        }
        try {
            ArrayDeque<ModFrame> stack = ACTIVE_MOD_POSTS.get();
            if (stack.isEmpty()) {
                return;
            }
            ModFrame frame = stack.pop();
            long elapsed = nonNegativeDelta(frame.startedNanos(), System.nanoTime());
            MOD_TOTALS.computeIfAbsent(new RegistryModKey(frame.registry(), frame.modId()), ignored -> new Totals()).add(elapsed);
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    public static void endPostRegisterEvents() {
        if (!ENABLED) {
            return;
        }
        try {
            long started = postRegisterEventsStartedNanos;
            postRegisterEventsStartedNanos = -1L;
            long totalNanos = started >= 0L ? nonNegativeDelta(started, System.nanoTime()) : -1L;
            long eventNanos = REGISTER_EVENT_NANOS.sum();
            long residualNanos = totalNanos >= 0L ? Math.max(0L, totalNanos - eventNanos) : -1L;

            List<Map.Entry<String, Totals>> registries = new ArrayList<>(REGISTRY_TOTALS.entrySet());
            registries.sort(Comparator.comparingLong((Map.Entry<String, Totals> entry) -> entry.getValue().nanos()).reversed());

            String dominant = registries.isEmpty() ? "none" : registries.get(0).getKey();
            long dominantNanos = registries.isEmpty() ? 0L : registries.get(0).getValue().nanos();

            logger().info(
                    "BOOTOPTIM_FML_REGISTRY summary=post_register_events total_wall_ms={} register_event_sum_ms={} residual_wall_ms={} registry_count={} dominant_registry={} dominant_wall_ms={}",
                    formatNanos(totalNanos),
                    formatNanos(eventNanos),
                    formatNanos(residualNanos),
                    registries.size(),
                    dominant,
                    formatNanos(dominantNanos));

            for (int i = 0; i < registries.size(); i++) {
                Map.Entry<String, Totals> entry = registries.get(i);
                logger().info(
                        "BOOTOPTIM_FML_REGISTRY dimension=registry rank={} registry={} calls={} wall_ms={} share_of_register_events_pct={}",
                        i + 1,
                        entry.getKey(),
                        entry.getValue().calls(),
                        formatNanos(entry.getValue().nanos()),
                        formatPercent(entry.getValue().nanos(), eventNanos));
            }

            if (!"none".equals(dominant)) {
                List<Map.Entry<RegistryModKey, Totals>> mods = MOD_TOTALS.entrySet().stream()
                        .filter(entry -> dominant.equals(entry.getKey().registry()))
                        .sorted(Comparator.comparingLong((Map.Entry<RegistryModKey, Totals> entry) -> entry.getValue().nanos()).reversed())
                        .toList();
                long modSumNanos = mods.stream().mapToLong(entry -> entry.getValue().nanos()).sum();

                logger().info(
                        "BOOTOPTIM_FML_REGISTRY dimension=dominant_mod_summary registry={} attributed_mod_wall_ms={} unattributed_or_bus_overhead_ms={} mod_count={}",
                        dominant,
                        formatNanos(modSumNanos),
                        formatNanos(Math.max(0L, dominantNanos - modSumNanos)),
                        mods.size());

                int count = Math.min(TOP_MODS, mods.size());
                for (int i = 0; i < count; i++) {
                    Map.Entry<RegistryModKey, Totals> entry = mods.get(i);
                    logger().info(
                            "BOOTOPTIM_FML_REGISTRY dimension=dominant_mod rank={} registry={} mod={} phase_posts={} wall_ms={} share_of_registry_pct={}",
                            i + 1,
                            dominant,
                            entry.getKey().modId(),
                            entry.getValue().calls(),
                            formatNanos(entry.getValue().nanos()),
                            formatPercent(entry.getValue().nanos(), dominantNanos));
                }
            }
        } catch (Throwable ignored) {
            // Diagnostic instrumentation must never affect startup.
        }
    }

    public static String registryName(RegisterEvent event) {
        try {
            return event.getRegistryKey().location().toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static long nonNegativeDelta(long start, long end) {
        return end >= start ? end - start : 0L;
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) {
            return "unavailable";
        }
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String formatPercent(long part, long total) {
        return total > 0L
                ? String.format(Locale.ROOT, "%.2f", part * 100.0D / total)
                : "0.00";
    }

    private static Logger logger() {
        return LoggerHolder.LOGGER;
    }

    private static final class LoggerHolder {
        private static final Logger LOGGER = LogUtils.getLogger();
    }

    private static final class Totals {
        private final LongAdder calls = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        void add(long elapsedNanos) {
            calls.increment();
            nanos.add(elapsedNanos);
        }

        long calls() {
            return calls.sum();
        }

        long nanos() {
            return nanos.sum();
        }
    }

    private record RegistryFrame(String registry, long startedNanos) {
    }

    private record ModFrame(String registry, String modId, long startedNanos) {
    }

    private record RegistryModKey(String registry, String modId) {
    }
}
