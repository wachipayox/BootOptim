# Pre-title resource demand architecture — 2026-09-01

Status: **ARCHITECTURE / CEILING PLAN — NO PRODUCTION LAZY IMPLEMENTATION YET**

Base: `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`.

This document answers the architectural question raised by the slow-laptop campaign and PR #69:

> How many of the ~44k block-model resources, 11.4k blockstate stacks and ~20k block-atlas sprites actually have to exist before the first title screen, and how many are loaded only because the 1.21.1 resource/model architecture enumerates and materializes the full universe eagerly?

The current evidence does **not** yet support a non-zero count of resources that is semantically proven safe to omit. It does prove that the stock/NeoForge architecture materializes essentially the entire measured input universe before the title screen, and that the dominant laptop cost is upstream resource access/decode rather than JSON parsing or geometric atlas packing.

Accordingly, the next action is demand/ceiling instrumentation, not a generic lazy `Map` implementation.

## Executive decision

- **GO:** build a diagnostic-only pre-title demand graph and a counterfactual critical-wall ceiling from the existing #69 attribution data/hook points.
- **GO, conditional after the trace:** consider a small hybrid that preserves the eager logical universe but defers resource payload work where exact semantics can be verified.
- **NO-GO now:** generic lazy `ModelBakery.topLevelModels`, an emulated baked registry, or wholesale ModernFix-style dynamic resources.
- **NO-GO now:** lazy sprite admission/repacking. Atlas coordinates/UVs make sprite deferral a separate, harder architecture.
- **NO exact-pack candidate request yet:** first produce the demand counts, current-schedule wall ceiling, and semantic verifier described below.

## Evidence baseline

### Current integration and history

The integration tree already contains the production direct generated-item baker from PR #64, the indexed blockstate matcher, hardened Decocraft quarter-turn quad reuse, and the existing FancyMenu overlap work. This research must preserve them.

Historical mechanisms that are not reopened by this work:

- PR #14: eager top-level bake parallelism improved the bake but regressed main-menu time.
- PR #36: 64.57% exact-identity reuse removed many top-level calls but improved the bake by only ~413 ms and did not improve end-to-end startup.
- PR #57: material caches reached very high hit rates, including 95.98% in one design, without a consistent direct or end-to-end win.
- PR #61: generated-item span indexing is superseded by the production direct baker.
- PR #62/#64: the direct generated-item path is validated/production and must remain intact.
- PR #66: even an intentionally optimistic flattened `ElementsModel` traversal lost to stock (`102.394 ms` candidate vs `91.267 ms` stock).

This architecture therefore targets **work creation and resource materialization**, not another bake micro-optimization.

### Slow-laptop resource evidence

PR #69 exact-pack laptop attribution:

| Family | Count | Measured work / wall | Important split |
| --- | ---: | ---: | --- |
| block-model resources | `44,103` | `63,243.150 ms` task-sum | `BlockModel.fromStream`: `5,896.289 ms` = only ~9.3% |
| blockstate stacks | `11,435` | `8,649.468 ms` task-sum | `openAsReader`: `7,961.276 ms` = ~92.0%; Gson parse `549.682 ms` |
| delegated sprite loads | `20,054` | `65,326.153 ms` task-sum | Decocraft `40,553.154 ms` = ~62.1% |
| blocks-atlas regions | `19,732` | `55,377.455 ms` `loadAndStitch` wall scope | final `stitch(...)`: only `875.884 ms` = ~1.58% |

For block models, roughly `57,346.861 ms` of task-sum is outside `BlockModel.fromStream`. PR #69 did not decompose that residual further, so it must not be mislabeled as one operation; it is an envelope containing resource open/read/decompression/materialization and surrounding task work.

The earlier same-build laptop scaling campaign showed the resource-facing phases scaling far worse than the actual bake:

- block states: ~20.42x laptop/fast;
- atlas branch: ~15.65x;
- block models: ~9.36x;
- `bakeModels`: ~3.40x.

`ModelManager` remained the preparation gate. This is the central reason to investigate demand rather than continuing inside `FaceBakery`.

