#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
UPSTREAM_COMMIT="139da711070c67f7e62cc20ea43507aa216cc8c6"
EXPECTED_LIST_BLOB="30706bda44951b044a90fc2c1d00a55ccd98abd2"
WORK_DIR="${RUNNER_TEMP:-/tmp}/bootoptim-agent145-access-transformers"

rm -rf "$WORK_DIR"
git init -q "$WORK_DIR"
git -C "$WORK_DIR" remote add origin https://github.com/neoforged/AccessTransformers.git
git -C "$WORK_DIR" fetch -q --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$WORK_DIR" checkout -q FETCH_HEAD

test "$(git -C "$WORK_DIR" rev-parse HEAD)" = "$UPSTREAM_COMMIT"
test "$(git -C "$WORK_DIR" rev-parse HEAD:src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java)" = "$EXPECTED_LIST_BLOB"

python3 "$ROOT_DIR/scripts/agent145/apply_at_oracle.py" "$WORK_DIR"

(
  cd "$WORK_DIR"
  ./gradlew test --no-daemon --tests net.neoforged.accesstransformer.parser.InProcessTopologyOracleTest
)
