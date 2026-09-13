#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
UPSTREAM_COMMIT="139da711070c67f7e62cc20ea43507aa216cc8c6"
EXPECTED_LIST_BLOB="30706bda44951b044a90fc2c1d00a55ccd98abd2"
EXPECTED_LICENSE_BLOB="1be5e916bdd91e39196affbdf5d85bfe1f22a37d"
WORK="${RUNNER_TEMP:-/tmp}/bootoptim-agent146-at-runtime"
OUT="$ROOT_DIR/.agent146-at-runtime"
VERSION="10.0.1-agent146"
AT_JAVA_HOME="${BOOTOPTIM_JDK17:-${JAVA_HOME:-}}"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"
git init -q "$WORK/source"
git -C "$WORK/source" remote add origin https://github.com/neoforged/AccessTransformers.git
git -C "$WORK/source" fetch -q origin "$UPSTREAM_COMMIT"
git -C "$WORK/source" checkout -q FETCH_HEAD

test "$(git -C "$WORK/source" rev-parse HEAD)" = "$UPSTREAM_COMMIT"
test "$(git -C "$WORK/source" rev-parse HEAD:src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java)" = "$EXPECTED_LIST_BLOB"
test "$(git -C "$WORK/source" rev-parse HEAD:license.txt)" = "$EXPECTED_LICENSE_BLOB"
grep -q '^MIT License$' "$WORK/source/license.txt"
python3 "$ROOT_DIR/scripts/agent146/apply_at_candidate.py" "$WORK/source"

(
  cd "$WORK/source"
  JAVA_HOME="$AT_JAVA_HOME" ./gradlew jar generatePomFileForMavenJavaPublication --no-daemon
)
mapfile -t jars < <(find "$WORK/source/build/libs" -maxdepth 1 -type f -name 'accesstransformers-*.jar' ! -name '*sources*' ! -name '*testsjar*' | sort)
test "${#jars[@]}" -eq 1
cp "${jars[0]}" "$OUT/candidate.jar"
sha=$(sha256sum "$OUT/candidate.jar" | awk '{print $1}')
jar --describe-module --file "$OUT/candidate.jar" > "$OUT/module.txt"
grep -q '^net.neoforged.accesstransformer' "$OUT/module.txt"
unzip -p "$OUT/candidate.jar" META-INF/MANIFEST.MF > "$OUT/manifest.txt"
grep -q '^Implementation-Version: 10\.0\.1+' "$OUT/manifest.txt"

repo="$OUT/repo/net/neoforged/accesstransformers/$VERSION"
mkdir -p "$repo"
cp "$OUT/candidate.jar" "$repo/accesstransformers-$VERSION.jar"
python3 - "$WORK/source/build/publications/mavenJava/pom-default.xml" "$repo/accesstransformers-$VERSION.pom" "$VERSION" <<'PY'
from pathlib import Path
import sys
src = Path(sys.argv[1])
dst = Path(sys.argv[2])
version = sys.argv[3]
text = src.read_text()
start = text.find('<version>')
end = text.find('</version>', start)
if start < 0 or end < 0:
    raise SystemExit('project version missing from generated POM')
text = text[:start] + '<version>' + version + text[end:]
dst.write_text(text)
PY

mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/agent146-at-candidate.gradle" <<EOF
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
candidate_sha256=$sha
maven_coordinate=net.neoforged:accesstransformers:$VERSION
runtime_module=net.neoforged.accesstransformer
EOF
cat "$OUT/evidence.txt"
