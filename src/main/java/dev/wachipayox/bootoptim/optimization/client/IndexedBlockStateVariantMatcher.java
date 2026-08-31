package dev.wachipayox.bootoptim.optimization.client;

import com.google.common.base.Splitter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Experimental replacement for 1.21.1's O(variants * possible states) blockstate matching loop.
 *
 * <p>Stock predicate parsing still runs first and remains authoritative for validation/errors. This helper parses the
 * already-valid key a second time only to build an indexed representation. A per-StateDefinition index maps each
 * property value to a BitSet over the canonical getPossibleStates() order. Variant matching then intersects masks and
 * streams only the matching states in the same order stock would have produced.</p>
 */
public final class IndexedBlockStateVariantMatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/BlockStateIndex");
    private static final Splitter COMMA_SPLITTER = Splitter.on(',');
    private static final Splitter EQUAL_SPLITTER = Splitter.on('=').limit(2);
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.blockstateIndexedMatching", "true"));

    // Enable only for equivalence runs; normal timing must not execute the stock predicate path again.
    private static final boolean VERIFY = Boolean.parseBoolean(
            System.getProperty("boot_optim.blockstateIndexedMatchingVerify", "false"));

    private static final ThreadLocal<RunState> RUN = new ThreadLocal<>();

    private IndexedBlockStateVariantMatcher() {
    }

    public static void beginLoadAll() {
        if (ENABLED) {
            RUN.set(new RunState());
        }
    }

    public static void finishLoadAll() {
        RunState run = RUN.get();
        RUN.remove();
        if (run == null) {
            return;
        }

        LOGGER.info(
                "BOOTOPTIM_BLOCKSTATE_INDEX status=enabled verify={} definitions={} indexed_variants={} fallback_variants={} verification_mismatches={} stock_candidate_tests={} indexed_match_visits={} avoided_candidate_tests={} index_build_ms={} matching_ms={} verify_ms={}",
                VERIFY,
                run.indices.size(),
                run.indexedVariants,
                run.fallbackVariants,
                run.verificationMismatches,
                run.stockCandidateTests,
                run.indexedMatchVisits,
                Math.max(0L, run.stockCandidateTests - run.indexedMatchVisits),
                ms(run.indexBuildNanos),
                ms(run.matchingNanos),
                ms(run.verifyNanos));
    }

    /** Called after stock predicate creation, so stock parse/validation exceptions have already occurred unchanged. */
    public static Predicate<BlockState> wrapValidatedPredicate(
            StateDefinition<Block, BlockState> definition,
            String variant,
            Predicate<BlockState> stockPredicate) {
        RunState run = RUN.get();
        if (!ENABLED || run == null) {
            return stockPredicate;
        }

        try {
            Map<Property<?>, Comparable<?>> constraints = parseAlreadyValidated(definition, variant);
            return new IndexedPredicate(definition, stockPredicate, constraints);
        } catch (RuntimeException unexpected) {
            // Stock parsing already succeeded. Any disagreement in our secondary parser must fail open.
            run.fallbackVariants++;
            return stockPredicate;
        }
    }

    /** Replaces only the Stream.filter call in lambda$loadBlockStateDefinitions$8. */
    public static Stream<BlockState> filterVariantStates(
            Stream<BlockState> stockStream,
            Predicate<BlockState> predicate) {
        RunState run = RUN.get();
        if (!ENABLED || run == null || !(predicate instanceof IndexedPredicate indexed)) {
            return stockStream.filter(predicate);
        }

        try {
            StateIndex index = run.indices.get(indexed.definition);
            if (index == null) {
                long started = System.nanoTime();
                index = new StateIndex(indexed.definition.getPossibleStates());
                run.indices.put(indexed.definition, index);
                run.indexBuildNanos += System.nanoTime() - started;
            }

            long started = System.nanoTime();
            BitSet matches = index.match(indexed.constraints);
            run.matchingNanos += System.nanoTime() - started;
            run.indexedVariants++;
            run.stockCandidateTests += index.states.size();
            run.indexedMatchVisits += matches.cardinality();

            if (VERIFY) {
                long verifyStarted = System.nanoTime();
                List<BlockState> expected = index.states.stream().filter(indexed.stockPredicate).toList();
                List<BlockState> actual = toList(index.states, matches);
                run.verifyNanos += System.nanoTime() - verifyStarted;
                if (!sameIdentityOrder(expected, actual)) {
                    run.verificationMismatches++;
                    run.fallbackVariants++;
                    return expected.stream();
                }
            }

            return matches.stream().mapToObj(index.states::get);
        } catch (RuntimeException unexpected) {
            run.fallbackVariants++;
            return stockStream.filter(indexed.stockPredicate);
        }
    }

    private static Map<Property<?>, Comparable<?>> parseAlreadyValidated(
            StateDefinition<Block, BlockState> definition,
            String variant) {
        Map<Property<?>, Comparable<?>> constraints = new HashMap<>();
        for (String part : COMMA_SPLITTER.split(variant)) {
            Iterator<String> iterator = EQUAL_SPLITTER.split(part).iterator();
            if (!iterator.hasNext()) {
                continue;
            }

            String propertyName = iterator.next();
            Property<?> property = definition.getProperty(propertyName);
            if (property == null || !iterator.hasNext()) {
                if (propertyName.isEmpty()) {
                    continue;
                }
                throw new IllegalStateException("Stock predicate accepted a property our parser cannot resolve");
            }

            String valueName = iterator.next();
            Comparable<?> value = getValue(property, valueName);
            if (value == null) {
                throw new IllegalStateException("Stock predicate accepted a value our parser cannot resolve");
            }
            constraints.put(property, value);
        }
        return constraints;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> getValue(Property<?> property, String valueName) {
        return (Comparable<?>) ((Property) property).getValue(valueName).orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> stateValue(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }

    private static List<BlockState> toList(List<BlockState> states, BitSet matches) {
        ArrayList<BlockState> result = new ArrayList<>(matches.cardinality());
        for (int index = matches.nextSetBit(0); index >= 0; index = matches.nextSetBit(index + 1)) {
            result.add(states.get(index));
        }
        return result;
    }

    private static boolean sameIdentityOrder(List<BlockState> expected, List<BlockState> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) != actual.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private record IndexedPredicate(
            StateDefinition<Block, BlockState> definition,
            Predicate<BlockState> stockPredicate,
            Map<Property<?>, Comparable<?>> constraints) implements Predicate<BlockState> {
        @Override
        public boolean test(BlockState state) {
            return stockPredicate.test(state);
        }
    }

    private static final class StateIndex {
        private final List<BlockState> states;
        private final IdentityHashMap<Property<?>, Map<Comparable<?>, BitSet>> propertyMasks = new IdentityHashMap<>();

        private StateIndex(List<BlockState> states) {
            this.states = states;
        }

        private BitSet match(Map<Property<?>, Comparable<?>> constraints) {
            if (constraints.isEmpty()) {
                BitSet all = new BitSet(states.size());
                all.set(0, states.size());
                return all;
            }

            BitSet result = null;
            for (Map.Entry<Property<?>, Comparable<?>> constraint : constraints.entrySet()) {
                BitSet mask = mask(constraint.getKey(), constraint.getValue());
                if (result == null) {
                    result = (BitSet) mask.clone();
                } else {
                    result.and(mask);
                }
                if (result.isEmpty()) {
                    break;
                }
            }
            return result == null ? new BitSet(states.size()) : result;
        }

        private BitSet mask(Property<?> property, Comparable<?> value) {
            Map<Comparable<?>, BitSet> byValue = propertyMasks.get(property);
            if (byValue == null) {
                byValue = buildPropertyMasks(property);
                propertyMasks.put(property, byValue);
            }
            BitSet mask = byValue.get(value);
            return mask == null ? new BitSet(states.size()) : mask;
        }

        private Map<Comparable<?>, BitSet> buildPropertyMasks(Property<?> property) {
            Map<Comparable<?>, BitSet> byValue = new HashMap<>();
            for (int i = 0; i < states.size(); i++) {
                Comparable<?> value = stateValue(states.get(i), property);
                byValue.computeIfAbsent(value, ignored -> new BitSet(states.size())).set(i);
            }
            return byValue;
        }
    }

    private static final class RunState {
        private final IdentityHashMap<StateDefinition<Block, BlockState>, StateIndex> indices = new IdentityHashMap<>();
        private long indexedVariants;
        private long fallbackVariants;
        private long verificationMismatches;
        private long stockCandidateTests;
        private long indexedMatchVisits;
        private long indexBuildNanos;
        private long matchingNanos;
        private long verifyNanos;
    }
}
