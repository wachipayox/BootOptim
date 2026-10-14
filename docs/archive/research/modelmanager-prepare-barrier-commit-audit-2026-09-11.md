# ModelManager prepare → barrier → ordered commit audit — 2026-09-11

Status: **ARCHITECTURE AUDIT / GENERIC NO-GO / PRECISE REOPENING DEFINED**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This Agent 115 audit consolidates the current integration tree with the open/closed ModelManager and ModelBakery research stack. It adds no runtime code, executor, cache, resource publication, GL work, callback movement or gameplay behavior. It deliberately does not repeat PR #14 eager bake parallelism, PR #36 top-level identity reuse, PR #47 listener-barrier attribution, PR #217 bake ownership attribution, PR #221 ModelBakery-constructor residual attribution, or PR #222's Decocraft prepare/commit experiment.

## Question

Can the first resource reload be restructured as a real `prepare -> barrier -> ordered commit` pipeline that moves the actual ModelManager critical path while preserving complete model maps, current-generation sprite/model state, callback/failure ordering, and render-thread ownership?

The answer is **not generically, from current evidence**. A narrower pre-sprite immutable preparation boundary is semantically real for strict domains, but no current experiment proves a detached preparation stage that executes off the existing gated ModelBakery/bake chain without duplicating work or changing observable lifecycle. The next experiment must measure exactly that missing scheduling edge rather than add another cache or count optimization.

## Current non-inclusive critical path

PR #214 is the newest validated structured-DAG profile for this subsystem. Exact-pack profile `34290311738` passed resource selection, one reload, `8192x8192x2` blocks atlas and zero BootOptim Mixin errors. Its phase/barrier values overlap and are not summed:

- resource reload phase wall: `37,267.935 ms`;
- global `allPreparations`: `25,612.598 ms`;
- ModelManager preparation arrival: `25,571.753 ms`;
- block-state future: `3,437.724 ms`;
- block-model future: `6,617.002 ms`;
- aggregate atlas futures: `7,525.087 ms`;
- ModelManager ordered apply-turn wait: `1,120.559 ms`;
- ModelManager apply/commit: `420.262 ms`.

ModelManager reached its preparation barrier only about `16.56 ms` before global preparation opened, so it was the actual preparation gate in that run. The lexical dependency chain was:

`model_bakery_prepare 8,959.851 ms -> model_manager_load_models 9,924.805 ms -> model_manager_apply_commit 420.310 ms`.

`model_manager_load_models` contains `model_bakery_bake 8,779.811 ms`. The dependency-interval union for that chain was `19,304.966 ms`; the whole-trace inclusive task-wall sum (`40,133.997 ms`) is intentionally not interpreted as recoverable time. After ModelManager completed, another roughly `10.131 s` remained to reload `allDone`; that is a separate ordered-listener tail, not ModelManager savings.

PR #217 reproduced the same topology on another valid exact-pack profile (`34293289041`) with a dependency-union chain of `15,817.312 ms`: ModelBakery prepare `6,614.092 ms`, loadModels `8,731.542 ms` containing bake `7,915.213 ms`, then commit `471.678 ms`. Absolute cross-run differences are hosted variance/observer context, not optimization deltas.

The causal conclusion is robust: the interesting ModelManager lane is the serial model-preparation/model-bake branch before the listener's stock preparation barrier, followed by a small ordered owner-thread/apply commit. Atlas readiness is not a demonstrated direct gate in these profiles.

## What owns the gated work

PR #217 measured `411,257` `bakeUncached` calls and `7,516.255 ms` of **exclusive cache-miss work**. The largest families were Decocraft BBGeometry `3,777.802 ms` (50.26%), ordinary BlockModel elements `1,761.978 ms` (23.44%), vanilla multipart `854.446 ms` (11.37%), generated item `615.896 ms` (8.19%), multivariant `265.391 ms`, and NeoForge OBJ geometry `183.906 ms`.

That profile also disproved another identity-cache premise: Decocraft's 3,527 first identities consumed `3,227.384 ms` exclusive while 10,581 repeated identities consumed only `550.418 ms`. A repeated-call percentage is therefore not a critical-path ceiling.

For ModelBakery construction, #217 measured `6,613.675 ms`: blockstate registration `2,739.858 ms`, item/dependency registration `100.027 ms`, parent resolution `354.761 ms`, and residual `3,419.029 ms`. PR #221 then split a slower valid run and left `5,627.884 ms` after tiny vanilla/FFAPI/NeoForge-extra-model callsites. Source/log attribution places that residual predominantly in CITResewn lifecycle/resource work injected directly into ModelBakery construction: active-CIT reload/publication plus item-CIT unbaked asset loading. Those operations mutate reload-generation state and populate unbaked models before parent resolution; they are not a generic immutable vanilla-preparation bucket.

Therefore a generic ModelBakery split would cross two incompatible classes of work: pure-ish model/geometry computation and arbitrary third-party reload lifecycle/publication. The latter cannot be moved behind a worker barrier or replayed later without an explicit compatibility contract.

