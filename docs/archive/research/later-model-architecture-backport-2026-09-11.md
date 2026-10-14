# Later Minecraft model architecture vs NeoForge 1.21.1

**Date:** 2026-09-11  
**Agent:** 129  
**Authority:** `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`  
**Target runtime:** Minecraft 1.21.1 / NeoForge 21.1.248  
**Status:** **ARCHITECTURE REVIEW COMPLETE — NO-GO for a direct/general runtime backport; one constrained two-phase design is worth a diagnostic safe-domain census before any implementation**

## Question

`ModelManager` / `ModelBakery` remains a physically large startup gate, but BootOptim has already rejected the shallow forms of this idea:

- PR #14: eager top-level parallel `forEach` improved the isolated bake but regressed main-menu time;
- PR #36: exact top-level identity reuse removed many calls but only a small amount of bake wall and no end-to-end win;
- PR #47/#214: ModelManager is a real preparation gate and the current work is a causal serial chain, not merely a large inclusive listener;
- PR #217: most current bake cost is real cache-miss work, not wrapper repetition;
- PR #222: a generic reflective prepare/commit split can preserve representation and still regress because it duplicates work and runs on the same worker;
- PR #250: the exact pack does contain an earlier scheduling window for immutable Decocraft input, but that only establishes eligibility for a narrow direct-mod experiment, not a generic scheduler.

This review asks a different question: did later Minecraft/NeoForge versions change the **model architecture itself** in a way that can be backported to 1.21.1 without weakening custom-loader/event/atlas/failure semantics?

## Sources reviewed

Project evidence:

- [`AGENTS.md`](../../AGENTS.md)
- [`model-pipeline.md`](model-pipeline.md)
- PRs [#14](https://github.com/wachipayox/BootOptim/pull/14), [#36](https://github.com/wachipayox/BootOptim/pull/36), [#47](https://github.com/wachipayox/BootOptim/pull/47), [#214](https://github.com/wachipayox/BootOptim/pull/214), [#217](https://github.com/wachipayox/BootOptim/pull/217), [#222](https://github.com/wachipayox/BootOptim/pull/222), and [#250](https://github.com/wachipayox/BootOptim/pull/250)

Upstream / NeoForge sources:

- NeoForged migration primer [1.21.1 -> 1.21.2](https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md)
- NeoForged migration primer [1.21.3 -> 1.21.4](https://github.com/neoforged/.github/blob/main/primers/1.21.4/index.md)
- NeoForged migration primer [1.21.4 -> 1.21.5](https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md)
- NeoForge 1.21.1 [`ModelEvent`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/ModelEvent.java)
- NeoForge 1.21.1 [`ModelManager` patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/client/resources/model/ModelManager.java.patch)
- NeoForge 1.21.1 [`IUnbakedGeometry`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/model/geometry/IUnbakedGeometry.java)
- NeoForge 1.21.5 [`ModelEvent`](https://github.com/neoforged/NeoForge/blob/1.21.5/src/client/java/net/neoforged/neoforge/client/event/ModelEvent.java)
- NeoForge 1.21.5 [`ModelManager` patch](https://github.com/neoforged/NeoForge/blob/1.21.5/patches/net/minecraft/client/resources/model/ModelManager.java.patch)
- NeoForge 1.21.5 [`ModelBakery` patch](https://github.com/neoforged/NeoForge/blob/1.21.5/patches/net/minecraft/client/resources/model/ModelBakery.java.patch)
- NeoForge 1.21.5 [`ExtendedUnbakedGeometry`](https://github.com/neoforged/NeoForge/blob/1.21.5/src/client/java/net/neoforged/neoforge/client/model/ExtendedUnbakedGeometry.java)
- NeoForge 1.21.5 docs: [custom model loaders](https://docs.neoforged.net/docs/1.21.5/resources/client/models/modelloaders/) and [model system](https://docs.neoforged.net/docs/1.21.5/resources/client/models/modelsystem/)

Supplemental signature cross-checks use [mappings.dev](https://mappings.dev/) with Mojang names. It is not the semantic authority; NeoForge source/primers above are.

## What actually changed upstream

### 1.21.1 baseline: eager bake over a mutable model registry

The 1.21.1 shape is the one BootOptim has already profiled:

- `ModelBakery` owns `unbakedCache`, `topLevelModels`, `bakedCache` and `bakedTopLevelModels`.
- `ModelBaker` exposes `getModel(id)` and `bake(id, ModelState)`.
- `ModelBakery.bakeModels(TextureGetter)` is synchronous and returns `void`.
- NeoForge custom geometry implements `IUnbakedGeometry#bake(...) -> BakedModel` and may run arbitrary mod code during that call.
- NeoForge `ModelEvent.ModifyBakingResult` receives the live modifiable baked-model map after it is complete, on a worker thread, before `BlockModelShaper` caching.
- `ModelEvent.BakingCompleted` runs after the manager has installed the new registry.

There is no API-level declaration that a 1.21.1 custom geometry bake is pure, re-entrant, thread-safe, or independent of call order. That absence is the central compatibility problem for generic parallelism.

### 1.21.2: dependency discovery becomes an explicit subsystem

The 1.21.2 primer introduces the first important structural step:

- `ModelDiscovery` becomes the component responsible for model/dependency loading.
- `UnbakedModel#getDependencies` / parent-resolution style behavior becomes `resolveDependencies` through a resolver.
- `ModelBaker#getModel` disappears from the public baker contract and model lookup is moved behind the discovery/resolution architecture.
- `ModelBakery` is reshaped around top models, all unbaked models, and the missing model rather than being the only object that discovers and bakes everything.

This is not yet the useful concurrency change. It is the enabling graph separation: dependency discovery becomes data that can be completed before the bake phase.

### 1.21.4: model domains and render data are split more aggressively

The 1.21.4 primer adds the second enabling layer:

- `ResolvableModel` becomes the common dependency-resolution contract.
- block-state models are separated from ordinary unbaked models rather than sharing one `BakedModel`-centric path;
- item rendering gets a separate client-item model description;
- `ModelBakery.bakeModels` returns a `BakingResult` instead of exposing only a mutable top-level field;
- `ModelDebugName`, `SpriteGetter`, `TextureSlots`, and the revised texture binding/reporting APIs separate model identity/diagnostics from raw top-level `ModelResourceLocation` publication;
- parent/render properties and geometry are more explicitly separated.

NeoForge keeps its custom-loader lane across this rewrite, but the custom-loader API itself changes with the upstream model representation. This matters: later scheduling is built on a different compatibility contract, not dropped into the 1.21.1 interface unchanged.

### 1.21.5: the meaningful scheduling rewrite

The 1.21.5 primer is the first version reviewed that contains the architectural change BootOptim was looking for.

It describes the model system as three explicit stages inside `ModelManager#reload`:

1. load JSON/model descriptions;
2. resolve model dependencies into `ResolvedModel` wrappers;
3. bake block-state and item models from those resolved inputs.

The same primer records two critical API changes:

- `ModelBaker#compute(SharedOperationKey<T>)` introduces a named shared-operation abstraction instead of relying only on ad-hoc recursive baked-model map lookups;
- `ModelBakery.bakeModels(SpriteGetter, Executor)` now returns `CompletableFuture<ModelBakery.BakingResult>` and performs loading/baking asynchronously.

NeoForge's 1.21.5 `ModelBakery` patch confirms that this is a real future-based architecture, not merely a renamed synchronous loop. NeoForge adds standalone-model baking as another future on the same executor and combines it into the final `BakingResult`.

NeoForge's 1.21.5 `ModelManager` patch also confirms the lifecycle around that future:

- block models, block states, client item info, and standalone models are prepared as futures;
- dependency discovery is run after those source futures complete;
- the `ModelBakery` is created from resolved models;
- `loadModels(..., executor)` returns the future-backed reload state;
- `ModelEvent.ModifyBakingResult` is fired only after the `BakingResult` exists;
- application into `ModelManager`, `BlockModelShaper` cache replacement, and `ModelEvent.BakingCompleted` remain later lifecycle steps.

The 1.21.5 `ModelEvent` javadoc preserves an important NeoForge contract: `ModifyBakingResult` still runs from a worker thread, sees the model registry/baking result, and runs before the `ModelManager` has installed the latest data. `BakingCompleted` still represents the post-install state. In other words, upstream parallelism did **not** require lazy/partial registry publication.

### Later 1.21.x

The reviewed 1.21.6 and 1.21.8 migration material does not introduce another model-loading rewrite comparable to 1.21.5. For this investigation, 1.21.5 is the relevant architectural reference point; later releases can refine details but do not remove the compatibility gap with 1.21.1.

## API / lifecycle map

| Concern | 1.21.1 | 1.21.5 reference | Backport implication |
| --- | --- | --- | --- |
| dependency graph | implicit through `UnbakedModel` dependencies / parent resolution inside `ModelBakery` | explicit `ResolvableModel` -> `ModelDiscovery` -> `ResolvedModel` | a safe scheduler needs an explicit pre-bake dependency representation, not just top-level roots |
| baker API | `getModel` + recursive `bake(id,state)` | `getModel` returns `ResolvedModel`; `compute(SharedOperationKey)` represents shared bake operations | concurrency should be keyed by semantic operations/dependencies, not model object identity |
| output | mutable `bakedTopLevelModels` | future `BakingResult` | compute can be detached from publication while callbacks still receive a complete result |
| top-level lifecycle | synchronous `bakeModels(TextureGetter)` | `bakeModels(SpriteGetter, Executor) -> CompletableFuture<BakingResult>` | later architecture supports worker baking, but only after its model contracts changed |
| model domains | unified `BakedModel` path | block-state models, item models, geometry/quad collections separated | a textual port would require recreating a large data-model rewrite, not one method |
| custom loaders | `IGeometryLoader` / `IUnbakedGeometry#bake -> BakedModel` | `UnbakedModelLoader` + `UnbakedGeometry#bake -> QuadCollection` | 1.21.1 custom bakes cannot be assumed thread-safe because 1.21.5 changed the API boundary under them |
| diagnostics | top-level `TextureGetter` binding; missing-reference behavior coupled to bake | `ModelDebugName` / `SpriteGetter` / later texture-reporting abstractions | parallel compute must not reorder or duplicate user-visible missing-texture diagnostics |
| NeoForge pre-cache callback | mutable complete map, worker thread | mutable complete `BakingResult`, worker thread | full-map callback semantics can be preserved with deterministic commit before event |
| NeoForge post-cache callback | manager installed, then `BakingCompleted` | same conceptual boundary | no reason to move this event |
| atlas / GL | atlas preparation is already asynchronous; model bake consumes prepared sprites; apply owns later installation/upload lifecycle | asynchronous model bake consumes prepared sprite access and finishes before apply | no GL/render-thread work needs to move; any backport must wait for the same atlas readiness edge |

## Why a direct 1.21.5 backport is not equivalence-safe

The useful 1.21.5 scheduler is inseparable from three contract changes.

### 1. An explicit resolved graph exists before bake

In 1.21.5, model descriptions are resolved into `ResolvedModel` objects before the asynchronous bake stage. The scheduler therefore operates over inputs whose dependency discovery has already completed.

In 1.21.1, arbitrary custom `IUnbakedGeometry#bake` code can still request nested models or perform mod-specific work during baking. `UnbakedModel#getDependencies` is useful but is not a proof that every runtime bake edge or side effect has been declared. A scheduler built only from the visible top-level map therefore cannot prove a closed DAG for unknown loaders.

### 2. The custom-loader ecosystem has a different bake contract

1.21.5 NeoForge custom loaders are written against the post-rework `UnbakedModel` / `UnbakedGeometry` / `QuadCollection` APIs while vanilla itself invokes `bakeModels(..., Executor)`.

That makes concurrent execution part of the version's environment in a way it is not for 1.21.1. Backporting the executor while keeping the old custom-loader API would expose 1.21.1 mods to a concurrency contract they were never required to satisfy.

This is the exact hole in a generic retry of PR #14: making maps thread-safe is not sufficient when the work inside a custom bake may not be thread-safe.

### 3. Later versions isolate result/diagnostic context better

1.21.4/1.21.5 introduce `BakingResult`, `ModelDebugName`, `SpriteGetter`, texture-slot resolution, and explicit resolved-model wrappers. These are not cosmetic renames. They make it possible to perform work away from final registry publication while retaining model-specific context and deferring the complete result to a later boundary.

A 1.21.1 backport that only runs the old bake callback on workers would still change warning order, missing-texture reporting order, exception timing, and potentially custom callback/side-effect order.

## The only generic architecture worth a diagnostic

A full 1.21.5 port is too invasive and a generic parallel loop is already rejected. The smallest architecture that actually imports the **idea** rather than the text is a **version-pinned two-phase deterministic bake plan with an opaque fallback lane**.

This is **not authorized as runtime code yet**. It first needs a safe-domain census proving that enough current exact-pack work is eligible to justify the complexity.

### Phase A — stock discovery and atlas preparation remain authoritative

Keep 1.21.1's existing resource/model parsing, NeoForge geometry-loader invocation during parse, blockstate registration, parent resolution, atlas generation, and all existing futures unchanged.

Do not schedule any bake work until the stock prerequisites for `loadModels` are satisfied and the current-generation sprite lookup is available. This preserves resource-pack order, atlas generation, and GL ownership.

### Phase B — build a `BakePlan` without invoking bake code

The proposed diagnostic adapter would inspect the already-resolved 1.21.1 model graph and classify each top-level root into one of two lanes:

1. **planned-safe** — the entire transitive bake closure can be enumerated from exact version-pinned vanilla/NeoForge structures and contains only audited side-effect-free operations;
2. **opaque** — any custom loader/geometry, unknown runtime subclass, dynamic nested lookup, unsupported NeoForge model data, or unenumerable edge.

Unknown means opaque. There is no speculative worker execution followed by a compatibility catch.

The classifier must be stricter than `model.getClass() == BlockModel.class`: a vanilla `BlockModel` shell can carry NeoForge custom geometry/model data. The plan must inspect the actual NeoForge 21.1.248 extension state and reject any model whose bake can enter `IUnbakedGeometry` or another mod callback.

The plan also needs to expand semantic 1.21.1 bake keys (`model id + ModelState transformation + uv-lock`) rather than model object identity. That follows the later `SharedOperationKey` idea and avoids reopening #36.

### Phase C — detached compute only for planned-safe operations

For the safe closure only, use the existing resource-reload executor and compute into private future/cell objects keyed by the semantic bake key.

Properties required:

- no new executor or generic pool;
- no write to `bakedTopLevelModels` from workers;
- no `ModelManager` publication from workers;
- no custom geometry/loader/event code on workers;
- no GL/render-thread work;
- recursive shared operations resolve through a future cell rather than duplicate computation;
- a DAG cycle or unsupported dynamic lookup invalidates that root before execution and sends it to the opaque lane.

This is structurally different from PR #14. PR #14 parallelized the existing top-level eager actions. The proposed design computes only a closed, audited dependency plan and leaves publication/order-sensitive work serialized.

### Phase D — deterministic stock-order commit

The owner `loadModels` task still walks the original 1.21.1 top-level iteration order.

- planned-safe root: join its private result and publish it at that exact position;
- opaque root: execute the original stock bake action synchronously at that exact position;
- custom roots therefore retain their relative order and see the same previously published top-level registry prefix as stock;
- `ModelEvent.ModifyBakingResult` fires only after this ordered commit has completed and therefore still receives a complete registry;
- `ModelEvent.BakingCompleted` and `BlockModelShaper` replacement stay untouched.

No lazy model map is exposed and no callback observes a partial registry.

### Phase E — ordered diagnostics, not worker logging

This is a necessary piece that the simple parallel experiment did not provide.

If an audited vanilla bake can report missing textures or other model diagnostics, worker compute must write a root-local diagnostic record rather than emit user-visible output immediately. Commit then replays those records in original root order using the same model/debug identity.

If a diagnostic cannot be captured/replayed without changing its exception or logging semantics, that operation is not in the safe domain.

This is the 1.21.1 analogue of the later separation between model debug identity, sprite access and final baking result.

## What cannot be claimed safe yet

The architecture above still has two unproven requirements, so production runtime work is not justified from source review alone.

### Safe-domain closure coverage

We do not yet know how much of the current 411k cache-miss lane is both expensive and fully classifiable without entering custom code.

#217 shows why this matters:

- Decocraft first/base geometry is the largest single family but is custom and must remain opaque under a generic BootOptim scheduler;
- ordinary `BlockModel` work is material, but individual models may carry NeoForge custom geometry/data and need graph-level classification;
- multipart/multivariant cost is meaningful, but much of it delegates to children, so the transitive child closure — not the wrapper class — determines safety;
- generated-item behavior has existing special/cancellable paths and must be audited rather than treated as generic vanilla work.

A count of roots or cache keys is not sufficient. The diagnostic must attribute **exclusive time** to safe vs opaque closures.

### Failure/diagnostic equivalence

A future-based result can preserve successful model output while still changing which warning/error appears first or where an exception is observed. Later Minecraft changed the surrounding diagnostic APIs at the same time it added asynchronous baking.

Before runtime authorization, the design needs a concrete 21.1.248 mapping for:

- missing-texture reporting;
- exception capture/rethrow boundaries;
- generated-item special cases;
- every path from audited vanilla roots into NeoForge extensions.

If those cannot be isolated into side-effect-free compute + ordered commit, the generic backport closes here.

## Diagnostic proposal — no runtime optimization

If this direction is continued, the next PR should be **diagnostic only** and should not schedule work.

It should build the proposed `BakePlan` beside the stock bake and report:

- top-level roots and semantic bake keys classified safe vs opaque;
- transitive dependencies and reason for every opaque rejection;
- exclusive stock `bakeUncached` time attributed to safe vs opaque closures using the existing #217 accounting style;
- how much of ordinary `BlockModel`, multipart, multivariant and generated-item work is actually safe after NeoForge custom-data checks;
- number of dynamic bake edges observed at runtime that were absent from the static plan;
- number/type/order of model diagnostics emitted by safe candidates;
- zero changes to models, executors, futures, atlas access, callbacks or result maps.

A runtime prototype is allowed only if all of the following are true:

1. the safe domain owns material exclusive work on the **current exact pack**, not merely many roots;
2. no safe candidate crosses into a custom loader/geometry or opaque runtime edge;
3. diagnostic/error effects can be replayed in stock order;
4. the plan is reload-generation-local and discarded completely on reload;
5. unsupported NeoForge/Minecraft versions and any structural mismatch take the stock path before worker execution.

## Runtime gates if the diagnostic passes

A future experiment would need stronger gates than PR #14 because it changes scheduling.

### Structural / semantic gates

- exact Minecraft `1.21.1` and NeoForge `21.1.248` guard;
- exact expected method/field/mixin targets; failure to bind disables the feature;
- current reload generation only; no persistent `BakedModel`, sprite, `ModelState`, or model-object cache;
- custom loader/geometry closure count on worker: **0**;
- NeoForge `ModifyBakingResult` sees the same complete key set and deterministic root order as control;
- `BakingCompleted` remains after manager/cache installation;
- resource selection 14/14 in exact order, one reload, same atlas dimensions/count;
- zero new Mixin errors and no added normalized ERROR set;
- missing-texture/model diagnostic multiset and replay order equal to control for the audited safe lane;
- output equivalence for representative vanilla and modded models, with custom/opaque models necessarily following stock.

### Scheduling / ownership gates

- only existing resource-reload executor;
- no OpenGL/render-thread calls from worker compute;
- bake worker tasks cannot start before the stock atlas/sprite readiness edge;
- top-level map writes occur only on the owner commit thread;
- no partial/lazy registry becomes visible to any event or consumer;
- opaque roots execute stock code once, in stock order;
- worker failures do not trigger duplicate custom callbacks or duplicate diagnostics.

### Performance gate

Only after semantic gates pass:

- hosted exact-pack A/B with the existing causal trace;
- report `model_bakery_bake`, `model_manager_load_models`, dependency-union and main-menu separately;
- do not sum overlapping worker durations;
- do not claim savings from safe-domain CPU reduction alone;
- laptop gate only if hosted evidence shows a coherent main-menu/critical-union change large enough to justify hardware validation.

## Decision

### Direct backport of 1.21.5 model loading: **NO-GO**

The later implementation is not a drop-in scheduler. Minecraft reached asynchronous baking only after several releases that introduced explicit dependency resolution, split model domains, a future `BakingResult`, a shared-operation abstraction, and new geometry/diagnostic APIs. NeoForge simultaneously moved custom loaders onto that new model contract.

Copying `bakeModels(..., Executor)` behavior into 1.21.1 while preserving the old `IUnbakedGeometry` API would impose a concurrency contract on mods that 1.21.1 never required. That is not a demonstrable-equivalence backport.

### Textual backport of `ModelDiscovery` / `ResolvedModel`: **NO-GO**

Recreating the 1.21.5 type system in BootOptim would amount to maintaining a parallel model implementation across vanilla and NeoForge extension points. It would be more invasive than the target optimization and would still need adapters for every 1.21.1 custom loader.

### Minimal structural innovation: **CONDITIONALLY VIABLE, diagnostic first**

The only architecture worth carrying forward is the two-phase **planned-safe compute / stock-order commit** design above:

- import the later version's explicit dependency/shared-operation idea;
- retain 1.21.1's public/custom-loader semantics;
- parallelize only a statically closed, version-pinned, side-effect-free subset;
- leave every unknown/custom case fully stock;
- keep diagnostics and publication ordered and callbacks complete.

This is a materially different premise from PR #14, but it is **not yet runtime-ready** because safe-domain coverage and diagnostic/failure isolation are unproven.

The next decision point is therefore a no-scheduling `BakePlan` census. If that census shows that the expensive residual is mostly opaque/custom, or that diagnostics/runtime edges prevent a closed pure domain, the generic later-version backport should be closed permanently and effort should remain on direct owner-specific work such as the narrowly eligible Decocraft path from #250.

No startup saving is claimed by this research.
