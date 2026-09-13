#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
UPSTREAM_COMMIT="139da711070c67f7e62cc20ea43507aa216cc8c6"
EXPECTED_LIST_BLOB="30706bda44951b044a90fc2c1d00a55ccd98abd2"
EXPECTED_LICENSE_BLOB="1be5e916bdd91e39196affbdf5d85bfe1f22a37d"
WORK_BASE="${RUNNER_TEMP:-/tmp}/bootoptim-agent146-at"
SRC="$WORK_BASE/source"
OUT="$ROOT_DIR/.agent146-at"
VERSION="10.0.1-agent146"

rm -rf "$WORK_BASE" "$OUT"
mkdir -p "$WORK_BASE" "$OUT"
git init -q "$SRC"
git -C "$SRC" remote add origin https://github.com/neoforged/AccessTransformers.git
git -C "$SRC" fetch -q origin "$UPSTREAM_COMMIT"
git -C "$SRC" checkout -q FETCH_HEAD

test "$(git -C "$SRC" rev-parse HEAD)" = "$UPSTREAM_COMMIT"
test "$(git -C "$SRC" rev-parse HEAD:src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java)" = "$EXPECTED_LIST_BLOB"
test "$(git -C "$SRC" rev-parse HEAD:license.txt)" = "$EXPECTED_LICENSE_BLOB"
grep -q '^MIT License$' "$SRC/license.txt"

python3 "$ROOT_DIR/scripts/agent146/apply_at_candidate.py" "$SRC"

mkdir -p "$SRC/src/test/java/net/neoforged/accesstransformer/parser"
cat > "$SRC/src/test/java/net/neoforged/accesstransformer/parser/Agent146TouchedValidationTest.java" <<'JAVA'
package net.neoforged.accesstransformer.parser;

