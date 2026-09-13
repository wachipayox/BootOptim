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
git -C "$SRC" fetch -q --tags origin
git -C "$SRC" checkout -q "$UPSTREAM_COMMIT"
test "$(git -C "$SRC" rev-parse HEAD)" = "$UPSTREAM_COMMIT"
test "$(git -C "$SRC" rev-parse HEAD:src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java)" = "$EXPECTED_LIST_BLOB"
test "$(git -C "$SRC" rev-parse HEAD:license.txt)" = "$EXPECTED_LICENSE_BLOB"
grep -q '^The MIT License (MIT)$' "$SRC/license.txt"
python3 "$ROOT_DIR/scripts/agent146/apply_at_candidate.py" "$SRC"
(
  cd "$SRC"
  ./gradlew jar generatePomFileForMavenJavaPublication --no-daemon
)
mapfile -t jars < <(find "$SRC/build/libs" -maxdepth 1 -type f -name 'accesstransformers-*.jar' ! -name '*sources*' ! -name '*testsjar*' | sort)
test "${#jars[@]}" -eq 1
cp "${jars[0]}" "$OUT/candidate-first.jar"
repo="$OUT/repo/net/neoforged/accesstransformers/$VERSION"
mkdir -p "$repo"
cp "$OUT/candidate-first.jar" "$repo/accesstransformers-$VERSION.jar"
python3 - "$SRC/build/publications/mavenJava/pom-default.xml" "$repo/accesstransformers-$VERSION.pom" "$VERSION" <<'PY'
from pathlib import Path
import sys
src, dst, version = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
text = src.read_text()
start = text.find('<version>'); end = text.find('</version>', start)
if start < 0 or end < 0: raise SystemExit('project version missing from generated POM')
dst.write_text(text[:start] + '<version>' + version + text[end:])
PY
cat > "$OUT/init.gradle" <<EOF
allprojects { p ->
    p.repositories { maven { url = uri('${OUT}/repo') } }
    p.configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute module('net.neoforged:accesstransformers') using module('net.neoforged:accesstransformers:${VERSION}') because 'Agent146 exact-source AT candidate'
        }
    }
}
EOF
printf 'upstream_commit=%s\nlist_blob=%s\nlicense_blob=%s\nlicense=MIT\n' "$UPSTREAM_COMMIT" "$EXPECTED_LIST_BLOB" "$EXPECTED_LICENSE_BLOB" > "$OUT/setup-evidence.txt"