## What is known about “required before title” today

The only defensible count table today is:

| Resource family | Eagerly materialized by current architecture | Semantically proven required pre-title | Semantically proven safe to defer |
| --- | ---: | ---: | ---: |
| block-model resources | `44,103 / 44,103` observed tasks | **unknown** | **0 proven** |
| blockstate stacks | `11,435 / 11,435` observed tasks | **unknown** | **0 proven** |
| blocks-atlas sprites | `19,732 / 19,732` stitched regions | **unknown** | **0 proven** |

“0 proven” is not a claim that none are deferrable. It means the existing profilers measure eager execution cost, not semantic demand. Any larger number would currently be guesswork.

The next profiler must produce these exact quantities:

```text
potentially_deferrable_models = enumerated_model_resources
                              - pretitle_transitive_model_resources
                              - compatibility_forced_eager_model_resources

potentially_deferrable_blockstates = enumerated_blockstate_stacks
                                   - pretitle_transitive_blockstate_stacks
                                   - compatibility_forced_eager_blockstate_stacks

potentially_deferrable_sprites = enumerated_block_atlas_sprites
                               - pretitle_material_sprite_dependencies
                               - compatibility/layout_forced_sprites
```

Only after semantic verification may “potentially deferrable” be relabeled “safe to defer”.

## Exact 1.21.1 / NeoForge lifecycle that matters

### ModelManager eager resource stage

PR #69 already targets the exact Mojmap synthetic methods in Minecraft 1.21.1:

- block model enumeration: `ModelManager.lambda$loadBlockModels$7` -> `MODEL_LISTER.listMatchingResources`;
- one block-model payload task: `lambda$loadBlockModels$8`;
- blockstate enumeration: `lambda$loadBlockStates$11` -> `BLOCKSTATE_LISTER.listMatchingResourceStacks`;
- one blockstate-stack payload task: `lambda$loadBlockStates$12`.

This is the first architectural distinction: **knowing an ID exists is cheaper and semantically different from opening/parsing its payload**.

### ModelBakery roots and dependency closure

NeoForge 1.21.1 patches `ModelBakery` so that `ModelEvent.RegisterAdditional` is fired during construction. Each registered standalone model is loaded and its dependencies are registered. After this, the constructor executes:

```text
topLevelModels.values().forEach(model -> model.resolveParents(this::getModel))
```

Thus current construction deliberately resolves the entire top-level set before baking. The resulting full-map behavior is an architectural choice, not evidence that every model is needed to draw the title screen.

### Model events are compatibility barriers

NeoForge 1.21.1 exposes:

- `ModelEvent.RegisterAdditional`: adds standalone model roots before global parent resolution;
- `ModelEvent.ModifyBakingResult`: receives a **modifiable** baked-model map while `ModelManager` is reloading;
- `ModelEvent.BakingCompleted`: receives the post-bake model map and `ModelBakery` after the manager adopts the result.

`ModifyBakingResult` is inserted before `ModelManager` builds its final dispatch/cache. A lazy design cannot assume these events are passive. A mod may perform `get`, `containsKey`, `keySet`, `entrySet`, `values`, iteration, `replaceAll`, `put`, or cross-namespace lookup.

Any event handler that relies on complete iteration can turn the event into a full-materialization barrier unless the registry is emulated with demonstrated equivalence.

### Custom geometries/loaders

Before model loading, NeoForge initializes `GeometryLoaderManager` and fires `RegisterGeometryLoaders`.

`BlockModel.fromStream` is replaced by `ExtendedBlockModelDeserializer`. If a JSON model contains a `loader`, the registered `IGeometryLoader.read(...)` executes during deserialization. `BlockModel.resolveParents` also delegates to custom geometry parent resolution.

Consequences:

1. Deferring a raw model parse can also defer arbitrary custom-loader code, not only Gson allocation.
2. “This model was never queried through our lookup API” does **not** prove that changing the timing of its custom loader is semantically invisible.
3. A production payload-lazy path needs either a conservative compatibility policy or a verifier that proves the exact-pack behavior; it cannot treat all 44,103 model files as inert JSON.

