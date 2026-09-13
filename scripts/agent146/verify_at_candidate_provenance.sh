#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${RUNNER_TEMP:-/tmp}/bootoptim-agent146-at/source"
OUT="$ROOT_DIR/.agent146-at"
first="$OUT/candidate-first.jar"
test -f "$first"
sha1=$(sha256sum "$first" | awk '{print $1}')
(
  cd "$SRC"
  ./gradlew clean jar --no-daemon
)
mapfile -t jars < <(find "$SRC/build/libs" -maxdepth 1 -type f -name 'accesstransformers-*.jar' ! -name '*sources*' ! -name '*testsjar*' | sort)
test "${#jars[@]}" -eq 1
cp "${jars[0]}" "$OUT/candidate-second.jar"
sha2=$(sha256sum "$OUT/candidate-second.jar" | awk '{print $1}')
test "$sha1" = "$sha2"
jar --describe-module --file "$OUT/candidate-second.jar" > "$OUT/module.txt"
grep -q '^net.neoforged.accesstransformer' "$OUT/module.txt"
unzip -p "$OUT/candidate-second.jar" META-INF/MANIFEST.MF > "$OUT/manifest.txt"
grep -q '^Implementation-Version: 10\.0\.1+' "$OUT/manifest.txt"
cat >> "$OUT/setup-evidence.txt" <<EOF
candidate_sha256=$sha2
reproducible=true
maven_coordinate=net.neoforged:accesstransformers:10.0.1-agent146
runtime_module=net.neoforged.accesstransformer
EOF
cat "$OUT/setup-evidence.txt"