import net.neoforged.accesstransformer.AccessTransformer;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Agent146TouchedValidationTest {
    @Test
    void aaBbCollisionCorpusMatchesRawPublicationAfterEveryFile() throws Exception {
        AccessTransformerList stock = new AccessTransformerList();
        AccessTransformerList candidate = new AccessTransformerList();
        List<String> classes = collidingNames(5); // 32 Aa/BB collision names
        for (int i = 0; i < classes.size(); i++) {
            String cls = "oracle." + classes.get(i);
            String text = "public-f " + cls + "\nprotected " + cls + " Aa\npublic " + cls + " BB\n";
            load(stock, false, text, "collision-" + i + ".cfg");
            load(candidate, true, text, "collision-" + i + ".cfg");
            assertEquals(raw(stock), raw(candidate));
            assertEquals(new ArrayList<>(stock.getTargets()), new ArrayList<>(candidate.getTargets()));
            assertEquals(stock.getTargets(), candidate.getTargets());
        }
    }

    @Test
    void conflictRollsBackAndMatchesStockState() throws Exception {
        AccessTransformerList stock = new AccessTransformerList();
        AccessTransformerList candidate = new AccessTransformerList();
        String base = "public+f oracle.Aa Aa\npublic+f oracle.BB BB\n";
        load(stock, false, base, "base.cfg");
        load(candidate, true, base, "base.cfg");
        List<String> stockBefore = raw(stock);
        List<String> candidateBefore = raw(candidate);
        String conflict = "public-f oracle.Aa Aa\npublic-f oracle.BB BB\n";
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class, () -> load(stock, false, conflict, "conflict.cfg"));
        IllegalArgumentException b = assertThrows(IllegalArgumentException.class, () -> load(candidate, true, conflict, "conflict.cfg"));
        assertEquals(a.getMessage(), b.getMessage());
        assertEquals(stockBefore, raw(stock));
        assertEquals(candidateBefore, raw(candidate));
        assertEquals(raw(stock), raw(candidate));
    }

    @Test
    void emptyAndRepeatedFilesRemainEquivalent() throws Exception {
        AccessTransformerList stock = new AccessTransformerList();
        AccessTransformerList candidate = new AccessTransformerList();
        for (int i = 0; i < 64; i++) {
            String text = i % 3 == 0 ? "" : "public oracle.Repeat value\npublic oracle.Repeat value\n";
            load(stock, false, text, "p-" + i + ".cfg");
            load(candidate, true, text, "p-" + i + ".cfg");
            assertEquals(raw(stock), raw(candidate));
        }
    }

    private static void load(AccessTransformerList list, boolean touched, String text, String name) {
        if (touched) System.setProperty("boot_optim.atTouchedValidation", "true");
        else System.clearProperty("boot_optim.atTouchedValidation");
        try {
            list.loadAT(CharStreams.fromString(text, name));
        } finally {
            System.clearProperty("boot_optim.atTouchedValidation");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> raw(AccessTransformerList list) throws Exception {
        Field f = AccessTransformerList.class.getDeclaredField("accessTransformers");
        f.setAccessible(true);
        Map<Object, AccessTransformer> map = (Map<Object, AccessTransformer>) f.get(list);
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(k + "=" + v + " origins=" + v.getOrigins()));
        return out;
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
  cd "$SRC"
  ./gradlew test --no-daemon --tests net.neoforged.accesstransformer.parser.Agent146TouchedValidationTest
)

# Build twice from clean outputs with deterministic archive settings; byte identity is required.
(
  cd "$SRC"
  ./gradlew clean jar generatePomFileForMavenJavaPublication --no-daemon
)
mapfile -t jars < <(find "$SRC/build/libs" -maxdepth 1 -type f -name 'accesstransformers-*.jar' ! -name '*sources*' ! -name '*testsjar*' | sort)
test "${#jars[@]}" -eq 1
cp "${jars[0]}" "$OUT/candidate-first.jar"
sha1=$(sha256sum "$OUT/candidate-first.jar" | awk '{print $1}')
(
  cd "$SRC"
  ./gradlew clean jar generatePomFileForMavenJavaPublication --no-daemon
)
mapfile -t jars2 < <(find "$SRC/build/libs" -maxdepth 1 -type f -name 'accesstransformers-*.jar' ! -name '*sources*' ! -name '*testsjar*' | sort)
test "${#jars2[@]}" -eq 1
cp "${jars2[0]}" "$OUT/candidate.jar"
sha2=$(sha256sum "$OUT/candidate.jar" | awk '{print $1}')
test "$sha1" = "$sha2"

jar --describe-module --file "$OUT/candidate.jar" > "$OUT/module.txt"
grep -q '^net.neoforged.accesstransformer' "$OUT/module.txt"
unzip -p "$OUT/candidate.jar" META-INF/MANIFEST.MF > "$OUT/manifest.txt"
grep -q '^Implementation-Version: 10\.0\.1+' "$OUT/manifest.txt"

# Materialize a unique Maven coordinate for Gradle dependency substitution while preserving the runtime module name.
repo="$OUT/repo/net/neoforged/accesstransformers/$VERSION"
mkdir -p "$repo"
cp "$OUT/candidate.jar" "$repo/accesstransformers-$VERSION.jar"
python3 - "$SRC/build/publications/mavenJava/pom-default.xml" "$repo/accesstransformers-$VERSION.pom" "$VERSION" <<'PY'
from pathlib import Path
import sys
src, dst, version = map(Path, sys.argv[1:3]) + [None] if False else (Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
text = src.read_text()
start = text.find('<version>')
end = text.find('</version>', start)
if start < 0 or end < 0:
    raise SystemExit('project version missing from generated POM')
text = text[:start] + '<version>' + version + text[end:]
dst.write_text(text)
PY

cat > "$OUT/init.gradle" <<EOF
allprojects { p ->
    p.repositories {
        maven { url = uri('${OUT}/repo') }
    }
    p.configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute module('net.neoforged:accesstransformers') using module('net.neoforged:accesstransformers:${VERSION}') because 'Agent146 exact-source AT candidate'
        }
    }
}
EOF

cat > "$OUT/evidence.txt" <<EOF
upstream_commit=$UPSTREAM_COMMIT
list_blob=$EXPECTED_LIST_BLOB
license_blob=$EXPECTED_LICENSE_BLOB
license=MIT
candidate_sha256=$sha2
reproducible=true
maven_coordinate=net.neoforged:accesstransformers:$VERSION
runtime_module=net.neoforged.accesstransformer
EOF
cat "$OUT/evidence.txt"