### Missing-model semantics and cross-namespace lookup

The candidate must distinguish at least:

- absent resource;
- resource present but failed to parse;
- `ModelBakery` missing-model substitution;
- absent top-level variant;
- re-entrant/in-flight lookup;
- arbitrary cross-namespace `get` by a mod.

Returning the missing model where stock returns `null`, or vice versa, is a compatibility bug. ModernFix contains explicit special cases for this reason.

## Demand graph to measure

The profiler should label every node and edge with an origin, timestamp and first-demand phase. The graph is a **semantic graph**, not merely a resource-call graph.

```text
                         +------------------------+
                         | first title marker     |
                         +-----------^------------+
                                     |
              observed pre-title API/render/menu demand
                                     |
+----------------------+      +------+------------------+
| NeoForge event roots |----->| top-level model demand |
| RegisterAdditional   |      | block / item / special |
| ModifyBakingResult   |      +------+------------------+
| BakingCompleted      |             |
+----------------------+             | getModel / parent / overrides
                                     v
                              +------+------------------+
                              | unbaked model resources |
                              +------+------------------+
                                     |
                          dependency / custom geometry
                                     v
                         +-----------+------------+
                         | materials / sprite IDs |
                         +-----------+------------+
                                     |
                         atlas source/layout/decode
                                     v
                              +------+-------+
                              | SpriteContents|
                              +--------------+

BlockState root -> specific blockstate stack -> top-level ModelResourceLocations
```

### Required node classes

For every model/blockstate/sprite ID, record these flags independently:

- `ENUMERATED`: architecture discovered the ID.
- `EAGER_PAYLOAD`: stock opened/parsed/decoded it.
- `API_GET`: a logical consumer requested that ID.
- `TRANSITIVE`: requested because another demanded object referenced it.
- `EVENT_ROOT`: requested or enumerated by a NeoForge model event.
- `ITERATION_FORCED`: became required because a consumer requested complete map/set iteration.
- `CUSTOM_LOADER`: deserialization/resolution ran a custom geometry loader.
- `PRE_TITLE`: first demand occurred before the main-menu marker.
- `POST_TITLE`: first demand occurred only after the marker.
- `UNOBSERVED`: no logical demand was observed in the measured window.

`UNOBSERVED` must never be logged as `SAFE_TO_SKIP`.

## Instrumentation design

The first demand profiler should extend the existing #69 diagnostic lineage rather than duplicate its resource timers.

### 1. Keep existing enumeration/payload timing

Reuse #69’s exact hooks and resource/pack attribution for:

- 44k block-model resource tasks;
- 11.4k blockstate stack tasks;
- sprite supplier/load/stitch timing.

Add stable per-ID task start/end timestamps, not only aggregate totals, so a counterfactual completion time can be reconstructed.

### 2. Trace logical model demand

Instrument exact logical boundaries, not generic `Map` globally:

- `ModelBakery#getModel(ResourceLocation)`;
- `registerModelAndLoadDependencies`;
- item/special-item load entry points;
- `ModelBakerImpl` top-level lookup/bake entry points exposed by NeoForge;
- parent/override/custom-geometry dependency resolution;
- `ModelManager#getModel(ModelResourceLocation)` after apply, through the first-title marker.

Every request gets an origin enum: stock constructor, blockstate loader, item registry, RegisterAdditional, ModifyBakingResult, BakingCompleted, model dependency, custom geometry, post-apply consumer, or unknown stack.

The critical metric is not raw `getModel` count; it is the cardinality of unique resource IDs in the transitive closure before title.

### 3. Separate stock-induced roots from external demand

A stock loop that visits every block/item is not proof of semantic demand. Record it as `STOCK_EAGER_ROOT`, not `PRETITLE_EXTERNAL_ROOT`.

The profiler must be able to answer both:

- “What does the current algorithm visit?”
- “What did a consumer outside the eager expansion actually request?”

Without this distinction the trace would simply rediscover the current eager universe.

### 4. Blockstate demand

Record the block owning every loaded blockstate stack and the top-level MRLs produced from it. Distinguish:

- stock `loadAllBlockStates` traversal;
- a specific block/model lookup;
- event-driven access;
- post-apply renderer/model-shaper access.

The key count is unique stack IDs in the pre-title transitive closure after excluding traversal whose only root is the stock full-registry loop.

### 5. Event map contract probe

This is the most compatibility-sensitive diagnostic.

For `ModifyBakingResult` and `BakingCompleted`, capture per-mod-handler use of:

- `get` / `getOrDefault`;
- `containsKey`;
- `keySet`;
- `entrySet`;
- `values`;
- iterator creation/consumption;
- `forEach`;
- `replaceAll`;
- `put`, `putAll`, `remove`, `compute*`, `merge` where applicable;
- requested namespace vs handler mod namespace.

A wrapper can capture this precisely but changes map identity/class and is therefore **diagnostic-only observer-effect instrumentation**. If exact identity is a concern, the safer alternative is bytecode/callsite instrumentation scoped to the actual registry identity; do not silently treat a forwarding wrapper as behavior-neutral.

The report must list each mod handler and whether it is:

- point-lookup-only;
- finite explicit-key mutation;
- full-universe iterator;
- `replaceAll`/unknown full-map transform;
- cross-namespace reader;
- identity/reflection-sensitive/unknown.

### 6. Custom geometry census

Record:

- registered geometry loader IDs;
- every model resource that invokes `IGeometryLoader.read`;
- every custom geometry parent/dependency lookup;
- whether the resource was otherwise in the pre-title demand closure.

This does not prove side-effect freedom. It tells us how much of the 44k universe would be excluded by a conservative “custom-loader models stay eager” rule.

### 7. Sprite demand

For each baked model/material lookup, record the `Material -> atlas + texture ID` edge. Then classify block-atlas sprites as:

- referenced by a pre-title demanded baked model;
- referenced only by stock eager models that otherwise have no pre-title root;
- atlas-source forced (custom sprite source, custom constructor, metadata dependency);
- unreferenced by the model graph.

Also record custom `SpriteContentsConstructor` use. A sprite not referenced by a demanded model is still not automatically safe to omit because atlas sources and mod hooks may introduce non-model consumers.

## Counterfactual ceiling without changing behavior

Before implementing any lazy behavior, calculate a first-order wall ceiling from one stock diagnostic run.

### Per-resource task intervals

For each async resource task record:

```text
(resource_id, worker, start_ns, end_ns, classification)
```

After the title marker, replay the branch completion logic offline while deleting tasks classified as candidate-deferred. For each future branch, compute the last retained dependency completion. This gives a **current-schedule counterfactual**:

```text
counterfactual_branch_done = max(end_ns of retained required tasks)
recorded_branch_done       = actual future completion
current_schedule_ceiling   = recorded_branch_done - counterfactual_branch_done
```

This is more useful than summing task durations. It respects overlap and directly estimates tail work that held a future open.

It is still not a production speedup prediction: deleting work can also reduce I/O/CPU contention and make retained tasks finish earlier. A later stripped A/B is required.

### Existing hard opportunity envelopes

Current measurements constrain what is worth pursuing:

- **Block models:** parse is only ~9.3% of resource task-sum; a JSON-parser-only change has a small ceiling relative to payload/open work. The entire ~63.24 s task-sum is not wall time and must not be advertised as savings.
- **Blockstates:** ~92.0% of stack-task work is `openAsReader`; avoiding payload opens is structurally more valuable than a faster Gson parse.
- **Blocks atlas:** final geometric stitch is only ~0.876 s out of ~55.38 s outer wall (~1.58%). A faster packer cannot recover the dominant branch.
- **Sprite decode/resource work:** the non-stitch portion of the outer atlas scope is an opportunity envelope of roughly 54.5 s on this heavily instrumented laptop run, **not** an achievable saving. Required sprites, shared worker waiting and profiler observer effect are included.
- **Global preparation:** no combined candidate can save more critical wall than the `ModelManager` preparation gate it occupies. Resource branches overlap and must never be summed.

### Decision thresholds

Before building a production candidate, require both:

1. a meaningful current-schedule gate ceiling, not merely task-sum; and
2. a sufficiently large candidate-deferred set after compatibility barriers.

Suggested research gate:

- fast PC: target >= ~1 s credible preparation/E2E ceiling;
- 4-thread laptop: target >= ~5 s credible preparation/E2E ceiling;
- no measurable regression on the other machine.

These are research prioritization thresholds, not claims of expected savings.

## Semantic verifier

The verifier must follow the successful pattern from PR #62: candidate semantics are computed and compared, while stock remains authoritative until equivalence is established.

### Model/blockstate verifier

For every candidate dynamic load or bake reached before title, compare against stock:

- absent vs present vs missing-model result;
- resource source-pack identity/provenance;
- selected blockstate variants/multipart results and canonical order;
- dependency/parent resolution result;
- custom geometry class/path and resolution outcome;
- baked-model metadata;
- quad count/order and exact vertex ints;
- tint, direction, shade, AO, sprite identity;
- item overrides/transforms;
- repeated lookup identity where consumers can observe it.

Any mismatch marks the candidate unsafe and returns/keeps stock.

### Event verifier

In verify mode, real NeoForge events continue to receive the stock registry. In parallel, maintain a candidate registry simulation and replay the observed access/mutation sequence against it.

Compare after each handler:

- key presence/absence;
- `get` return missing/null/value semantics;
- map size and key universe where queried;
- mutation result;
- final baked registry contents/identity for changed entries.

If a handler performs a full-universe operation that the candidate cannot emulate exactly, that handler becomes a materialization barrier. The fail-open behavior is **full stock materialization before the handler**, not a best-effort partial view.

### Reload/fail-open rules

- cache lifetime is one resource reload unless a later persistent design has independent invalidation proof;
- any resource-pack change resets all demand/load state;
- any unexpected custom loader behavior, recursion anomaly, event-map mismatch or missing-model disagreement disables the candidate for that reload;
- failure falls back to the existing stock/production paths, including the current Decocraft and generated-item optimizations;
- no hard runtime dependency on ModernFix.

## Safe vs unsafe lazy boundaries

### Relatively safer, pending verifier

1. **Eager universe, lazy block-model payload:** preserve resource IDs/provenance but defer `Resource.open` + deserialization until a genuine model dependency requests the file.
2. **Eager blockstate ID universe, lazy stack payload:** preserve known stack IDs but defer `openAsReader`/Gson until a specific block is demanded.
3. **Lazy baked computation after an already-loaded unbaked model:** only if event/map consumers do not force full pre-title baking and the existing production bake optimizations remain active.

Even (1) is not universally safe because custom `IGeometryLoader.read` currently runs at parse time. The trace must quantify that surface.

### Unsafe as a first implementation

1. Replacing `topLevelModels` with a generic lazy forwarding map without tracing iteration/default methods.
2. Returning a partial `bakedRegistry` to `ModifyBakingResult`/`BakingCompleted`.
3. Namespace-restricting lookups without exact-pack evidence; cross-namespace model access is legal in practice.
4. Treating an unobserved `get` as proof that a resource’s parse/custom-loader side effects can disappear.
5. Deferring atlas admission such that later sprites force repacking; existing baked UVs would become invalid.
6. Moving texture upload/OpenGL work off the render thread.

## Sprite-specific architecture

Sprite work is the largest single resource branch on the laptop, but it is not the best first lazy implementation.

The block atlas needs stable dimensions/regions before models can bake UVs. NeoForge also allows custom `SpriteContents` construction. Three distinct ideas must not be conflated:

### A. Demand-pruned atlas membership

Load/stitch only sprites referenced by pre-title demanded models.

**Current decision: NO-GO.** Later addition can require repacking and invalidate already-baked UVs. A separate page/stable-allocation architecture would be required.

### B. Layout-first, decode/upload-later

Read enough metadata/PNG header information to reserve the exact final atlas region, but defer expensive full pixel decode and/or upload until first use.

**Research-only possibility.** This preserves region geometry in principle but must prove:

