# Structured trace stacked-PR integration audit — 2026-09-08

Status: **DIAGNOSTIC INTEGRATION PLAN / NO PERFORMANCE PROMOTION**

Authority audited: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

Scope is limited to the existing structured trace stack: PR #200 (trace-core), #201 (SERVICE/ModDev classpath + FML Discovery producers), and #202 (game-layer `CommonModLoader.begin` gather boundary). This audit adds no trace phase, no scheduling change, no OpenGL/render work, no callback/thread change, and no gameplay change.

## Findings

### 1. The stack is a real linear ancestry, despite all three PRs targeting integration

The current heads form a strict ancestor chain:

- integration `fa6df8bc8f74aae32338f521bf845a5730ac634b`
- #200 `e4132e46eb0bb190e5f2264fac31f45411451682` — 3 commits ahead of integration
- #201 `a957b77c58c52edf1bccf68c5bc394cd84432e51` — 6 commits ahead of #200, 0 behind
- #202 `c0f995a7afa5480f3eff91f18decb5fd9f5bb619` — 11 commits ahead of #201, 0 behind

Therefore the current GitHub diffs against integration intentionally include their ancestors. They must not be merged out of order and they should be rebased after each predecessor lands so review sees only the intended delta.

### 2. #201's final SERVICE repair is the correct net build layout

#200's original bootstrap layout used `implementation project(':trace-core')` and copied `:trace-core` output into the packaged bootstrap JAR. That is sufficient for the distributable wrapper, but not for ModDev's SERVICE module, which is assembled from `project(':bootstrap').sourceSets.main` output.

#201's final tree instead does this in `bootstrap/build.gradle`:

```gradle
sourceSets.main.java.srcDir(rootProject.file('trace-core/src/main/java'))
```

and removes both the bootstrap `implementation project(':trace-core')` dependency and the extra JAR copy step. The root regular mod continues to compile against the standalone `:trace-core` project. The development mapping exposes only the root mod plus one `boot_optim_bootstrap` source set; it does not expose `trace-core` as a second local mod/module.

This is materially different from the failed intermediate state. Hosted run `34273167016` reached a module `ResolutionException` while a second trace-core development mapping still existed; commit `13b12efcf49e50d6990152db2707c35a7035b566` removed that duplicate development path. Do not resurrect that mapping while rebasing.

### 3. Distributed packaging currently has one physical trace implementation

Build run `34273366644`, artifact `10074729302` (`sha256:2ddc4112bf0c54d2cbfbbbf82914aa52477b8fdfeed21e7d997d3fb1ac3170e5`) was inspected.

The distributable `bootoptim-ci.jar` contains `dev/wachipayox/bootoptim/trace/StructuredBootTrace.class` once in the outer bootstrap wrapper. Its nested regular-mod JAR `META-INF/jarjar/dev.wachipayox.bootoptim.boot_optim-0.1.0.jar` contains no `dev/wachipayox/bootoptim/trace/` classes.

That packaging shape avoids the PR #198 dual-writer/dual-sequence architecture and avoids two physical trace implementations in the shipped artifact.

### 4. Current implemented producers use one bootstrap-side global

`StructuredBootTrace` owns a static singleton:

```java
private static final StructuredBootTrace GLOBAL = fromSystemProperties();
```

The #201 Discovery producer and #202 FML gather producer both resolve `StructuredBootTrace.global()` from bootstrap code. #202's transformer is also installed by the bootstrap transformation service and is absent in `off` mode.

Final-head #202 exact-pack profile run `34278957632`, artifact `10076967670` (`sha256:99b86b7dee90dc26dd15cac9a21f1ad99017f9ea1aac5bc6ed033439f78917a6`), contains exactly one schema-v1 header, six events, and one summary: three balanced task pairs, zero dropped events, zero flush failures, zero development-sink failures, and zero trace errors. Task 3 (`fml_gather_and_initialize_mods`) depends on task 2 (`dependency_discovery`). This is functional trace evidence only; its timings are inclusive/profile telemetry and are not a performance improvement.

### 5. One identity claim is still narrower than the documentation wording

The shipped wrapper has one physical `StructuredBootTrace`, and all *currently implemented* producers live on the bootstrap side. However, ModDev still builds the standalone `:trace-core` output because the root regular mod has `implementation project(':trace-core')` while bootstrap independently compiles the same Java source into its own source-set output.

No current regular-mod producer calls `StructuredBootTrace.global()`, so the hosted profiles do not prove what class/module identity a future regular-mod caller would resolve in ModDev, nor that this resolution exactly matches the packaged wrapper's single outer copy. The source is identical, but Java static identity is classloader/module identity, not source-file identity.

