package net.neoforged.accesstransformer.parser;

import net.neoforged.accesstransformer.AccessTransformer;
import net.neoforged.accesstransformer.Target;
import net.neoforged.accesstransformer.generated.AtLexer;
import net.neoforged.accesstransformer.generated.AtParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distinguish a non-exported live HashMap topology change from the observable
 * failure-diagnostic order. Stock republishes through clear/putAll after every
 * successful file; the candidate deliberately does not. On the conflict path,
 * however, the candidate makes the same new HashMap copy stock makes before
 * scanning invalid transformers. That copy must normalize to the same order.
 */
class HashMapPublicationOrderTest {
    @Test
    void conflictDiagnosticTopologyMatchesStockAfterAdversarialCollisions() throws Exception {
        var candidate = new AccessTransformerList();
        var stock = new StockMap();
        var names = collidingClassNames(4);
        int expectedHash = names.get(0).hashCode();
        assertTrue(names.size() >= 12);
        names.forEach(name -> assertEquals(expectedHash, name.hashCode(), name));

        boolean sawLiveDivergence = false;
        for (int i = 0; i < names.size(); i++) {
            String origin = "collision-" + i + ".cfg";
            String text = "public-f " + names.get(i) + "\n";
            candidate.loadAT(CharStreams.fromString(text, origin));
            stock.load(text, origin);
            if (!stock.rawOrder().equals(candidateRawOrder(candidate))) {
                sawLiveDivergence = true;
            }
        }
        assertTrue(sawLiveDivergence, "adversarial fixture must exercise the removed republication topology");

        // This is the topology each implementation scans on an existing-target
        // finality conflict: both create a fresh HashMap from their committed map;
        // replacing values for the conflicting existing targets does not move keys.
        assertEquals(stock.failureCopyOrder(), candidateFailureCopyOrder(candidate));
        assertNotEquals(stock.rawOrder(), candidateRawOrder(candidate),
                "fixture should retain a live-map topology difference so the equality above is meaningful");
    }

    private static List<String> collidingClassNames(int pairCount) {
        var out = new ArrayList<String>();
        int count = 1 << pairCount;
        for (int mask = 0; mask < count; mask++) {
            var name = new StringBuilder("example.");
            for (int pair = 0; pair < pairCount; pair++) {
                name.append((mask & (1 << pair)) == 0 ? "Aa" : "BB");
            }
            out.add(name.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Target<?>, AccessTransformer> candidateMap(AccessTransformerList candidate) throws Exception {
        Field field = AccessTransformerList.class.getDeclaredField("accessTransformers");
        field.setAccessible(true);
        return (Map<Target<?>, AccessTransformer>) field.get(candidate);
    }

    private static List<String> candidateRawOrder(AccessTransformerList candidate) throws Exception {
        return candidateMap(candidate).keySet().stream().map(Target::toString).toList();
    }

    private static List<String> candidateFailureCopyOrder(AccessTransformerList candidate) throws Exception {
        return new HashMap<>(candidateMap(candidate)).keySet().stream().map(Target::toString).toList();
    }

    private static final class StockMap {
        private final Map<Target<?>, AccessTransformer> map = new HashMap<>();

        void load(String text, String origin) {
            var stream = CharStreams.fromString(text, origin);
            var parser = new AtParser(new CommonTokenStream(new AtLexer(stream)));
            parser.addErrorListener(new AtParserErrorListener());
            var visitor = new AccessTransformVisitor(stream.getSourceName());
            parser.file().accept(visitor);

            var local = new HashMap<Target<?>, AccessTransformer>(map);
            visitor.getAccessTransformers().forEach(at -> local.merge(
                    at.getTarget(), at,
                    (left, right) -> left.mergeStates(right, stream.getSourceName())));
            if (local.values().stream().anyMatch(at -> !at.isValid())) {
                throw new IllegalArgumentException("Invalid AT final conflicts");
            }
            map.clear();
            map.putAll(local);
        }

        List<String> rawOrder() {
            return map.keySet().stream().map(Target::toString).toList();
        }

        List<String> failureCopyOrder() {
            return new HashMap<>(map).keySet().stream().map(Target::toString).toList();
        }
    }
}