- exact frame size and animation metadata availability;
- identical mipmap behavior;
- custom `SpriteContentsConstructor` compatibility;
- upload/animation lifecycle correctness;
- no render-thread stalls larger than the startup saving.

A ceiling profiler should first measure how much of `loadSprite` is metadata/open/read/NativeImage decode per demanded vs undemanded sprite. Do not implement this from the current aggregate alone.

### C. Persistent decoded-resource cache

Potentially valuable for large Decocraft PNGs, but this is an independent persistent-cache project with exact JAR/resource-pack invalidation. It is not part of the first model-lazy candidate.

## Comparison with ModernFix dynamic resources

ModernFix 1.21.1 is important prior art, but its implementation demonstrates why this is not a one-mixin change.

Observed source behavior on branch `1.21.1`:

- wraps `ModelBakery.topLevelModels` for dynamic `get`;
- uses locks and in-flight recursion guards;
- uses LRU maps for unbaked/baked caches;
- skips initial item-model registry loading;
- dynamically loads specific blockstates;
- replaces the exposed baked registry with an emulated dynamic map;
- adds integrations for item/block shapers, overrides and other consumers;
- wraps NeoForge model events per mod;
- synthesizes a model-location universe for `keySet`/`containsKey` compatibility;
- special-cases problematic mod visibility;
- implements a `replaceAll` heuristic specifically because loading every model is extremely slow.

It also still performs meaningful eager universe work: it enumerates blockstate keys, all blocks/items for its model-location universe, model registry keys, and item-model resources for compatibility.

### BootOptim difference

BootOptim should initially target a smaller boundary:

1. preserve the logical ID universe;
2. quantify how much payload work can disappear without emulating the entire registry;
3. only introduce top-level/baked-map laziness if the measured ceiling requires it and the exact-pack event contract is tractable.

If ModernFix dynamic resources is effectively enabled in the exact pack, BootOptim must not install an overlapping production lazy model architecture. Detection must be optional/fail-open and must not create a hard dependency. The effective pack setting is being investigated separately; this document does not infer it from #69’s failed reflection probe.

## Minimal hybrid designs, in escalation order

### H0 — demand profiler only

No behavior changes. Produce exact counts, event map contract census, custom-loader census and counterfactual wall ceiling.

**GO now.**

### H1 — eager universe, lazy payload

Keep resource/blockstate keys eager. Defer expensive payload access only for objects not reached by the verified pre-title graph. Preserve stock event/baked-map behavior.

Benefits:

- attacks the measured `openAsReader` and non-parse model task cost;
- smaller compatibility surface than a lazy baked registry;
- leaves PR #64, Decocraft reuse and existing bake semantics intact.

Limit:

- if stock `ModelBakery` still makes every block/item a top-level root, much of the payload will eventually be forced before `ModifyBakingResult` anyway;
- custom geometry parse-time behavior may eliminate a large part of the safe set.

**Conditional GO only after H0 ceiling.**

### H2 — selective top-level deferral with materialization barriers

Allow point-lookups to load models dynamically, but materialize the full stock universe before any handler/consumer whose observed contract requires complete iteration or unknown semantics.

This can win only if those barriers occur late enough or do not force most of the universe before title.

**NO-GO until H0 proves the event surface is favorable and H1 ceiling is insufficient.**

### H3 — emulated dynamic baked registry

ModernFix-class architecture: synthetic key universe, dynamic entries, per-mod event compatibility, broad consumer integrations.

**NO-GO for BootOptim at present.** Only reopen if H0 shows very large top-level deferability, H1/H2 cannot realize it, and the semantic verifier can cover the exact pack.

### Sprite lane

Keep separate from H1-H3. First quantify demand and layout-preserving decode ceiling. Do not bundle a sprite lifecycle rewrite into the first model candidate.

## GO / NO-GO matrix