## Dependency and publication boundaries

Minecraft 1.21.1 already starts block models, block states and atlas preparation concurrently. ModelBakery waits on block-model/block-state inputs; `loadModels` then consumes ModelBakery plus current atlas stitch results. Existing #57/#132/#214 evidence shows the model branch, not atlas readiness, reaches the preparation gate last in the relevant runs.

The safe boundary is consequently **not** early atlas upload. A `TextureAtlasSprite` belongs to the current atlas generation and atlas upload mutates live registered atlas objects. Moving it earlier would expose new atlas state while the old baked registry/model cache or other reload listeners may still be authoritative. OpenGL and live atlas mutation remain render-thread-owned.

The same applies to model publication. NeoForge `ModelEvent.ModifyBakingResult` observes the complete mutable baked-model map with the current texture getter/ModelBakery, and `ModelEvent.BakingCompleted` occurs after ModelManager installs the new model registry/group state. These are complete-map/generation compatibility boundaries. Arbitrary models may not remain pending after the initial reload barrier merely so the menu can open.

## The strongest prepare/commit proof: Decocraft #222

PR #222 is important because it tested the requested architecture rather than only reducing calls. It attached a reload-local primitive geometry representation to each Decocraft `BBGeometry`. Preparation contained geometry/group rotations and raw face/UV structure. Commit retained current material resolution, current `TextureAtlasSprite` lookup/interpolation, `ModelState.getRotation()` evaluation, facing and fresh `BakedQuad` construction. A structural fingerprint forced stock fallback on mutation.

The final exact-pack pair (`34299359452`, head `55bdb58172b4519d7e54a82f089288b76813a546`, artifact `10084459670`) passed the semantic/resource contract: one reload, valid selected packs, atlas `8192x8192x2`, zero BootOptim Mixin errors. It exercised 3,527 prepares and 3,527 commits for 964,046 quads with zero prepare failures and zero commit fallbacks.

Most importantly, output equivalence passed: control and candidate had identical sprite-local geometry/UV/metadata aggregates, and all eight raw vertex lanes matched. This demonstrates that a **pre-sprite/pre-ModelState geometry intermediate is semantically possible for this exact Decocraft domain**.

But it did not create critical-path overlap. Preparation cost `4,133.246 ms`; commit cost `1,558.717 ms`; both were observed on `Worker-ResourceReload-1`. The implementation therefore paid about `5.692 s` of work on the same worker, versus #217's earlier `3.227 s` exclusive ceiling for all first-identity Decocraft work. In the final paired run, candidate minus control was `+1,907 ms` main-menu wall and `+3,015 ms` reload-to-FancyMenu wall. This is a rejected implementation, not a production candidate.

The lesson is architectural: identifying a pure intermediate is insufficient. To move TTMM, **prepare must be produced before the gated bake edge from data that is already immutable/available, and the stock bake call must consume it without recomputing the same preparation**. #222 prepared inside each `BBGeometry` constructor but still wound up on the same reload worker and duplicated enough computation to lose.

## Later Decocraft evidence does not reopen the generic pipeline

PR #224 V2 found a narrower in-place semantic optimization: keep the entire stock `applyElementRotation` body and suppress only repeated `rotateVertexBy` math where exact-input corner reuse had been verifier-proven. Verify-only execution found `2,527,029` matches, zero mismatches and zero input-alias fallbacks. The V2 exact-pack pair preserved final sprite-local output for 3,527 models / 964,046 quads with valid resources and one reload.

One same-VM pair moved reload-to-FancyMenu by `-990 ms` and main menu by `-3,325 ms`, but that single pair was explicitly not promotion evidence. A later physical control/candidate pair had a misleading `+12.160 s` total TTMM shift caused mostly before the feature could run; the causal post-entrypoint delta was `-2.325 s`, leaving the physical result inconclusive/directionally positive rather than a no-go. These data show that eliminating actual geometry work can matter; they do **not** prove a detached prepare/barrier stage or authorize a generic ModelManager scheduler.

## Decision on the generic architecture

**NO-GO: do not implement a generic `ModelManager prepare pool -> barrier -> publish partial/complete later` redesign.**

Reasons:

