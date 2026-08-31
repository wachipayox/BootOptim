package dev.wachipayox.bootoptim.profiling.client;

import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Diagnostic-only accounting for the 1.21.1 blockstate/model-structure algorithms.
 *
 * <p>This intentionally does not repeat the old top-level bake identity profiler. It measures two structural costs:
 * variant predicates scanning block states during {@code BlockStateModelLoader}, and reconstruction of multipart
 * selector predicates during ModelBakery construction and baking.</p>
 */
public final class ModelStructureProfiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModelStructure");
    private static final List<VariantCounter> VARIANT_COUNTERS = new ArrayList<>();

    private static volatile Phase phase = Phase.NONE;
    private static long constructorStartedNanos;
    private static long constructorNanos;
    private static long blockStateStartedNanos;
    private static long blockStateNanos;
    private static long selectorConstructorCalls;
    private static long selectorConstructorNanos;
    private static long selectorBakeCalls;
    private static long selectorBakeNanos;
    private static long selectorOtherCalls;
    private static long selectorOtherNanos;

    private ModelStructureProfiler() {
    }

    public static synchronized void beginConstructor() {
        VARIANT_COUNTERS.clear();
        constructorNanos = 0L;
        blockStateNanos = 0L;
        selectorConstructorCalls = 0L;
        selectorConstructorNanos = 0L;
        selectorBakeCalls = 0L;
        selectorBakeNanos = 0L;
        selectorOtherCalls = 0L;
        selectorOtherNanos = 0L;
        constructorStartedNanos = System.nanoTime();
        blockStateStartedNanos = -1L;
        phase = Phase.CONSTRUCTOR;
    }

    public static synchronized void endConstructor() {
        if (phase != Phase.CONSTRUCTOR) {
            return;
        }
        constructorNanos = System.nanoTime() - constructorStartedNanos;
        phase = Phase.NONE;
    }

    public static void beginBlockStates() {
        if (phase == Phase.CONSTRUCTOR) {
            blockStateStartedNanos = System.nanoTime();
        }
    }

    public static void endBlockStates() {
        long started = blockStateStartedNanos;
        blockStateStartedNanos = -1L;
        if (started > 0L) {
            blockStateNanos = System.nanoTime() - started;
        }
    }

    public static boolean constructorActive() {
        return phase == Phase.CONSTRUCTOR;
    }

    public static synchronized VariantCounter registerVariantPredicate() {
        if (phase != Phase.CONSTRUCTOR) {
            return null;
        }
        VariantCounter counter = new VariantCounter();
        VARIANT_COUNTERS.add(counter);
        return counter;
    }

    public static boolean testVariant(VariantCounter counter, Predicate<BlockState> predicate, BlockState state) {
        counter.tests++;
        boolean result = predicate.test(state);
        if (result) {
            counter.matches++;
        }
        return result;
    }

    public static void beginBake() {
        phase = Phase.BAKE;
    }

    public static void recordSelectorPredicate(long nanos) {
        Phase current = phase;
        synchronized (ModelStructureProfiler.class) {
            switch (current) {
                case CONSTRUCTOR -> {
                    selectorConstructorCalls++;
                    selectorConstructorNanos += nanos;
                }
                case BAKE -> {
                    selectorBakeCalls++;
                    selectorBakeNanos += nanos;
                }
                case NONE -> {
                    selectorOtherCalls++;
                    selectorOtherNanos += nanos;
                }
            }
        }
    }

    public static boolean selectorProfilingActive() {
        return phase != Phase.NONE;
    }

    public static synchronized void finishBake() {
        if (phase == Phase.BAKE) {
            phase = Phase.NONE;
        }

        long predicates = VARIANT_COUNTERS.size();
        long tests = 0L;
        long matches = 0L;
        long maxTests = 0L;
        for (VariantCounter counter : VARIANT_COUNTERS) {
            tests += counter.tests;
            matches += counter.matches;
            maxTests = Math.max(maxTests, counter.tests);
        }

        LOGGER.info(
                "BOOTOPTIM_MODEL_STRUCTURE constructor_ms={} blockstate_load_ms={} variant_predicates={} variant_state_tests={} variant_matches={} max_tests_per_variant={} selector_constructor_calls={} selector_constructor_ms={} selector_bake_calls={} selector_bake_ms={} selector_other_calls={} selector_other_ms={}",
                ms(constructorNanos),
                ms(blockStateNanos),
                predicates,
                tests,
                matches,
                maxTests,
                selectorConstructorCalls,
                ms(selectorConstructorNanos),
                selectorBakeCalls,
                ms(selectorBakeNanos),
                selectorOtherCalls,
                ms(selectorOtherNanos));
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    public static final class VariantCounter {
        private long tests;
        private long matches;

        private VariantCounter() {
        }
    }

    private enum Phase {
        NONE,
        CONSTRUCTOR,
        BAKE
    }
}
