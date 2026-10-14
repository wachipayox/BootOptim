# NeoForge/FML + ModLauncher lifecycle critical-path research — 2026-09-04

Status: **ACTIVE / DIAGNOSTIC ONLY**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

Scope: startup work before and around the initial client resource reload that belongs to NeoForge/FML lifecycle dispatch, ModLauncher/classloading, Mixin, mod discovery, and scheduling. This entry deliberately excludes already-measured ModelManager optimizations and does not reopen rejected Mixin side-load memoization or the external ASM writer-tail experiment.

## Existing evidence that constrains this investigation

The research ledger and PR history were checked before code changes.

Do **not** repeat these mechanisms without a materially changed premise:

- PR #43: generic Mixin transformer-loader side-load memoization. Only `375 / 10,120` calls hit (~3.71%), with about `41.7 ms` estimated saved and ~`55 MiB` retained. **REJECTED**.
- PR #46: Mixin `ClassInfo` negative-cache bug exists, but the exact pack had only seven relevant retries, about `4.7 ms` avoidable. **REJECTED as startup target**.
- PR #48: ModLauncher final writer tail after Mixin was measured exactly: about `734.650 ms` in `ClassNode.accept`, `26.084 ms` in `toByteArray`, `760.734 ms` total for 1,199 rewrite classes. **REJECTED as a hidden multi-second target**.
- PR #19: generic parallel prewarming of 200 `Blocks` fan-out classes succeeded mechanically but regressed discovery→entrypoint about `2.20%` and TTMM about `1.11%`. Extra classloading concurrency is therefore not presumed beneficial on the four-thread target.
- Issue #67 / old PR #22: persistent post-Mixin transformed-class caching remains architecturally interesting, but BootOptim has no supported ModLauncher hook to decorate/replace the `mixin` launch plugin. Production must not crack module encapsulation merely to implement this cache.
- Current production already has a persistent, fail-open FML `ModFile.compileContent()` scan cache. Residual discovery research must distinguish work outside `compileContent()` and must distinguish cold CI from a warm user launch.

Historical laptop data also matters for interpretation:

- fast-PC `mod_entrypoint`: ~`28.541 s`; old laptop: ~`112.594 s` (~3.94x scaling);
- old-laptop JFR allocation samples: `modloading-sync-worker` ~`20.38%`, but allocation share is **not** wall time or CPU time;
- compiler-thread time was large in aggregate, but JIT compiler time is CPU accumulated across compiler threads, not recoverable wall by itself;
- the old laptop JFR did not include useful `ClassLoad` / `ClassDefine` events, so classloading-specific attribution is still incomplete.

## Current hosted exact-pack reference