1. The existing scheduler already overlaps the independent resource/model/atlas input branches.
2. The actual ModelManager gate contains custom loaders and third-party reload lifecycle (notably CITResewn), not only deterministic vanilla geometry.
3. ModelBakery/ModelBaker caches, current sprites, `ModelState`, custom geometry and NeoForge callbacks define generation-local mutable semantics.
4. `ModifyBakingResult` and `BakingCompleted` require complete-map semantics; publishing a menu while arbitrary models remain pending changes observable behavior.
5. The one directly tested prepare/commit split (#222) proved semantic separability for a narrow domain but failed to create overlap and regressed the relevant reload/TTMM metrics.
6. Generic top-level eager parallelism (#14) already improved isolated bake while regressing menu time, so another executor/pool policy without a different dependency premise is specifically ruled out.

No runtime code is added by this audit because #214/#217/#221 already provide the necessary instrumentation on open diagnostic stacks. Re-implementing their probes from integration would duplicate observability and make comparison harder.

## Precise reopening: detached immutable-preparation eligibility probe

The next useful experiment is narrower than a production optimization and different from #222. It should answer one binary question:

> For a proven-safe model family, does a materially expensive immutable preparation product become available **before** the existing `model_bakery_prepare -> model_manager_load_models` gated edge, and can stock-order bake/commit consume it without recomputation?

Start with only a version-pinned/fail-open domain whose boundary is already semantically demonstrated, preferably Decocraft 3.0.11 first-base geometry or a strict ordinary vanilla `ElementsModel` subset. Do not include CITResewn/custom loader state in the generic domain.

The diagnostic must record, in the same structured trace generation as #214:

- source/unbaked-model availability time;
- immutable prepare begin/end, producer thread and dependency identity;
- the stock ModelBakery/bake call's first need for that product;
- explicit `blocked_on(prepared_product)` only if it actually waits;
- sprite/atlas readiness and the current-generation binding point;
- ordered ModelManager apply turn and owner-thread commit;
- fallback reason for every unsupported/mutated/custom case.

The critical metric is **dependency-interval union to ModelManager preparation barrier and then menu**, not prepare CPU sum or number of eligible models. The experiment is a no-go if preparation still begins on the same serial bake worker after the model branch is already the gate, if the bake path must redo substantial preparation, or if shifting prepare simply moves the global barrier to another listener with no endpoint movement.

For a Decocraft-specific fork/probe, the desired architecture is:

`immutable BB model graph available -> direct/non-reflective primitive prepare -> future/result owned by this reload generation -> original BBGeometry bake reaches commit -> await only if needed -> bind current material/sprite + invoke current ModelState in stock order -> construct fresh quads -> continue existing complete-map callbacks -> stock ModelManager preparation barrier -> ordered apply commit`.

Preparation may execute on a resource worker only if the exact Decocraft source proves the traversed graph immutable for that interval and no callback/receiver state is skipped. Commit remains where stock bake currently performs sprite/ModelState-dependent work; no GL or atlas upload moves. Any optional Decocraft hook must fail open to the stock path.

## Equivalence gate for any future candidate

Before a timing claim, a candidate must prove:

- exact selected resource-pack list/order and `reload_count=1`;
- no new Mixin/model errors and the normal menu endpoint;
- identical complete baked-model key set at the NeoForge pre-callback boundary;
- one `ModifyBakingResult` and one `BakingCompleted` in stock order;
- same current-generation sprite identities/atlas generation, never a prior reload's live object;
- canonical baked output for the eligible subset: class/metadata, particle sprite, quad count/order, raw vertex lanes, tint/direction/shade/light metadata and sprite identity;
- exact fallback to stock on unsupported custom loader/model data, mutation, version drift or preparation failure;
- stock failure ordering: a failed prepare must not hide, delay past publication, or convert an error that stock would expose before completion.

For Decocraft, reuse #222/#224's sprite-local quad-equivalence strategy and require an in-world visual check only after a coherent hosted critical-path effect exists. For a strict vanilla structural-plan experiment, build the reload-local shadow verifier first and always return stock until canonical comparison is zero-mismatch.

## Promotion / rejection rule

Hosted exact-pack remains the first runtime gate. A smoke only establishes compatibility, never improvement. Promotion requires a same-origin/same-endpoint repeated comparison in which:

1. the detached prepare is demonstrably outside or overlapping the former gated interval;
2. ModelManager preparation-barrier dependency-union shrinks coherently;
3. reload-to-menu / main-menu endpoint moves in the same direction;
4. resource/callback/model-equivalence gates remain exact.

If only prepare/bake local time or call count falls while the ModelManager barrier and menu do not, reject it. If the hosted result is small/noisy or the mechanism is hardware-sensitive, physical work remains a later arbitration gate; this audit requests no new laptop A/B.

## Final disposition

The broad hypothesis "ModelManager can be accelerated by introducing a generic prepare -> barrier -> ordered commit pipeline" is **discarded for current integration**. The model branch is a real critical path, but its contents are heterogeneous and include third-party lifecycle/publication that cannot be detached generically.

The narrower hypothesis "some model families expose an immutable pre-sprite preparation product that can be prepared earlier and committed in stock order" is **supported semantically but not yet supported causally/performance-wise**. PR #222 proved the representation boundary; it also proved why mere separation is not enough. The next reopening must instrument and then demonstrate **detached availability and overlap before the existing ModelBakery/bake gate**, with no recomputation and complete-map callback equivalence. Until that edge is proven, no generic scheduler, lazy ModelManager, persistent live-model cache, early atlas publication or additional bake executor should be implemented.
