# AccessTransformers 10.0.1 exact-topology source candidate

This branch does not shade AccessTransformers into BootOptim and does not add it as a mod. It records a source patch against the exact MIT upstream source and a branch-only dependency substitution used only by hosted validation.

## Exact source / artifact identity

- Runtime coordinate: `net.neoforged:accesstransformers:10.0.1`.
- Upstream: `neoforged/AccessTransformers`.
- Exact source commit: `139da711070c67f7e62cc20ea43507aa216cc8c6`.
- Published 10.0.1 manifest must report `Implementation-Version: 10.0.1+139da711` and `Git-Commit: 139da711`; CI also verifies the repository-published SHA-256 sidecar before accepting the source pin.
- Relevant source blob: `src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java` at blob `30706bda44951b044a90fc2c1d00a55ccd98abd2`.
- License: MIT, byte-compared in CI against upstream `license.txt` at the pinned commit and embedded in the rebuilt candidate JAR.
- JPMS module is unchanged: `net.neoforged.accesstransformer`.

## Why the Agent142 incremental publication is not used

The committed map remains a stock `HashMap` and the stock `new HashMap<>(state) -> merge -> clear -> putAll` publication is retained byte-for-structure. This is deliberate. Adversarial `Aa`/`BB` collision corpora can make an in-place/insertion-ordered candidate differ from the stock HashMap topology. That topology is observable through conflict diagnostic traversal, `getAccessTransformers()`, `getTransformersForTarget()`, and indirectly through the mutable/iterable `getTargets()` set. This candidate therefore does **not** try to remove the full-map copy or republication.

## Healthy-path correction

The patch removes/delays two O(accumulated-state) maintenance passes while keeping the stock map topology and transaction boundary:

1. **Validation scan:** committed `AccessTransformer` final-state fields are immutable and every committed state has already passed validation. After the current file is fully parsed and merged into the stock temporary HashMap, a new invalid final state can only be on a target touched by that file. Healthy files therefore test only the current file's final merged targets. If any touched target is invalid, the code immediately runs the original full `invalidTransformers(localATCopy)` traversal before logging/throwing, preserving exact HashMap diagnostic order and rollback.
2. **Target-set rebuild:** stock rebuilds `targetedClassCache` from the complete map after every successful file even if nobody can observe the intermediate Set. The candidate marks it dirty after the exact stock map publication and rebuilds from that exact map on the first subsequent `getTargets()`/`containsClassTarget()` observation. An escaped old Set is never mutated again, so a later successful file still detaches retained references and discards caller mutations exactly as stock. A successful empty file still yields a mutable empty Set when observed. Failed parse/merge/conflict publishes neither map nor target-set generation.

The two structural passes that establish HashMap topology — full temporary copy and clear/putAll republication — remain stock and are treated as the semantic floor for this candidate.

## Tests

The branch starts from PR #277 commit `6517d469f7722b0ced64400bbc0b9c3404ec0a2f`; its stock-vs-reference parser/origin/conflict/ASM contract stays unchanged. The source patch adds actual-candidate-vs-literal-stock tests covering public grouping iteration order, target-set iteration/mutability/identity, failed-file rollback, successful empty files, a 32-key `Aa`/`BB` collision corpus, and 160 deterministic multi-file property iterations. Upstream tests also run from the pinned source.

## Single-module replacement gate

The candidate is rebuilt from the pinned upstream commit into a temporary Maven repository. A Gradle init script substitutes only the root module `net.neoforged:accesstransformers` with `dev.bootoptim.atfork:accesstransformers:<candidate-version>`. CI fails before runtime unless resolved artifacts contain exactly one JAR with `module-info.class` naming `net.neoforged.accesstransformer`, exactly one provider of `AccessTransformerList.class`, and no resolved stock `net.neoforged:accesstransformers:10.0.1` engine alongside the fork. `net.neoforged.accesstransformers:at-modlauncher:10.0.1` remains stock and is not duplicated or replaced.

This is a hosted branch experiment, not a BootOptim production change. A distributable pack would have to replace the root engine library at the launcher/version-metadata level; adding the fork to `mods/`, JarJar, bootstrap, or classpath alongside stock is forbidden.

## Evidence boundary

#274/#275 timings are attribution only: 97 per-file calls consumed 266.609 ms hosted wall / 265.517 ms CPU in one high-detail probe; the lower-perturbation #275 run measured `addAccessTransformers` at 233.611 ms wall / 232.572 ms CPU inside Stage 2. The supplied physical observation is 1,356.24 ms wall / 968.75 ms CPU. None is a savings estimate. A smoke proves compatibility only. Any speed claim requires 3+3 hosted candidate/control with the same pack/JVM/endpoint and must be interpreted as TTMM, not as reduced maintenance counts.
