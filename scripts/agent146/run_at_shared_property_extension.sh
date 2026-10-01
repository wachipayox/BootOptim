#!/usr/bin/env bash
set -euo pipefail
WORK_DIR="${RUNNER_TEMP:-/tmp}/bootoptim-agent145-access-transformers"
TEST_DIR="$WORK_DIR/src/test/java/net/neoforged/accesstransformer/parser"
test -f "$WORK_DIR/src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java"
grep -q 'oracleTouchedValidation' "$WORK_DIR/src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java"
mkdir -p "$TEST_DIR"
cat > "$TEST_DIR/Agent146SharedIdentityPropertyTest.java" <<'JAVA'
package net.neoforged.accesstransformer.parser;

import net.neoforged.accesstransformer.AccessTransformer;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Agent146SharedIdentityPropertyTest {
    @Test
    void deterministicSharedIdentityPropertyCorpusPreservesEveryOrderedSurface() {
        var stock = new AccessTransformerList(false);
        var candidate = new AccessTransformerList(true);
        var names = collidingNames(5); // 32 Aa/BB names with identical String hashes
        for (int i = 0; i < 256; i++) {
            String origin = "property-" + i + ".cfg";
            String text;
            if (i % 17 == 0) {
                text = "";
            } else {
                String cls = "oracle." + names.get((i * 13 + 7) & 31);
                String access = (i & 1) == 0 ? "public" : "protected";
                String finality = (i % 5) == 0 ? "+f" : "";
                text = access + finality + " " + cls + "\n"
                        + access + " " + cls + " Aa\n"
                        + "public " + cls + " BB\n";
                if (i % 11 == 0) text += access + " " + cls + " Aa\n";
            }
            List<AccessTransformer> shared = AccessTransformerList.parseForOracle(CharStreams.fromString(text, origin));
            stock.loadParsedForOracle(shared, origin);
            candidate.loadParsedForOracle(shared, origin);
            assertEquals(stock.oracleRawEntries(), candidate.oracleRawEntries(), "raw HashMap order @" + i);
            assertEquals(new ArrayList<>(stock.getTargets()), new ArrayList<>(candidate.getTargets()), "target iteration @" + i);
            assertEquals(stock.getTargets(), candidate.getTargets(), "target membership @" + i);
            assertEquals(stock.getAccessTransformers().toString(), candidate.getAccessTransformers().toString(), "group publication @" + i);
        }
    }

    private static List<String> collidingNames(int depth) {
        List<String> names = List.of("");
        for (int i = 0; i < depth; i++) {
            List<String> next = new ArrayList<>();
            for (String p : names) { next.add(p + "Aa"); next.add(p + "BB"); }
            names = next;
        }
        return names;
    }
}
JAVA
(
  cd "$WORK_DIR"
  ./gradlew test --no-daemon --tests net.neoforged.accesstransformer.parser.Agent146SharedIdentityPropertyTest
)
