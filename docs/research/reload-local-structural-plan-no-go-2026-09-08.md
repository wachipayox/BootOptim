# Reload-local structural BlockModel plan no-go — 2026-09-08

Status: **REJECTED / NO-GO FOR THE CURRENT RELOAD-LOCAL PLAN PREMISE**

Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This note is Agent 50's closure of PR #160's proposed first falsification step: a reload-local immutable structural plan for ordinary vanilla-element `BlockModel`s that still binds current-generation sprites, calls stock/NeoForge `FaceBakery`, constructs fresh baked models and preserves ModelManager/NeoForge publication callbacks. No production code is added.

The conclusion is intentionally stronger than “this implementation was slow”: after inspecting the later model-pipeline experiments, every material source of savings available to that reload-local representation has already been exercised by an equal or more optimistic mechanism. Re-implementing the same work as a new scalar IR would rename rejected mechanisms rather than change their premise.

## Required prior art

The architecture note in PR #160 correctly restricts any safe representation to pre-sprite/pre-bake data and requires fresh generation-local sprites/baked objects plus normal `ModelEvent.ModifyBakingResult` and `ModelEvent.BakingCompleted` boundaries. It recommended a reload-local shadow verifier before persistence.

Subsequent and earlier experiments close the relevant reload-local submechanisms:

- PR #36: top-level exact-identity bake reuse removed 64.57% of eligible repeated calls but saved only about 0.413 s in `bakeModels` and did not improve startup. Existing recursive bake caching is already authoritative above this path.
- PR #66 (closed pipeline experiment, not listed in the task prompt): a reload-local compiled Elements plan attached to the persistent defining `BlockModel` flattened repeated inherited element/face traversal while keeping current materials, sprites, `ModelState`, `BlockModel.bakeFace`/`FaceBakery` and builder callbacks. Its exact shadow verifier matched 5,448/5,448 repeated geometries with 0 mismatches and 0 fallbacks. The safe candidate was slower than stock (`189.190 ms` candidate vs `179.709 ms` stock); an intentionally optimistic unsafe ceiling that removed live-structure validation was still slower (`102.394 ms` vs `91.267 ms`). This directly rejects traversal/dispatch flattening as a positive ceiling in the measured workload.
- PR #152: per-model resolved-material memoization after parent resolution regressed hosted exact-pack medians by `+22,825 ms` TTMM and `+9,005 ms` reload-to-FancyMenu. The cache is not production.
- PR #170: direct reload-local Elements material plan, preserving order, current sprite getter, `FaceBakery`, `ModelState` and `IModelBuilder`, regressed hosted medians by `+2,097 ms` TTMM and `+2,213 ms` reload-to-FancyMenu.
- PR #171: allocation-light, exact-sized, thresholded revision of #170 again regressed all reported medians; candidate minus control was `+9,236 ms` TTMM and `+4,451 ms` reload-to-FancyMenu. The PR was closed and explicitly says not to repeat the same direct-plan premise without a different ownership/scheduling hypothesis.
- PR #153: cull-direction/model-state precomputation produced a severe eager physical regression and later surgical/lazy variants did not establish a coherent startup win. It is not a reason to embed direction caching in a structural plan.
- PR #177: list pooling changes allocation ownership only; it is separate and still experimental. A structural plan must not claim the same `BlockModel#getMaterial` temporary-list allocation as unexplored.

The post-promotion profiler also bounded the ordinary Elements residual on the fast reference workload: `ElementsModel.addQuads` measured `3,110.528 ms`, `BlockModel.bakeFace` accounted for `1,472.434 ms`, and the entire non-face residual was `1,638.094 ms`. That residual contains material resolution, sprite lookup, face iteration and builder/cull dispatch together; it is an upper bound, not removable work.

## Isolated replay first-pass evidence (#178)

PR #178's isolated phase replay was used before deciding whether to write runtime code. Its successful run `34162510177` produced fixture `exact-pack-model-graph-767328e84de51424`. These are deterministic graph/work units, not TTMM measurements.

Fixture facts:

- `32,081` selected model parse tasks and `32,081` model-bake tasks;
- `10,210` parent edges, with maximum parent depth 4;
- `4,757` distinct parent targets;
- 570 parents have fan-out >=2, covering 6,023 children; maximum observed fan-out is 339;
- the graph builder selected one logical resource per winning model; the fixture records 4,000 shadowed logical resources rather than scheduling duplicate winning parses.

The phase replay itself reported stock four-worker makespan `19,084.529452` work units versus `75,770.919305` single-worker, while its reported critical-path value stayed `286.055746` units across stock/critical-first/small-first policies. This cannot promote or reject a Minecraft optimization by itself, but it makes two points relevant here:

1. reload-local core model parsing is represented once per selected logical model, so there is no replay evidence for a large duplicate vanilla parse loop that a same-reload plan could simply memoize;
2. parent fan-out creates structural sharing opportunities, but PR #66 already tested the strongest exact-identity inherited-geometry reuse form and found a negative timing ceiling.

## Why the remaining reload-local IR collapses to rejected mechanisms

Under the task's invariants, a safe reload-local structural plan cannot persist work across reloads and cannot keep baked models/sprites. Its possible savings are therefore limited to work performed after the current reload has already produced the stock model graph and before/during current-generation baking.

### 1. Flatten ordered elements/faces

This is PR #66's mechanism. A scalar encoding can change memory layout, but the work it removes is the same element/face traversal and lookup. #66's trusted/unsafe ceiling was already slower than stock on the exact repeated-geometry subset. A new array/record encoding has no demonstrated multi-second ceiling and would require another hot-path dispatch plus plan construction.

### 2. Pre-resolve texture/material parent chains

A per-face structural plan is the strongest form of this idea because it can replace a dynamic lookup with direct plan data. PR #170 did exactly that; #171 removed the obvious compile/list/boxing overhead and still regressed. PR #152 independently showed that memoizing final `Material`s is worse than stock. Replacing `Material` with canonical material/sprite IDs does not change the ownership premise: the plan still has to be built during the same reload and current sprites still must be resolved at bake time.

### 3. Precompute cull/model-state mappings

This is PR #153's family and is not a new structural-plan saving source.

### 4. Move parse/parent work into the plan

A reload-local plan built *after* stock deserialization/parent resolution cannot remove that work; it only adds compilation. Building the plan *instead of* the stock `BlockModel` graph would change the premise materially, but it is not a safe BootOptim reload-local cache: NeoForge's `ExtendedBlockModelDeserializer`, custom loaders, root transform/render-type/visibility metadata, dependency discovery, malformed-resource behavior and callbacks can observe or depend on the stock unbaked graph. Constructing both representations preserves semantics but restores the cost; skipping the stock representation lacks a strict fail-open proof.

Therefore the current #160 H3 falsification step has now been falsified by later evidence: within a reload, after stock parsing/resolution and while retaining current sprites/`FaceBakery`/builder/events, the structural work available to precompile is either already shown non-beneficial (#66/#170/#171/#152/#153) or too entangled with stock object ownership to remove safely.

## Canonical verifier requirements if this direction is ever reopened

A future materially different implementation still needs a shadow verifier. It must compare, before NeoForge post-bake callbacks and while returning stock output:

- model resource ID plus winning source/provenance;
- ordered parent chain and parent-closure identity/digest;
- ordered element identity/ownership and raw float-bit values for `from`, `to` and rotations;
- ordered face keys and face records, UV raw bits/rotation, tint, cull source direction, shade/light-emission and supported NeoForge face metadata;
- unresolved texture references and the final resolved `Material` atlas/texture IDs used by the current reload;
- relevant root/model transformations and model-state inputs;
- stock/candidate fallback/error class and reason, including malformed references/cycles;
- canonical baked quad output if lowering is tested: culled bucket, order, raw vertex ints, tint/direction/shade/light-emission and sprite identity.

The verifier must be reload-generation scoped. Any resource-pack reload starts a new generation and discards every plan/digest object; there is no cross-reload publication in this rejected design.

## Decision

**No runtime implementation, exact-pack smoke or 3x3 A/B is justified for this premise.** Doing so would repeat mechanisms that already failed a stricter or more optimistic test. No laptop request is justified.

This is a **no-go documented**, not an incomplete experiment.

## Minimum materially different next step

If ModelManager remains dominant after the current pool/ZIP/scheduling investigations, the next model-architecture step should change ownership rather than encoding:

1. extend the #178 class-level replay to instantiate the real NeoForge 1.21.1 `ExtendedBlockModelDeserializer`/parent-resolution path and record exclusive CPU/allocation by parse, parent resolution, texture-chain resolution and ordinary Elements lowering;
2. require a canonical semantic digest over the fields above;
3. only if that replay proves a multi-second exclusive cost outside `FaceBakery` and outside the already rejected material/traversal/cull families, investigate a version-guarded replacement at that exact ownership boundary;
4. if the only way to remove the cost is to avoid constructing the stock unbaked graph, treat that as a NeoForge/model-loader API redesign requiring explicit compatibility proof, not as another BootOptim cache/mixin.

Reopening criterion: new exclusive evidence must identify work that is not traversal flattening, material memoization/pre-resolution, cull-direction caching, top-level identity reuse or direct Elements-plan compilation. A large count, allocation reduction or task-sum decrease is insufficient without ModelManager-barrier/TTMM leverage.
