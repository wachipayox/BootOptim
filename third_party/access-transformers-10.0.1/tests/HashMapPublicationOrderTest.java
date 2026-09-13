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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial equivalence check for the HashMap publication detail that the
 * incremental candidate intentionally removes. Aa/BB combinations are distinct
 * Java strings with identical hash codes; enough entries force the collision
 * bin through HashMap resize/treeification thresholds.
 */
class HashMapPublicationOrderTest {
    @Test
    void successfulFilesKeepStockRawIterationOrderAcrossCollidingTargets() throws Exception {
        var candidate = new AccessTransformerList();
        var stock = new StockMap();
        var names = collidingClassNames(4);
        int expectedHash = names.getFirst().hashCode();
        assertTrue(names.size() >= 12);
        names.forEach(name -> assertEquals(expectedHash, name.hashCode(), name));

        for (int i = 0; i < names.size(); i++) {
            String origin = "collision-" + i + ".cfg";
            String text = "public-f " + names.get(i) + "\n";
            candidate.loadAT(CharStreams.fromString(text, origin));
            stock.load(text, origin);
            assertEquals(stock.rawOrder(), candidateRawOrder(candidate), "after " + origin);
        }
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
    private static List<String> candidateRawOrder(AccessTransformerList candidate) throws Exception {
        Field field = AccessTransformerList.class.getDeclaredField("accessTransformers");
        field.setAccessible(true);
        Map<Target<?>, AccessTransformer> map = (Map<Target<?>, AccessTransformer>) field.get(candidate);
        return map.keySet().stream().map(Target::toString).toList();
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
    }
}
