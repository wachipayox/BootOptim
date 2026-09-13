# AccessTransformers 10.0.1 source candidate

This directory does **not** vendor AccessTransformers classes into BootOptim. It records a source-level candidate patch and provenance for building a single replacement parser module for the exact NeoForge 1.21.1 dependency.

## Upstream and exact version

- Upstream: https://github.com/neoforged/AccessTransformers
- Published runtime artifacts: `net.neoforged:accesstransformers:10.0.1`, `net.neoforged.accesstransformers:at-parser:10.0.1`, and `net.neoforged.accesstransformers:at-modlauncher:10.0.1`.
- Source commit used by this candidate: `8274f92c0f11ef1a95cc0d78c2ba3a15783e105c` (`Use absolute paths in origin names (#10)`). It is the last upstream commit before the 10.0.1 publication window and the next source commit is `90f1408667758034e10a1963564ae67c0ae1a548` on 2024-11-23. The candidate workflow additionally checks the published 10.0.1 parser JAR manifest `Git-Commit` against `8274f92` before building; a mismatch fails closed.
- Historical base tag: `10.0` -> commit `ce0a186070ec31f41c86f43c2676ef2d52bba7a0`. NeoForged GradleUtils derives the patch release version from Git history; there is no `10.0.1` Git tag in the upstream tag set.
- License: MIT. The exact upstream notice is retained as `LICENSE.txt` and is also embedded into the rebuilt parser JAR by the candidate patch.

No decompiled source is used. The patch applies to the public upstream source commit above.

## Algorithmic correction

Upstream 10.0.1 `AccessTransformerFiles.loadAT` starts every file by copying the complete accumulated `HashMap<Target, Transformation>`, parses/merges into that copy, scans every accumulated transformation for invalid final-state conflicts, clears and republishes the entire global map, then rebuilds every target class from the entire key set.

The candidate preserves the per-file transaction but makes its successful hot path proportional to the current file:

1. Parse the file in stock line/rule order into a `LinkedHashMap` overlay containing only touched targets. A repeated target merges against the previous staged value; its first occurrence merges against the committed value. `Transformation.mergeStates` is unchanged, so modifier precedence, finality and ordered origin strings are unchanged.
2. The committed state is an invariant-valid state because no file is published before validation. Therefore a new invalid final state can only occur on a touched target. Successful validation checks only staged values.
3. If any staged value is invalid, reconstruct a full stock-style temporary `HashMap` **only on that failure path**, inserting new touched keys in first-encounter order, then call the existing full invalid-map scan/logger and throw the same `IllegalArgumentException`. The committed map/targets remain untouched.
4. On success, `putAll` publishes only touched mappings. The public `getAccessTransformers()` object remains the same unmodifiable live view.
5. Target class names are monotonic under successful AT loads. They are added incrementally while no `getTargets()` set has escaped. If `getTargets()` has been observed, the next successful file deliberately falls back to a full rebuild from the authoritative transformation map before publication; this detaches retained references and discards caller mutations exactly as stock does. The initial immutable empty set becomes mutable after the first successful load, including an empty file, matching stock behavior.

The candidate does not cache across launches, deduplicate AT files/rules, reorder files/rules, parse ahead, or move work to another thread.

## Equivalence contract and tests

The patch adds differential tests against a literal stock-reference implementation for:

- sequential multi-file merge state, ordered `origins()` and target membership;
- duplicate/touched targets and inner-class-generated targets;
- conflicting `+f`/`-f` finality: same exception text and whole-file rollback;
- malformed input: same parser exception class/message/line and rollback;
- the unusual mutable `getTargets()` exposure: caller mutation remains visible through the escaped old set, but is discarded from the newly published target set on the next successful file.

Further promotion gating must compare transformed class bytes for representative class/field/method/wildcard/inner-class ATs and capture invalid-target log ordering under multiple conflicting targets. HashMap/HashSet iteration order is not an API ordering guarantee; file/rule order and origin order are preserved.

## Packaging rule: replace, never add beside stock

Only `at-parser` contains the changed class. The clean pack route is a **single-module replacement**: replace the dependency edge `net.neoforged.accesstransformers:at-parser:10.0.1` with one version-pinned fork JAR carrying the same JPMS module name `net.neoforged.accesstransformer.parser`. Keep stock `accesstransformers` and `at-modlauncher` 10.0.1. Do not place the fork beside the stock parser JAR and do not shade these packages into BootOptim.

The branch-only Gradle bridge substitutes the parser dependency to a temporary local Maven coordinate only when `BOOTOPTIM_AT_PARSER_OVERRIDE_REPO` and `BOOTOPTIM_AT_PARSER_OVERRIDE_VERSION` are set. Normal BootOptim builds are unchanged. The candidate workflow checks dependency resolution and a hosted exact-pack smoke; a module/class duplication would fail the dependency gate or JPMS launch.

For an actual modpack distribution, publish the fork as a pinned binary with source/commit/license metadata and make the launcher/version manifest select it **instead of** the stock `at-parser:10.0.1`. If the launcher cannot express a single replacement cleanly, this candidate is not distributable through that launcher and must remain unshipped.

## Performance evidence boundary

PR #274 attributed 97 `FMLLoader.addAccessTransformer` calls to 266.609 ms of 269.009 ms in one hosted diagnostic. PR #275 measured `LoadingModList.addAccessTransformers` at 233.611 ms wall / 232.572 ms CPU inside a 286.449 ms Stage-2 run. The physical attribution supplied for this investigation is 1,356.24 ms wall / 968.75 ms CPU. These are inclusive/direct attribution measurements, **not savings** and not TTMM A/B evidence.

The next performance gate, only after source tests and smoke pass, is a separate hosted exact-pack A/B with candidate vs stock using identical endpoints. Physical A/B is justified only if hosted shows a coherent critical-path signal.