This is **not a blocker to the current #201/#202 producers**, because they are bootstrap producers and the observed JSONL is single-sequence. It **is a blocker to claiming that arbitrary future regular-mod direct trace calls are already proven to share the same singleton in both ModDev and packaged runtime**.

Do not change root `implementation` to `compileOnly`, add a bridge API, duplicate the writer, or add a production hook merely to make that claim without a ModLauncher-layer runtime proof. That would exceed this audit's scope and could turn a diagnostics cleanup into a classloading change.

## Recommended merge/rebase sequence

### Step 1 — review and merge #200 first

Base: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

Expected #200 head before merge: `e4132e46eb0bb190e5f2264fac31f45411451682`.

Required gates after any rebase or review edits:

- `./gradlew build` / Build workflow, including `trace-core` JUnit tests and Python analyzer tests;
- packaged bootstrap assertion that `StructuredBootTrace.class` exists;
- default-off Startup Benchmark reaches menu;
- hosted exact-pack smoke with trace **off** reaches menu.

Do not interpret green CI as a performance promotion; #200 is diagnostic infrastructure.

### Step 2 — rebase #201 onto the post-#200 integration head

Do not merge #201 in its current all-ancestors diff before #200. After #200 is integrated, rewrite/rebase #201 so its PR diff contains only the final #201 net delta.

The final delta should retain:

- FML Discovery producer changes in `DiscoveryProfiler`;
- final `bootstrap/build.gradle` SERVICE layout that compiles `trace-core/src/main/java` into bootstrap main output;
- root regular-mod dependency on standalone `:trace-core` and only one `boot_optim_bootstrap` ModDev source-set mapping;
- pack-local trace path and trace JSONL artifact collection;
- the SERVICE classpath research record.

When rewriting history, squash or drop the obsolete intermediate classpath experiments rather than replaying them as mergeable states. In particular, do not leave a commit that adds `trace-core` as a second local development module.

Required gates on the rebased final tree:

- Build/package, including exact check that outer bootstrap contains one `StructuredBootTrace.class` and nested regular mod contains no trace classes;
- normal default/off Startup Benchmark to menu;
- exact-pack **profile smoke** (not A/B) with one header/summary, balanced Discovery pairs, zero drops/flush/sink/errors, and no `NoClassDefFoundError` or module `ResolutionException`;
- exact-pack **off** smoke if review changes affect Gradle/ModDev mapping, to ensure diagnostic activation is not required for startup.

### Step 3 — rebase #202 onto the post-#201 integration head

Expected current head: `c0f995a7afa5480f3eff91f18decb5fd9f5bb619`.

The current net delta from #201 is limited to the FML gather transformer/hooks/test, small Discovery predecessor exposure, transformation-service registration, and its research record. Rebase/squash away the discarded direct-FML and broadened-matcher attempts so the reviewable history represents only the surviving `CommonModLoader.begin` design.

Required gates:

- transformer unit test proves `begin hook -> original gatherAndInitializeMods -> end hook` and ignores the bootstrap/SERVICE FML `ModLoader` target;
- Build/package;
- default/off Startup Benchmark to menu, with no FML transformer installed in `off` mode;
- exact-pack profile smoke reproducing one schema/header/summary, three balanced task pairs, dependency edge `dependency_discovery -> fml_gather_and_initialize_mods`, zero drops/errors;
- no physical benchmark: this is diagnostic-only and has no performance candidate.

## Benchmark contamination rule

`profile` and `development` modes must never be used as timing evidence for optimization A/B. The profile smokes above validate observability and classloading only.

`off` is the default and #202 installs no FML transformer in that mode. The existing Discovery profiler can still reference the trace class while its older startup profiling/benchmark flags are active, so this audit does not claim mathematically zero diagnostic overhead. Performance branches should not inherit/enable profile telemetry, and no profile number should be subtracted from TTMM or treated as savings.

`benchmark` trace mode is a separate explicitly selected diagnostic mode whose contract is counter/task-id only; it is not the default performance baseline.

## Decision

The stack is **organizable into a clean merge sequence** without reintroducing duplicate writers or modules: #200, then rebased #201 final net state, then rebased #202 final net state.

No code change is justified by this audit before those rebases. The minimum integration action is history cleanup plus the packaging/ModDev gates above.

The only unresolved architectural proof is future **regular-mod direct caller identity in ModDev versus packaged runtime**. Do not block the current bootstrap-only producers on that hypothetical extension, but do not document the regular mod as already proven to share the same singleton until a bounded classloader/module identity test exists. If a future trace PR introduces a regular-mod producer, that proof becomes a mandatory prerequisite before merge.

This decision is diagnostic only. Green CI permits review; it does not promote tracing to production performance behavior or establish a startup improvement.
