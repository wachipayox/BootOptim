# ModLauncher pre-Bootstrap redundancy audit — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / OPTIMIZATION NO-GO WITHOUT A NEW UPSTREAM CONTRACT**

Agent 75. Authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b` before branching.

This diagnostic is intentionally stacked on #207 head `46060e80087edac17801a4c3a5a313559533a61a`, and therefore depends on #207 -> #205 -> #203 -> #202 -> #201 -> #200. It must not be merged as production merely because CI is green.

## Scope and same-run starting evidence

#207 exact-pack profile `34284589550` measured the cross-thread phase from BootOptim's existing SERVICE `transformers()` callback to actual `net.minecraft.server.Bootstrap.bootStrap()` entry as 8317.044 ms inclusive. Its strict Bootstrap-transform edge split that phase into:

- SERVICE callback -> strict Bootstrap transform acceptance: 3187.324 ms, `main` -> `main`;
- strict Bootstrap transform acceptance -> actual Bootstrap entry: 5129.665 ms, `main` -> `pool-8-thread-1`.

These are phase wall intervals, not CPU sums or savings budgets.

The same exact-pack artifact identifies the exercised loader stack as:

- ModLauncher `11.0.5+main.901c6ea8`, upstream commit `901c6ea849ae21ee7d464cd97113e77a6101a734`;
- FML `4.0.43` / NeoForge `21.1.248`;
- Fabric Sponge Mixin `0.15.2+mixin.0.8.7`.

## Exact upstream route

At ModLauncher 11.0.5 commit `901c6ea8`, `Launcher.run` performs these operations after scan completion and GAME resources are collected:

1. add GAME jars to `ModuleLayerHandler`;
2. `TransformationServicesHandler.initialiseServiceTransformers()`;
3. `LaunchPluginHandler.offerScanResultsToPlugins(gameContents)`;
4. launch-target validation;
5. `buildTransformingClassLoader(...)`, which calls `ModuleLayerHandler.buildLayer(GAME, ...)`;
6. set the thread context classloader;
7. invoke the launch service.

`initialiseServiceTransformers()` delegates every transformation service's `transformers()` callback and registers returned targets in `TransformStore`. `TransformStore` already has an O(1)-style `HashSet` class prefilter (`classNeedsTransforming`) and label-indexed transformer lists; a generic target-membership cache is therefore not a plausible multi-second redesign.

GAME-layer construction is not a passive list copy. `ModuleLayerHandler.buildLayer` rebuilds `SecureJar` inputs, runs `Configuration.resolveAndBind`, creates the classloader, calls `ModuleLayer.defineModules`, publishes the completed layer, and wires fallback loaders. A persistent shortcut here would need an upstream-supported reusable module-resolution representation; reusing live `Configuration`/`ModuleReference` objects across JVMs is not a BootOptim-safe cache design.

Class definition enters `TransformingClassLoader.maybeTransformClassBytes`, which delegates to `ClassTransformer.transform`. For each transformed class the latter:

1. calls every launch plugin's `handlesClass` in current order;
2. checks the registered transformation-service target set;
3. parses input bytes to a `ClassNode` when work is required;
4. runs BEFORE launch-plugin callbacks;
5. runs transformation-service votes/transforms when targeted;
6. runs AFTER launch-plugin callbacks, including Mixin;
7. computes the final ASM writer flags and emits bytes.

The byte result is therefore downstream of stateful callbacks. It is not a pure function of raw bytes plus a simple mod-list hash.

## Historical evidence that constrains the optimization space

PR #42's target-pack transform profiler found:

- 34,775 `maybeTransformClassBytes` calls, 14.361 s exclusive transform wall;
- Mixin `processClassWithFlags(AFTER)` about 11.543 s total;
- the first `org.sinytra.connector.mod.DummyTarget` Mixin call triggered about 3.4 s of lazy Mixin selection/preparation;
- 1,197 rewrite calls consumed about 6.56 s inside Mixin;
- all other Mixin NO_REWRITE calls after excluding the first DummyTarget trigger were only about 0.774 s total.

PR #48 then measured the post-Mixin ModLauncher ASM writer tail on 1,199 rewritten classes at about 0.761 s total (`ClassNode.accept` about 0.735 s, `toByteArray` about 0.026 s). It is real but not a standalone seconds-scale target.

PR #43's generic Mixin side-load byte cache retained about 55 MiB for only about 41.7 ms estimated benefit. PR #46's confirmed ClassInfo negative-cache bug represented only about 4.7 ms of avoidable retry work.

These results rule out repeating broad side-load, negative-cache, target-membership or writer-tail caching as the primary front.

## Why a persistent final transformed-byte cache is still a no-go

PR #22 attempted a version-pinned persistent transformed Minecraft-class cache. #42 later established the semantic blocker: Mixin 0.8.7's `processClassWithFlags`/`MixinProcessor.applyMixins` performs stateful selection, coprocessor, target/config, plugin, extension and audit transitions while producing the transformed class.

Returning cached final bytes before those callbacks would skip observable loader state and callback effects. A full input/config fingerprint proves only that cached bytes were produced under a similar environment; it does not prove that the skipped transitions are semantically redundant on this launch.

Therefore BootOptim must not ship a cache that bypasses `processClassWithFlags`, launch-plugin callbacks, transformer voting, or current class-definition ordering.

## Safe observation design selected here

The first diagnostic step is intentionally outside the transformation pipeline: opt-in Java Flight Recorder `jdk.ExecutionSample` sampling, post-processed only after the Minecraft JVM exits.

`tools/boot-trace/analyze_prebootstrap_jfr.py` correlates JFR sample timestamps with the epoch origin and monotonic phase markers already emitted by the #200/#207 trace stack. It reports sample counts, threads, top frames and conservative package categories for exactly the three #207 phase windows.

The exact-pack harness does **not** enable JFR by default. It only notices a specifically named `bootoptim-prebootstrap.jfr` after process exit, analyzes it, and appends one compact summary record to the already-uploaded console log. Missing JFR is a no-op. Analyzer failure is fail-open and occurs after endpoint/resource validation, so it cannot alter launch callbacks, class bytes, classloader choice, module construction, scheduling, threads or the `main_menu` endpoint.

This is preferable to #42's launch-plugin wrappers for the current question: wrappers were useful and reached menu, but they mutate ModLauncher's live plugin map and necessarily add callback delegation/bookkeeping inside the path being measured. JFR sampling has lower coupling and no plugin identity/ordering risk.

## Candidate decision tree after same-run attribution

A seconds-scale candidate is acceptable only if the JFR profile puts material CPU in one of these buckets:

### 1. Mixin preparation/application remains dominant

BootOptim-side final-byte caching remains rejected. The maintainable optimization requires a new Mixin/upstream contract that separates **pure reusable preparation** from **per-launch state transitions**.

The useful upstream primitive would be a versioned immutable "compiled mixin plan" (parsed mixin bytecode, validated selectors/injection metadata and other demonstrably pure preparation artifacts) keyed by every input that affects that plan. On reuse, Mixin itself must still execute target selection, plugins, coprocessors, extensions, audit and application callbacks in stock order. BootOptim cannot safely infer or replay that state from the outside.

A weaker upstream primitive would expose an explicit side-effect-free eligibility/plan cache API with invalidation fingerprints supplied by Mixin/config plugins. Without such an API, reflection/coremod replacement of Mixin internals is not maintainable enough for production.

### 2. `Configuration.resolveAndBind` / GAME module-layer work is material

A BootOptim reflection replacement is rejected: it would duplicate JPMS resolution/binding semantics and publish custom live module objects. The required upstream primitive is either a supported reusable resolution descriptor or a loader-owned persistent cache whose validity is checked against exact `SecureJar` module descriptors/service bindings and parent configurations before stock `defineModules` publication.

### 3. Class loading/definition dominates outside Mixin

Investigate CDS/AppCDS only for classes whose bytes are not transformed or whose archived form is explicitly supported by the JVM/custom loader. Do not archive-and-return transformed GAME bytes as a way to bypass launch-plugin or transformation-service callbacks.

### 4. No bucket owns seconds

Close this front as distributed mandatory work. Do not introduce a coremod/service wrapper merely to shave microphases or counts.

## Explicit no-go mechanisms

Unless a new upstream contract changes a material premise, do not pursue:

- cached final transformed bytes returned before Mixin/plugin/service callbacks;
- reordering or parallelizing launch-plugin callbacks or transformer application;
- replacing the GAME classloader or module layer solely to intercept definitions;
- wrapping the live launch-plugin map in production;
- generic Mixin side-load caches, ClassInfo negative caches, target-membership caches, or ASM serialization caches as a seconds-scale claim;
- moving the first Mixin preparation trigger to another thread or earlier phase and calling the shift a reduction without identical-origin/end-to-end evidence.

## Validation contract for this PR

- diagnostic stack dependency is explicit (#200 -> #201 -> #202 -> #203 -> #205 -> #207 -> this PR);
- Python unit test verifies phase-window correlation and conservative sample categorization;
- normal build/package and startup must remain green with JFR absent;
- hosted exact-pack profile enables JFR explicitly and must reach the same `main_menu` endpoint with zero BootOptim Mixin errors and valid resource selection;
- JFR sample counts are CPU attribution only, not wall-clock savings;
- no physical laptop run is requested at this diagnostic stage.
