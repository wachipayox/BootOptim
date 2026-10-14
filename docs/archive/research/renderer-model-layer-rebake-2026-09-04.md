# Renderer model-layer rebake diagnostic — 2026-09-04

Status: **ACTIVE / DIAGNOSTIC ONLY**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Why this front

PR #47's slow-laptop barrier/turn trace showed that after the global resource-reload preparation gate opens, renderer reconstruction remains serial critical-path work. PR #75 attributed two particularly large ordered post-turn intervals:

- `BlockEntityRenderDispatcher`: about **5.316 s** wall;
- `EntityRenderDispatcher`: about **6.839 s** wall.

Those intervals are serial after the preparation barrier, so unlike overlapping listener preparation task-sums they are real startup wall ceilings. They are also separate from the already-optimized block/item `ModelBakery` paths.

PR #75 identified `EntityModelSet.bakeLayer(ModelLayerLocation)` as a common structural operation in both renderer families. Every call creates a fresh mutable `ModelPart` tree from a `LayerDefinition`. Generic reuse of the returned `ModelPart` is unsafe because renderers mutate pose/visibility state. However, repeated baking of the **same layer key** could still expose a distinct optimization: preserve fresh mutable `ModelPart` instances while reusing immutable/precomputed geometry/topology work below that mutable shell.

No prior PR measures whether this repetition is actually present or expensive in the exact pack. Search of open/closed PRs and active branches found no overlapping `EntityModelSet.bakeLayer` diagnostic.

## Hypothesis under test

The narrow question is:

> During the startup resource reload, how much of the serial block-entity/entity renderer reconstruction wall is spent in `EntityModelSet.bakeLayer`, and how much of that layer-bake time is repeated work for layer keys already baked earlier in the same dispatcher reconstruction?

A positive result requires all of the following:

1. `bakeLayer` is a material fraction of dispatcher wall, not merely a visible helper;
2. repeated layer keys account for a material fraction of `bakeLayer` wall;
3. the repeated work is large enough to justify a copy/prototype architecture instead of direct `ModelPart` sharing;
4. any later candidate can prove per-renderer mutable-state isolation and exact geometry equivalence.

If calls are mostly unique, or repeated-layer wall is small, this candidate stops here.

## Instrumentation

Enabled only with:

```text
-Dboot_optim.profileRendererLayerRebake=true
```

The diagnostic adds no cache and changes no renderer behavior. It scopes timing to the existing first resource-reload callbacks of:

- `BlockEntityRenderDispatcher.onResourceManagerReload`;
- `EntityRenderDispatcher.onResourceManagerReload`.

Inside those scopes it times the authoritative `EntityModelSet.bakeLayer` calls and reports one aggregate marker per dispatcher:

```text
BOOTOPTIM_RENDERER_LAYER_REBAKE
```

Fields distinguish:

- dispatcher total wall and current-thread CPU;
- total layer calls and unique `ModelLayerLocation` keys;
- repeated calls;
- total `bakeLayer` wall;
- first-observation versus repeated-key `bakeLayer` wall;
- residual dispatcher wall outside `bakeLayer`;
- top layer keys by bake wall.

There is no per-call logging and no renderer/provider ordering change.

## Interpretation discipline

- Dispatcher `total_ms` is serial post-turn wall when observed in the initial resource reload.
- `layer_ms` is nested inside that wall and must not be added to it.
- `repeat_layer_ms` is only a **ceiling for eliminating repeated layer construction**, not a claimed saving.
- Hosted exact-pack is useful for structural counts and a coarse wall signal, but the historical multi-second ceilings came from the 4-thread Windows laptop. A production candidate still needs laptop confirmation if the hosted signal is small or allocation/JIT behavior differs materially.

## Candidate architecture if repetition is material

Do **not** share returned `ModelPart` roots. A later implementation should investigate whether the expensive immutable geometry produced from `LayerDefinition` can be compiled once per reload/layer and instantiated into fresh mutable `ModelPart` trees. The safety boundary must treat renderer-visible mutable state as per-instance:

- transform/pose fields;
- visibility/skip-draw flags;
- child `ModelPart` identity;
- any mod accessors/mixins that mutate model-part structure or cube data.

The first production prototype, if justified, should remain reload-scoped and fail open to stock for any layer/model shape whose immutability assumptions cannot be proven.

## Gate

1. Build and normal Startup CI must pass with the property off.
2. Hosted exact-pack smoke with the property on must reach the main menu, emit both scope markers and show zero BootOptim Mixin failures.
3. Only if repeated-layer wall is material should a separate optimization branch be opened.
4. A candidate/control exact-pack A/B then measures dispatcher wall and TTMM, followed by laptop A/B for the final decision.

## Related work

- PR #47 — reload barrier/turn critical-path profiler.
- PR #75 — source-level apply-tail attribution and the original renderer-dispatcher ceilings.
- PR #85/#87 — LevelRenderer split, a separate post-turn lane.
- PR #36/#43 — examples of why call-count repetition alone is not enough to justify caching.