| Candidate | Current decision | Reopen / promote criterion |
| --- | --- | --- |
| demand graph + counterfactual ceiling | **GO** | diagnostic branch, no behavior change |
| H1 model payload deferral | **CONDITIONAL GO** | large verified defer set + meaningful gate ceiling + custom-loader policy |
| H1 blockstate payload deferral | **CONDITIONAL GO** | large verified defer set + measurable open-reader wall leverage |
| selective top-level lazy models | **NO-GO now** | event/map trace shows point-lookup-dominant behavior and H1 is insufficient |
| generic lazy `Map` / full dynamic registry | **NO-GO** | only after explicit architectural reopening evidence |
| demand-pruned/repacked atlas | **NO-GO** | requires stable late-admission design; current UV semantics prohibit naive version |
| layout-first sprite decode deferral | **RESEARCH ONLY** | per-sprite demand/decode ceiling + exact region/mipmap/custom-constructor verifier |
| faster geometric stitcher | **NO-GO as P0** | current stitch is only ~0.876 s of ~55.38 s outer blocks-atlas scope |

## Required H0 report

The next diagnostic should emit one bounded summary at the first-title marker with at least:

```text
models.enumerated=44103
models.eager_payload=...
models.pretitle_unique_get=...
models.pretitle_transitive=...
models.stock_eager_only=...
models.custom_loader=...
models.unobserved=...
models.candidate_defer=...
models.candidate_defer_task_ms=...
models.counterfactual_branch_wall_ms=...

blockstates.enumerated=11435
blockstates.pretitle_specific=...
blockstates.stock_eager_only=...
blockstates.candidate_defer=...
blockstates.candidate_defer_open_reader_ms=...
blockstates.counterfactual_branch_wall_ms=...

sprites.blocks_atlas=19732
sprites.pretitle_material_refs=...
sprites.custom_source_or_constructor=...
sprites.stock_eager_only=...
sprites.candidate_defer=...
sprites.candidate_defer_load_task_ms=...
sprites.counterfactual_blocks_atlas_wall_ms=...

events.modify.handlers=...
events.modify.point_lookup_only=...
events.modify.full_iteration=...
events.modify.replace_all=...
events.modify.cross_namespace=...
events.completed.*=...
```

The report must separately state:

- `observed_not_requested`;
- `candidate_defer_under_current_model`;
- `semantically_verified_safe`.

Those fields must never be collapsed into one count.

## Final architectural answer

The current 1.21.1 + NeoForge pipeline makes all measured model resources, blockstate stacks and block-atlas sprites exist before the title because the resource/model/atlas architecture eagerly enumerates and materializes the universe. Existing profiling does **not** tell us how many are title-screen necessities.

The evidence strongly suggests that avoiding payload work could have much larger leverage than optimizing parsers/bake arithmetic, especially on weak storage/CPU hardware. But exact compatibility means the project must first measure logical demand and full-map/custom-loader contracts.

Therefore the correct next step is **not “make ModelBakery lazy”**. It is:

1. measure the pre-title demand graph;
2. reconstruct the wall ceiling of deleting only unneeded tasks;
3. quantify compatibility barriers;
4. verify a candidate side-by-side while stock remains authoritative;
5. only then choose the smallest hybrid boundary whose ceiling is worth the complexity.

Until H0 produces those counts, production lazy ModelBakery is **NO-GO**.

## Sources / project evidence

- PR #14 — eager bake parallelism, rejected.
- PR #36 — top-level identity reuse, limited/rejected.
- PR #47 — resource reload critical-path and deep ModelManager profiling.
- PR #57 — post-promotion residuals and rejected material caches.
- PR #61 — generated-item span index, superseded by #64.
- PR #62 — semantic verifier for direct generated-item baker.
- PR #64 — production direct generated-item baker.
- PR #66 — negative live `ElementsModel` traversal ceiling.
- PR #68 — laptop startup-scaling JFR documentation.
- PR #69 — exact resource/model pipeline decomposition.
- NeoForge 1.21.1 `ModelManager.java.patch`, `ModelBakery.java.patch`, `ModelEvent`, `GeometryLoaderManager`, `ExtendedBlockModelDeserializer`, and `SpriteResourceLoader.java.patch`.
- ModernFix branch `1.21.1`, especially dynamic-resources `ModelBakeryMixin`, `ModelManagerMixin`, `ForgeHooksClientMixin`, and `ModelBakeEventHelper`.
