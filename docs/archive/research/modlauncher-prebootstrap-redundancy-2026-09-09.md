# ModLauncher pre-Bootstrap redundancy audit — 2026-09-09

Status: **COMPLETE DIAGNOSTIC / LOCAL SECONDS-SCALE OPTIMIZATION NO-GO WITHOUT A NEW UPSTREAM CONTRACT**

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

`tools/boot-trace/analyze_prebootstrap_jfr.py` correlates JFR sample timestamps with the epoch origin and monotonic phase markers already emitted by the #200/#207 trace stack. It reports sample counts, threads, top frames, top-frame categories and full-stack semantic ownership for exactly the three #207 phase windows.

The exact-pack harness does **not** enable JFR by default. It only notices a specifically named `bootoptim-prebootstrap.jfr` after process exit, analyzes it, and appends one compact summary record to the already-uploaded console log. Missing JFR is a no-op. Analyzer failure is fail-open and occurs after endpoint/resource validation, so it cannot alter launch callbacks, class bytes, classloader choice, module construction, scheduling, threads or the `main_menu` endpoint.

This is preferable to #42's launch-plugin wrappers for the current question: wrappers were useful and reached menu, but they mutate ModLauncher's live plugin map and necessarily add callback delegation/bookkeeping inside the path being measured. JFR sampling has lower coupling and no plugin identity/ordering risk.

## Hosted exact-pack attribution result

The repository's ordinary exact-pack workflow was unable to launch Minecraft for this PR because its fixture job downloaded and verified the pinned JCEF payload but lost a GitHub Actions cache-reservation race; the benchmark job then used `actions/cache/restore` with `fail-on-cache-miss` and aborted before the timed JVM. Re-running the failed job reproduced the same provisioning failure. This is not a product signal.

To avoid changing the timed JVM merely to work around that infrastructure race, commit `094b8e90701c92b476fed8580b598d2b6cd5e62b` added a branch-only diagnostic workflow that downloads and SHA-verifies the same exact-pack fixture and the same pinned JCEF payload directly **before** launching the benchmark. `Agent75 Exact Pack JFR` run `34288040085` completed successfully:

- same exact-pack fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- Oracle JDK 25.0.4 runtime, `ActiveProcessorCount=4`, llvmpipe;
- BootOptim trace origin `hosted_exact_pack`, endpoint `main_menu`;
- main menu reached at 93,070 ms;
- BootOptim Mixin errors: 0;
- resource-selection contract valid, one reload, expected and observed pack lists identical;
- JFR summary produced after process exit.

The JFR run is **not** an end-to-end performance comparison. Its phase walls differ from #207 and JFR itself is an observer, so no regression or saving is inferred from those wall values. They are used only to bound the CPU attribution:

- parent SERVICE-transformers -> Bootstrap entry: 9,744.332 ms, 722 execution samples;
- SERVICE-transformers -> strict Bootstrap transform acceptance: 3,707.624 ms, 347 samples;
- strict Bootstrap transform acceptance -> Bootstrap entry: 6,036.645 ms, 375 samples.

### Before strict Bootstrap transform acceptance

The 347 samples are not one serial transformation-service block:

- `main`: 153 samples;
- three `background-scan-handler-*` threads: 177 samples total;
- `bootoptim-scan-cache-writer`: 16 samples;
- async logger: 1 sample.

On `main`, full-stack exclusive ownership was: `other` 42, JPMS 27, ModLauncher 24, zip/I/O 16, ASM 14, module classloading/JarHandling 13, Mixin 10, JDK classloading 6, access transformer 1. JPMS + ModLauncher + module-loader ownership therefore accounts for 64/153 main-thread samples (~41.8%), with direct stacks through `Configuration.resolveAndBind`, `ModuleLayerHandler.buildLayer`, `ModuleLayer.defineModules`, `ModuleClassLoader` and JarHandling.

The 177 background-scan samples are real CPU overlap, with stacks through `net.neoforged.fml.loading.modscan.Scanner`, mod-file scanning, ASM parsing and zipfs. They are **not** added to the main-thread wall budget: JFR sample sums across concurrently running threads are not a critical-path saving. Changing their scheduling or scan callbacks is outside this front and would violate the task's semantic/scheduling constraint without a separate proof.

