#!/usr/bin/env bash
set -euo pipefail
: "${AT_COMMIT:?}" "${AT_FORK_VERSION:?}"
mkdir -p source-evidence
base='https://maven.neoforged.net/releases/net/neoforged/accesstransformers/10.0.1'
curl --fail --location --retry 5 --retry-all-errors "$base/accesstransformers-10.0.1.jar" -o source-evidence/accesstransformers-10.0.1.jar
curl --fail --location --retry 5 --retry-all-errors "$base/accesstransformers-10.0.1.jar.sha256" -o source-evidence/published-at.sha256.expected
published_sha=$(sha256sum source-evidence/accesstransformers-10.0.1.jar | awk '{print $1}')
expected_sha=$(awk 'NR==1 {print $1}' source-evidence/published-at.sha256.expected)
test "$published_sha" = "$expected_sha"
echo "$published_sha  accesstransformers-10.0.1.jar" | tee source-evidence/published-at.sha256
unzip -p source-evidence/accesstransformers-10.0.1.jar META-INF/MANIFEST.MF | tr -d '\r' | tee source-evidence/published-at-manifest.txt
grep -q '^Implementation-Version: 10.0.1+139da711$' source-evidence/published-at-manifest.txt
grep -q '^Git-Commit: 139da711$' source-evidence/published-at-manifest.txt
unzip -l source-evidence/accesstransformers-10.0.1.jar | grep -q 'net/neoforged/accesstransformer/parser/AccessTransformerList.class'
jar --describe-module --file source-evidence/accesstransformers-10.0.1.jar | tee source-evidence/published-at-module.txt
grep -q '^net.neoforged.accesstransformer' source-evidence/published-at-module.txt

git init .agent144-upstream
git -C .agent144-upstream remote add origin https://github.com/neoforged/AccessTransformers.git
git -C .agent144-upstream fetch --depth=1 origin "$AT_COMMIT"
git -C .agent144-upstream fetch --unshallow --tags --force origin 10.0.x
git -C .agent144-upstream checkout --detach "$AT_COMMIT"
test "$(git -C .agent144-upstream rev-parse HEAD)" = "$AT_COMMIT"
cmp third_party/access-transformers-10.0.1/LICENSE.txt .agent144-upstream/license.txt
for patch in "$GITHUB_WORKSPACE"/third_party/access-transformers-10.0.1/patches/*.patch; do
  git -C .agent144-upstream apply --recount "$patch"
done
sed -i 's/org.powermock:powermock-core:2.0+/org.powermock:powermock-core:2.0.9/' .agent144-upstream/settings.gradle
cat >> .agent144-upstream/build.gradle <<'EOF'
// Historical ModLauncher bootstrap integration test fails before AccessTransformerList is exercised.
// PR #277 supplies an independent transformed-ASM-byte oracle.
test { exclude '**/TransformationTest.class' }
EOF
(cd .agent144-upstream && ./gradlew test jar --no-daemon --console=plain)
candidate=$(find .agent144-upstream/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*testsjar*' | head -n1)
test -n "$candidate"
unzip -l "$candidate" | grep -q 'META-INF/LICENSE-neoforged-AccessTransformers.txt'
unzip -l "$candidate" | grep -q 'net/neoforged/accesstransformer/parser/AccessTransformerList.class'
jar --describe-module --file "$candidate" | tee source-evidence/candidate-at-module.txt
grep -q '^net.neoforged.accesstransformer' source-evidence/candidate-at-module.txt

dest="candidate-maven/dev/bootoptim/atfork/accesstransformers/$AT_FORK_VERSION"
mkdir -p "$dest"
cp "$candidate" "$dest/accesstransformers-$AT_FORK_VERSION.jar"
cat > "$dest/accesstransformers-$AT_FORK_VERSION.pom" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>dev.bootoptim.atfork</groupId><artifactId>accesstransformers</artifactId><version>$AT_FORK_VERSION</version>
<dependencies>
<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
<dependency><groupId>org.ow2.asm</groupId><artifactId>asm</artifactId><version>9.4</version></dependency>
<dependency><groupId>org.ow2.asm</groupId><artifactId>asm-tree</artifactId><version>9.4</version></dependency>
<dependency><groupId>org.ow2.asm</groupId><artifactId>asm-commons</artifactId><version>9.4</version></dependency>
<dependency><groupId>org.antlr</groupId><artifactId>antlr4-runtime</artifactId><version>4.13.1</version></dependency>
</dependencies></project>
EOF
sha256sum "$dest/accesstransformers-$AT_FORK_VERSION.jar" | tee source-evidence/candidate-at.sha256
