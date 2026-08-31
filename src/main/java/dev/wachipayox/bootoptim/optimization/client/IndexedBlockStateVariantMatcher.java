package dev.wachipayox.bootoptim.optimization.client;

import com.google.common.base.Splitter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Replaces 1.21.1's O(variants * possible states) blockstate variant scan with a reload-scoped index.
 *
 * <p>The stock predicate parser still runs first and therefore remains authoritative for validation and errors. Once
 * the variant is known to be valid, this helper builds property/value BitSet masks over the canonical
 * {@code getPossibleStates()} order and intersects them to enumerate only matching states. Any unexpected internal
 * disagreement fails open to the original stock predicate path.</p>
 */
public final class IndexedBlockStateVariantMatcher {
    private static final Splitter COMMA_SPLITTER = Splitter.on(',');
    private static final Splitter EQUAL_SPLITTER = Splitter.on('=').limit(2);
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.blockstateIndexedMatching", "true"));

    private static final ThreadLocal<LoadContext> CONTEXT = new ThreadLocal<>();

    private IndexedBlockStateVariantMatcher() {
    }

    public static void beginLoadAll() {
        if (ENABLED) {
            CONTEXT.set(new LoadContext());
        }
    }

    public static void finishLoadAll() {
        CONTEXT.remove();
    }

    /** Called only after the stock parser has successfully created its predicate. */
    public static Predicate<BlockState> wrapValidatedPredicate(
            StateDefinition<Block, BlockState> definition,
            String variant,
            Predicate<BlockState> stockPredicate) {
        LoadContext context = CONTEXT.get();
        if (!ENABLED || context == null) {
            return stockPredicate;
        }

        try {
            Map<Property<?>, Comparable<?>> constraints = parseAlreadyValidated(definition, variant);
            return new IndexedPredicate(definition, stockPredicate, constraints);
        } catch (RuntimeException unexpected) {
            return stockPredicate;
        }
    }

    /** Replaces only the Stream.filter candidate scan used for blockstate variants. */
    public static Stream<BlockState> filterVariantStates(
            Stream<BlockState> stockStream,
            Predicate<BlockState> predicate) {
        LoadContext context = CONTEXT.get();
        if (!ENABLED || context == null || !(predicate instanceof IndexedPredicate indexed)) {
            return stockStream.filter(predicate);
        }

        try {
            StateIndex index = context.indices.get(indexed.definition);
            if (index == null) {
                index = new StateIndex(indexed.definition.getPossibleStates());
                context.indices.put(indexed.definition, index);
            }

            BitSet matches = index.match(indexed.constraints);
            return matches.stream().mapToObj(index.states::get);
        } catch (RuntimeException unexpected) {
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
                throw new IllegalStateException("Stock predicate accepted an unresolved property");
            }

            Comparable<?> value = getValue(property, iterator.next());
            if (value == null) {
                throw new IllegalStateException("Stock predicate accepted an unresolved value");
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

    private static final class LoadContext {
        private final IdentityHashMap<StateDefinition<Block, BlockState>, StateIndex> indices = new IdentityHashMap<>();
    }
}
