# AccessTransformers 10.0.1 source candidate

This directory does **not** vendor AccessTransformers classes into BootOptim. It records a source-level patch, reproducible source pin and a branch-only dependency bridge for evaluating one replacement of the exact NeoForge 1.21.1 AccessTransformers engine.

## Exact upstream, artifact and license

- Upstream: `neoforged/AccessTransformers`.
- Exact runtime artifact: `net.neoforged:accesstransformers:10.0.1`.
- Exact public source commit: `139da711070c67f7e62cc20ea43507aa216cc8c6` (`Bump major to 10.0`). The published 10.0.1 JAR manifest states `Implementation-Version: 10.0.1+139da711` and `Git-Commit: 139da711`; the candidate workflow downloads and SHA-verifies that artifact and fails closed unless those fields match. The same JAR contains `net/neoforged/accesstransformer/parser/AccessTransformerList.class`.
- The exact 10.0.1 source is the older monolithic engine on upstream's `10.0.x` line, **not** the later split `at-parser` implementation on `main`. The first candidate workflow deliberately caught this distinction before source tests were accepted.
- License: MIT. `LICENSE.txt` is copied from upstream and the workflow byte-compares it with the pinned source before build. The patch also embeds the notice into the rebuilt fork JAR.

No decompiled source is used or redistributed.

## Exact stock algorithm

`AccessTransformerList.loadAT(CharStream)` keeps file parsing transactional. ANTLR parses the complete current file into an ordered `List<AccessTransformer>` first. Stock then:

1. copies the complete accumulated `HashMap<Target<?>, AccessTransformer>`;
2. merges current-file rules into that copy in visitor/list order;
3. scans **all** accumulated values for invalid final-state conflicts;
4. on success clears the live map and republishes the entire copy;
5. rebuilds the full `Set<Type>` target cache from every accumulated key.

A parse failure occurs before the state copy and leaves committed state untouched. A finality conflict is diagnosed after the full temporary merge and also leaves committed state untouched. `AccessTransformer.mergeStates` creates a new value and appends origins in left-then-right order, including the stock synthetic merge origin.

## Incremental correction and equivalence contract

The candidate keeps the same per-file transaction but stages only the targets touched by the current file in first-encounter order. The first occurrence of a target merges against committed state; repeated occurrences merge against the staged value. Because committed state is valid by construction, a newly invalid final state can only be on a touched target, so the successful validation scan is limited to staged values.

If a staged conflict exists, the candidate deliberately reconstructs the stock full temporary `HashMap` **only on the failure path**, inserts staged targets in first-encounter order, runs the existing full invalid scan/logger, and throws the same `IllegalArgumentException`. Thus committed state, first failing file, rule order, merge/finality semantics and ordered origin lists are unchanged.

On success, only staged mappings are published. Target `Type`s are monotonic and are added incrementally until `getTargets()` exposes its mutable set. Once a target set may have escaped, the next successful file falls back to stock's full target-set rebuild so retained references detach and caller mutations are discarded exactly as before. The first successful empty file still converts the initial immutable empty set into a mutable set, matching stock.

The candidate does **not** cache across runs, fingerprint prefixes, deduplicate files/rules, parse ahead, reorder input, or move work across threads.

## Tests and remaining semantic gates

The patch adds differential tests against a literal stock-reference implementation for sequential multi-file state/origins/inner-class targets, conflicting `+f`/`-f` rollback, malformed parser rollback, and the unusual mutable `getTargets()` exposure/detachment behavior. Upstream's own tests are run unchanged in the same source build.

Before promotion, add/retain a transformed-byte differential for representative class/field/method/wildcard/inner-class rules and a multi-conflict diagnostic-order assertion. `HashMap`/`HashSet` iteration order is not an API ordering contract, but the candidate intentionally reconstructs stock topology on the conflict path because diagnostics are observable.

## Packaging: replace the engine, never duplicate it

The changed class is inside the exact root module `net.neoforged:accesstransformers:10.0.1`, JPMS module `net.neoforged.accesstransformer`. The clean route is therefore a **single-module replacement** of that artifact with one version-pinned fork carrying the same JPMS module name. Keep stock `net.neoforged.accesstransformers:at-modlauncher:10.0.1`; do not add a second AccessTransformers engine JAR, do not put the fork in `mods/`, and do not shade its packages into BootOptim.

The branch-only `override.init.gradle` substitutes the exact engine dependency with a temporary local Maven coordinate only when `BOOTOPTIM_AT_OVERRIDE_REPO` and `BOOTOPTIM_AT_OVERRIDE_VERSION` are set. Normal BootOptim builds are unchanged. Hosted smoke exercises the replacement under ModDevGradle; duplicate modules/classes should fail launch rather than silently select one.

For an actual Prism/modpack distribution, the instance/version metadata must select the fork **instead of** the stock `accesstransformers:10.0.1` library. If the distribution mechanism cannot express one unambiguous replacement, this candidate is a no-ship even if source tests are green.

## Performance evidence boundary

PR #274 attributes 97 `FMLLoader.addAccessTransformer` calls to 266.609 ms of 269.009 ms in one hosted diagnostic. PR #275 measured `LoadingModList.addAccessTransformers` at 233.611 ms wall / 232.572 ms CPU inside a 286.449 ms Stage-2 run. The supplied physical attribution is 1,356.24 ms wall / 968.75 ms CPU. These are direct/inclusive attribution measurements, **not savings and not TTMM evidence**.

After equivalence tests and hosted smoke pass, the next performance gate is a separate exact-pack hosted A/B of stock vs fork with identical endpoints. Only a coherent critical-path signal justifies physical A/B; the laptop is not part of this candidate workflow.