For a very recent hosted smoke (PR #96, same integration base), the exact-pack result was:

- `mod_entrypoint_ms = 25,754`;
- `main_menu_ms = 77,799`;
- `post_mod_entrypoint_ms = 52,045`;
- root mod discovery: `476.726 ms`;
- dependency discovery: `6,445.528 ms`.

Important cold-cache qualification:

- that hosted fixture produced `240` scan-cache misses, `0` hits, plus one stock/nonregular scan;
- `241` scans completed;
- the `compileContent()` scan window reached ~`3,591.595 ms`.

Therefore the ~6.446 s dependency-discovery interval is useful as a **cold software-pack wall observation**, but it is not evidence that a warm production scan-cache path still has 6.446 s available. The exact-pack fixture is reconstructed for the job and does not preserve the user's `.bootoptim` cache across runs.

## Source architecture: discovery and background scan

Exact FML 1.21.1 source establishes two distinct pieces of work.

`FMLLoader.completeScan(...)` performs language-provider setup and calls `modValidator.stage2Validation()`. This creates the `BackgroundScanHandler`, sets `loadingModList`, and returns the discovered game resources to ModLauncher.

`BackgroundScanHandler` then scans mod content asynchronously with a fixed pool of:

```text
max(1, FML maxThreads - 1)
```

The source comment explicitly leaves one thread for Minecraft bootstrap. Each file runs `ModFile.compileContent()` on this pool, and `ModLoader.gatherAndInitializeMods(...)` later calls `waitForScanToComplete(...)` before building mod containers.

Consequences for a 4-thread exact-pack/laptop configuration:

1. background class-metadata scanning can occupy three workers;
2. main-thread ModLauncher/Mixin work can execute concurrently as the fourth active CPU consumer;
3. wall observed inside either lane can overlap the other lane;
4. adding more speculative prewarm work is especially likely to increase contention rather than shorten the gate.

The production BootOptim scan cache only replaces the body of `ModFile.compileContent()`. It does **not** automatically cache all candidate locator, JarJar/nested dependency, Connector, metadata validation, module-layer construction, language-provider loading, or ModLauncher plugin work.

## Source architecture: ModLauncher transformation path

ModLauncher constructs a parallel-capable `TransformingClassLoader`. Every transformed class flows through `ClassTransformer.transform(...)`.

The significant sequence is:

1. compute launch-plugin transformer set;
2. parse input bytes into `ClassNode` if any plugin/transformer needs the class;
3. launch-plugin BEFORE processing;
4. ordinary ModLauncher transformers/voting;
5. launch-plugin AFTER processing (Mixin is here for normal non-empty classes);
6. final `TransformerClassWriter` only when rewriting is required.

The final writer in step 6 is already bounded by PR #48 and is not the new target.

Mixin's ModLauncher adapter also adds an important scheduling constraint: `MixinLaunchPluginLegacy.processClass(...)` synchronizes over its processor list, and `MixinTransformationHandler.processClass(...)` itself is synchronized. Generic attempts to make many FML class loads concurrent therefore do **not** imply that expensive Mixin apply work will scale across those threads; they can instead queue on Mixin plus compete for CPU in the work around that critical section.

## Source architecture: Mixin lazy transformer creation

Mixin's `MixinTransformationHandler` receives an `IMixinTransformerFactory`, but does not create the transformer immediately. On the first `processClass(...)` it lazily executes:

```text
transformerFactory.createTransformer()
```

and then obtains the synthetic-class registry.

Historical PR #42 attributed about `3.18 s` to Mixin config preparation in the initial `DummyTarget` path on the fast reference. This is real compulsory work, but a scheduling optimization is **not yet justified**:

- ModLauncher cannot offer all game resources to launch plugins until discovery has produced them;
- FML starts background `compileContent()` work at stage-2 validation, before game launch;
- thus Mixin initialization/preparation can already overlap the background scan;
- on four processors the background scanner is intentionally sized to three workers, leaving approximately the main thread as the fourth CPU consumer.

So the attractive-sounding idea “start Mixin prepare earlier” may merely move work into an already saturated interval. Reopen it only if phase CPU/wall measurements show meaningful idle capacity or a warm-cache launch where scan workers finish substantially before Mixin prepare.

## Source architecture: serial mod-container entrypoint class loading

This is a materially different premise from PR #19's arbitrary class prewarm.

In `ModLoader.gatherAndInitializeMods(...)`, FML waits for background scanning and then creates mod containers with a normal serial stream:

```text
loadingModList.getModFiles().stream()
    .map(ModFileInfo::getFile)
    .map(ModLoader::buildMods)
    .<ModContainer>mapMulti(Iterable::forEach)
    .toList();
```

For Java mods, `FMLJavaModLanguageProvider` builds an `FMLModContainer`. The `FMLModContainer` constructor immediately loads every declared `@Mod` entrypoint class with:

```text
Class.forName(layer, entrypoint)
```

Only **after all containers are built** does FML enter `constructMods(...)`, which dispatches actual mod construction through its dependency-aware parallel DAG.

This creates a real serial window containing required entrypoint class definition/linking and any ModLauncher/Mixin transformation triggered by those classes. It was not measured by PR #19, which preloaded a fixed arbitrary `Blocks` fan-out before the normal demand point.

However, direct parallelization is high risk:

- Mixin transformation is synchronized as described above;
- `buildMods` also performs per-language-loader validation after containers are created;
- `ModLoadingContext` active-container state is manipulated while building/loading mods;
- third-party language loaders are not proven thread-safe;
- classloading side effects and transformer/plugin order are compatibility-sensitive;
- PR #19 already demonstrated that extra classloading concurrency can regress a four-thread pack.

Therefore the next action is **attribution**, not a production parallel stream.

## Source architecture: FML lifecycle placement around resource reload

NeoForge `CommonModLoader` has three groups:

### `begin(...)` — before resource reload

- gather/initialize mods;
- registry initialization;
- config loading.

These are before the initial resource-reload overlap window and therefore directly delay reaching it.

### `load(...)` — resource preparation

- Common setup;
- Sided/client setup;
- Registration events.

On client, `ClientModLoader` runs this work in the reload listener's preparation stage. Its wall can overlap resource preparation and is only TTMM-critical if it becomes the preparation gate or creates downstream work that later blocks the gate.

### `finish(...)` — after the preparation barrier/order wait

- Enqueue IMC;
- Process IMC;
- LoadComplete;
- Network registry lock.

Client scheduling executes `finish(...)` after the listener's preparation stage has crossed the reload barrier. This makes its wall qualitatively different from `load(...)`: it lives in the ordered post-barrier path toward title and should be treated as critical unless a later independent gate hides it.

Each `dispatchParallelEvent(...)` itself has two parts:

1. dependency-aware per-mod event dispatch on the parallel executor;
2. the event's `DeferredWorkQueue` run on the single `modloading-sync-worker`.

The deferred queue preserves its owning `ModContainer` and runs tasks sequentially. That gives a clean diagnostic attribution path by mod/owner, but not permission to parallelize it: `enqueueWork` is explicitly the main-thread bridge mods use for work that cannot safely run on parallel mod-loading workers.

## Diagnostic added on this branch

Property:

```text
-Dboot_optim.profileFmlLifecycle=true
```

No work is skipped, reordered, cached, or moved to a different executor.

The mixin observes the stock call boundaries for:

- `gather_and_initialize_mods`;
- `registry_initialization`;
- `config_loading`;
- `common_setup`;
- `sided_setup`;
- `registration_events`;
- `enqueue_imc`;
- `process_imc`;
- `load_complete`;
- `network_registry_lock`.

It is optional/fail-open:

- the mixin config is non-required;
- injections use `require = 0`;
- profiler bodies catch failures and never alter stock control flow;
- property-off path returns immediately.

Marker:

```text
BOOTOPTIM_FML_LIFECYCLE
```

Fields deliberately separate measurement domains:

- `wall_ms` — elapsed wall across the stock phase call;
- `caller_cpu_ms` — CPU consumed by the thread invoking the phase. For parallel dispatch this **under-counts** worker CPU by design;
- `process_cpu_ms` — process-wide CPU delta. It is an **inclusive upper bound**, not attributable CPU; during resource preparation it includes unrelated resource-loader/JIT/native work;
- `classes_loaded_delta` — JVM-wide total-loaded-class delta, useful for identifying classloading-heavy phases but not a savings estimate;
- `jit_compilation_ms` — JVM-wide compilation-time delta. This is aggregate JIT activity, not elapsed critical-path wall;
- `placement` / `criticality` — static scheduling classification (`pre_resource_reload`, `resource_preparation`, `ordered_post_barrier`).

Interpretation rule: never sum process CPU or inclusive wall across overlapping lanes and call the sum recoverable TTMM.

## Candidate ranking

### P0 — attribute serial FML container/entrypoint loading

**Cause:** required Java mod entrypoint classes are loaded serially while FML builds containers, before parallel mod construction begins.

**Data today:** source proof is strong; exact-pack class-specific wall is not yet measured. Laptop ClassLoad/ClassDefine JFR data is missing. `mod_entrypoint` as a whole is 25.754 s on the recent hosted smoke and 112.594 s on old laptop, but neither number is a ceiling for this subphase.

**Next diagnostic if `gather_and_initialize_mods` is material:** split `waitForScanToComplete`, serial `buildMods`/container loading, parallel construction, and construction deferred queue. Then attribute container build by mod ID/entrypoint. Prefer source-level/agent diagnostic rather than changing ordering.

**Production candidate only if concentrated:** if one or a few entrypoints trigger most of the serial wall, move the expensive non-semantic initialization behind that mod's real first consumer, guarded by exact mod/version and fail-open fallback. This is safer than globally parallelizing every language loader.

**Parallel-container experiment gate:** only if the wall is broad across many independent entrypoint loads, the dominant time is outside Mixin's synchronized section, and a bounded-concurrency experiment wins exact-pack and old-laptop A/B. Start with bounded concurrency, not `parallelStream()` and not FML maxThreads by default.

**Risks:** language-loader thread safety, Mixin serialization/contended locks, class-init side effects, dependency semantics, error attribution/order, extra heap/GC, and four-core contention.

### P0 — attribute FML `finish(...)` post-barrier lifecycle

**Cause:** Enqueue IMC, Process IMC, LoadComplete and network lock execute after the reload preparation barrier and are therefore much closer to direct TTMM wall than setup events that run during preparation.

**Data today:** placement is source-proven, but current exact-pack/laptop per-phase wall is absent. Old `modloading-sync-worker` allocation share (~20.38%) is only a clue, not wall evidence.

**Candidate after measurement:** instrument per-mod event body and each `DeferredWorkQueue` task/owner for the dominant finish phase. If a specific mod performs title-irrelevant work in LoadComplete/IMC, defer that mod-specific work to its first consumer while preserving FML event completion semantics externally where possible.

**Do not:** globally parallelize `DeferredWorkQueue`, reorder IMC vs LoadComplete, or bypass `NetworkRegistry.setup()`.

**Risks:** event ordering is public mod lifecycle semantics; network registration has correctness constraints; many mods rely on enqueueWork specifically because work must execute on the sync thread. Any optimization should be narrow, version-gated, and fall back to stock.

### P1 — residual discovery outside production `compileContent()` cache

**Cause:** production caching covers annotation/class metadata scan, but discovery also includes locators, dependency/nested-jar resolution, validation, language-provider loading, module/layer/resource handling, Connector interactions and other metadata work.

**Data today:** recent hosted cold fixture root discovery ~0.477 s, dependency discovery ~6.446 s; cold `compileContent()` window ~3.592 s, with 240 misses and 0 hits. This cannot quantify the warm residual.

**Next diagnostic:** create a two-launch exact-pack diagnostic mode or equivalent controlled run that preserves only `.bootoptim` between launch A and B, then report root/dependency discovery plus cache hit/miss counts. Separately bracket FML locator/dependency resolution versus background `compileContent()`.

**Candidate:** only if warm residual remains material, cache a narrowly defined deterministic metadata product (for example, expensive nested dependency/JarJar resolution) with strong source + nested-artifact fingerprints and fail-open stock fallback.

**Risks:** stale dependency graphs, dynamic Connector/locator behavior, security/status metadata, changed config/environment inputs, and invalidation complexity. A cold-CI win is insufficient.

### P1 — Mixin lazy prepare scheduling, currently **not promoted**

**Cause:** transformer creation is lazy and historical `prepareConfigs` wall is several seconds on the fast reference.

**Why not promote now:** the background mod scanner already runs concurrently with this interval and is deliberately sized to leave one bootstrap thread. On four processors there may be no free CPU to exploit; PR #19 provides a concrete contention warning.

**Reopening criterion:** lifecycle telemetry or a warm-cache trace shows Mixin prepare consuming critical wall while process CPU indicates spare capacity / background scan has already ended. A safe experiment would need an official or version-stable point after all Mixin resources/configs are registered; do not initialize the transformer before config discovery is complete.

**Risks:** incomplete config set, lifecycle ordering, Mixin plugin semantics, transformed side loads, and contention.

### P1/P2 — persistent post-Mixin transformed-class cache remains blocked on API

Issue #67 has the correct architectural premise: avoid repeated deterministic Mixin apply work across warm launches, not another in-process side-load cache. The ceiling (~6.36 s apply on the old fast-machine profile, likely larger on slow CPU) is material.

ModLauncher source confirms the desired interception is launch-plugin processing inside `ClassTransformer`, but the current public API exposes lookup, not replacement/decoration of the Mixin plugin. Old PR #22 replaces the GAME classloader factory with a custom loader and is too invasive to treat as production evidence.

**Candidate:** upstream a supported post-plugin-transform cache/decorator hook in ModLauncher or Mixin, with exact pre-Mixin node + complete dynamic fingerprint and exclusion of unsafe plugin-backed targets. BootOptim uses it only when available; otherwise stock.

**Risk:** `IMixinConfigPlugin` and arbitrary runtime selection inputs make cache invalidation the primary correctness problem. No deep reflective plugin-map mutation in production.

## What is explicitly not a candidate from this pass

- another generic Mixin transformer-loader side-load memo;
- a `ClassInfo` negative cache as startup optimization;
- optimizing only ModLauncher's final ASM writer;
- arbitrary class prewarming / “load more classes in parallel”;
- globally making FML deferred work queues parallel;
- treating setup-listener inclusive time during resource preparation as additive TTMM;
- any additional ModelManager cache or bake optimization already covered by the model-pipeline ledger;
- JVM/system-level tuning that changes the user's installation or launcher settings.

## Exact-pack gate for this branch

Run hosted exact-pack smoke with:

```text
-Dboot_optim.profileFmlLifecycle=true
```

Required:

1. normal build/package/Startup succeeds with property off;
2. smoke reaches title with no BootOptim Mixin failure;
3. lifecycle markers appear for the applicable begin/load/finish phases;
4. record wall + caller CPU + process CPU + class/JIT deltas without interpreting process CPU as exclusive attribution;
5. use the results to choose the next narrow diagnostic, not to ship a scheduling change directly.

If `gather_and_initialize_mods` dominates, split wait/container-build/construction next. If a `finish` phase dominates, split by mod event/deferred-work owner next. If both are small, close this lane rather than manufacturing a micro-optimization.