This result makes GAME-layer resolution/construction the largest identifiable main-thread owner before transform acceptance, but not a demonstrated multi-second pure cache. Its public objects are live JPMS/module-reader/classloader state, so BootOptim has no maintainable persistent shortcut that preserves stock publication semantics.

### Strict Bootstrap transform acceptance to actual Bootstrap entry

This window is much more serial: 350/375 samples are on `main`, with 25 on `Datafixer Bootstrap`.

Full-stack attribution is transformation-heavy but distributed. Mixin is the largest single semantic owner: 121 samples contain `org.spongepowered.asm` frames, including config selection/preparation, `MixinInfo` validation, `ClassInfo`, `getClassNode` side-loads, applicator/injector work and actual application. ModLauncher owns another 26 samples; access-transformer/FML ASM paths are also present; 68 samples are rooted in ASM without a deeper owner visible in the captured stack. Combining those transformation-related buckets identifies a seconds-scale aggregate, but not a pure subroutine that can be bypassed while preserving callbacks.

A concrete redundant micro-path was also exposed: exact Mixin 0.8.7's `Bytecode.getOpcodeName` reflectively walks `Opcodes.getDeclaredFields()` and calls `Field.getInt` for opcode-name lookup. The JFR window contains 39 Mixin samples with this utility (36 ending in `Bytecode.getOpcodeName`). Newer Fabric Mixin source uses a direct opcode-name table instead. This is a credible version-pinned micro-optimization/backport, but the sample share is sub-second-scale for this phase; it does **not** satisfy this task's requirement to prioritize a seconds-moving hypothesis, so no invasive coremod patch is proposed here.

## Final decision

**No-go for a BootOptim-local seconds-scale optimization of the #207 pre-Bootstrap front under the required semantic constraints.** The hosted profile does not reveal a maintainable pure boundary large enough to justify replacing transformation services, launch plugins, the GAME classloader, or Mixin state transitions.

The only two mechanisms with credible seconds-scale aggregate ceilings require ownership upstream:

1. **Mixin-owned reusable preparation contract.** Mixin would expose/version an immutable compiled preparation plan for demonstrably pure work (parsed mixin class data, validated selectors/injection metadata, immutable target-independent analysis). The cache key and invalidation inputs must be supplied by Mixin/config plugins. On reuse, stock per-launch selection, config/plugin/coprocessor/extension/audit callbacks and class application still execute in the same order on fresh mutable class state. BootOptim must not infer that contract by reflecting into 0.8.7 internals.
2. **ModLauncher/JarHandling-owned module-resolution descriptor cache.** Loader code would validate exact `SecureJar` module descriptors, service bindings and parent configuration identity, then reconstruct fresh live JPMS/classloader state through a supported API. BootOptim must not persist/reuse live `Configuration`, `ModuleReference`, `ModuleReader` or classloader instances across launches.

If neither upstream contract is available, this phase should be treated as distributed mandatory startup work rather than wrapped/reordered/cached from BootOptim.

No physical-laptop run is justified: this PR is diagnostic and found no hosted optimization candidate. No A/B optimization run is claimed or required because no candidate was enabled.

## Explicit no-go mechanisms

Unless a new upstream contract changes a material premise, do not pursue:

- cached final transformed bytes returned before Mixin/plugin/service callbacks;
- reordering or parallelizing launch-plugin callbacks or transformer application;
- replacing the GAME classloader or module layer solely to intercept definitions;
- wrapping the live launch-plugin map in production;
- generic Mixin side-load caches, ClassInfo negative caches, target-membership caches, or ASM serialization caches as a seconds-scale claim;
- moving the first Mixin preparation trigger to another thread or earlier phase and calling the shift a reduction without identical-origin/end-to-end evidence.

## Validation contract and status

- diagnostic stack dependency is explicit (#200 -> #201 -> #202 -> #203 -> #205 -> #207 -> this PR);
- Python unit coverage verifies phase-window correlation, nanosecond timestamp parsing, top-frame categorization and full-stack semantic ownership;
- normal build/package and startup were green before the hosted profile, with JFR absent;
- hosted direct exact-pack profile `34288040085` reached the same `main_menu` endpoint with zero BootOptim Mixin errors and valid resource selection;
- the ordinary exact-pack workflow failure is a pre-JVM JCEF cache-reservation infrastructure failure and is recorded separately from the successful direct pinned gate;
- JFR sample counts are CPU attribution only, not wall-clock savings;
- no physical laptop run is requested at this diagnostic stage.
