# MoreCulling listener 26 translucency audit — 2026-09-08

Status: **COMPLETE / TECHNICAL CLOSE — no runtime candidate, no laptop gate**

Agent 61 branch base requested by the assignment: `7c2cb82cd68cd86b343b12177810a1116add5902`.

Repository-reference caveat: while starting this audit, GitHub's public branch API resolved `agent/integration-current` to `fa6df8bc8f74aae32338f521bf845a5730ac634b`, while the assignment explicitly required `7c2cb82cd68cd86b343b12177810a1116add5902`. This branch was therefore created from the explicitly requested SHA and does not move either integration ref. The requested `docs/research/five-front-replay-triage-2026-09-08.md` is not present at that SHA and public repository code/commit search did not find it; this note does not invent its contents.

## Question and metric boundary

PR #184 measured the anonymous listener at final reload-list index 26 as **852.055 ms hosted exclusive ordered serial wall** in exact-pack run `34175380705`. This is an apply-slot critical-path measurement, not an inclusive listener duration and not task-sum CPU. PRs #189 and #191 independently resolve the same object to MoreCulling 1.0.8's second `ResourceManagerReloadListener` injected into `Minecraft.<init>`.

The 852.055 ms slot is an observed ceiling for that run, not a promised saving. No current evidence separates its wall into model-map traversal, quad enumeration, UV-bound calculation, sprite pixel scanning, per-state publication, or custom/platform-model dispatch.

## Exact callsite and body

Exact upstream release: `FxMorin/MoreCulling` tag `v1.0.8`, commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`.

`common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java` injects `moreculling$onBlockRenderManagerInitialized` immediately before `NEW LevelRenderer`. Its second consecutive reload listener is:

```java
((BlockModelShaperAccessor) blockRenderManager.getBlockModelShaper()).getModels()
        .forEach((state, model) -> {
            if (!state.canOcclude()) {
                ((BakedOpacity) model).moreculling$resetTranslucencyCache(state);
            }
        });
```

PR #189 dynamically mapped it twice on fresh hosted VMs to final listener index 26 / transformed callsite `Minecraft.java:30050`; PR #191 statically maps the source synthetic outer body to `lambda$moreculling$onBlockRenderManagerInitialized$3(ResourceManager)` and its nested map callback to `$2(BlockState,BakedModel)` (subject to Mixin unique-renaming). The listener ignores its `ResourceManager` argument and contains no direct GL call.

The traversed input is the **published current-generation `BlockModelShaper` state -> baked-model map**. Every non-occluding state is dispatched through `BakedOpacity.moreculling$resetTranslucencyCache(state)`.

## Concrete model implementations

### `SimpleBakedModel_cacheMixin`

This is the important pixel-scan path. For every `culledFaces` entry it allocates `new ArrayList<>(entry.getValue())`, records an empty-face bit when the copied list is empty, and—until translucency becomes true—walks its `BakedQuad`s. Each scanned quad:

1. obtains `quad.getSprite()` as MoreCulling `SpriteOpacity`;
2. reads the sprite's unmipmapped `SpriteContents.originalImage` as `NativeImage`;
3. computes integer UV bounds with `VertexUtils.getQuadUvBounds(quad, image width, image height)`;
4. calls `SpriteOpacity.moreculling$hasTranslucency(bounds)` -> `SpriteUtils.doesHaveTranslucency(...)` to scan CPU-side image pixels.

If translucency is still false after culled faces, it repeats the same quad/bounds/pixel path over `unculledFaces`.

Publication is exactly two writes to the current `BlockState` through MoreCulling's `MoreStateCulling` API:

- `moreculling$setHasQuadsOnSide(emptyFaces bits)`;
- `moreculling$setHasTextureTranslucency(translucency)`.

Crucially, the **derived pair `(emptyFaces, translucency)` does not otherwise read `state`** in this implementation. Therefore exact `SimpleBakedModel` object identity is a valid semantic reuse domain *within one invocation/generation*: repeated states pointing at the same exact model can share the derived pair while retaining owner-thread per-state publication.

### `BuiltInModel_cacheMixin`

Reads only its `particleTexture` translucency and writes `moreculling$setHasTextureTranslucency(...)` to the state. The computation is likewise model/sprite-dependent rather than state-dependent, but this path is not separately measured and is unlikely to dominate the slot.

### `WeightedBakedModel_cacheMixin`

For all six directions it calls MoreCulling's platform `Services.PLATFORM.getQuads((BakedModel)this, state, face, RANDOM, EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`, derives empty faces, and asks each returned `BakedQuad` for `QuadOpacity.moreculling$getTextureTranslucency()`. The passed `state` is part of quad selection, so model identity alone is **not** a valid reuse key.

### `MultiPartBakedModel_cacheMixin`

Has the same six-direction `Services.PLATFORM.getQuads(..., state, ...)` dependency and therefore is also state-dependent. Its model may additionally contain selectors/predicates tied to `BlockState`. Model-identity reuse is unsafe here without a stronger state/output equivalence proof.

### BakedQuad cache interaction

MoreCulling's `BakedQuad_cacheMixin.moreculling$getTextureTranslucency()` lazily memoizes a whole-sprite translucency Boolean on each quad. Weighted/MultiPart therefore already avoid re-running that whole-sprite test after the quad cache is populated. `SimpleBakedModel_cacheMixin` does **not** use that quad Boolean: it computes per-quad UV bounds and calls bounded sprite translucency directly, which explains why repeated `(NativeImage identity, bounds)` scans remain real.

## Real repetition already measured

Diagnostic PR #108 / exact-pack run `33975337605` measured the same MoreCulling startup family before the index-26 callsite was known:

- `SpriteUtils.doesHaveTranslucency` calls: **602,308**;
- exact repeated `(NativeImage identity, integer bounds)` calls under the bounded tracker: **450,293**;
- direct wall in those repeated scans: **395.731 ms**;
- result mismatches: **0**;
- tracker cap: 4,096 unique keys, after which additional work was deliberately not promoted to equivalent-hit evidence;
- exact listener/model-hook coverage reported by that diagnostic was incomplete (`81.222%` model-level coverage), so these counts are a measured lower-bound family, not a complete per-implementation decomposition.

This is the strongest real repetition evidence relevant to slot 26. It is direct scan work, not critical wall saved.

PR #119 then implemented exact reload-generation-local `(NativeImage identity,bounds)` reuse. Its smoke proved activation (`hits=451707 misses=150713 stores=4096 ... failed_open=false`) and semantic gates, but two hosted 3x3 campaigns contradicted each other:

- run `33981861574`: TTMM **+15.005 s** candidate regression, reload->FancyMenu **+5.642 s**;
- run `33981907944`: TTMM **-2.774 s**, reload->FancyMenu **-1.305 s**.

The design also placed a global `synchronized` monitor on a path called roughly 600k times. It was rejected. The durable conclusion is not that repeated scans are fake; it is that their measured ~396 ms direct-work ceiling did not support that mechanism or a stable startup claim.

PR #192 is orthogonal to this listener: its `VoxelShape#getFaceShape` cache targets listener 25. Its hosted regression and physical non-critical result do not validate any translucency optimization for listener 26.

## Candidate evaluation

### 1. Another pixel/bounds cache

**Closed as a duplicate premise.** #119 already proved exact identity+bounds equivalence and real repetition. A different lock-free table could lower mechanism overhead, but its measured direct-repeat wall ceiling was only 395.731 ms in the diagnostic family and its end-to-end hosted signal was unstable. Repeating the same key-space with a different hash table is not enough new evidence.

### 2. Reload-local derived index by exact baked-model identity

There is a materially different semantic premise for **exact `SimpleBakedModel` (and possibly `BuiltInModel`) only**: compute the `(emptyFaces, translucency)` summary once per exact current-generation model object and apply the two MoreCulling state writes for every mapped state. This removes repeated `ArrayList` copies, quad iteration, UV-bound calculation and bounded pixel scans together, not just the final pixel loop.

However this audit does **not** implement it because the economic discriminator is missing: existing diagnostics do not report (a) how many non-occluding map entries are exact `SimpleBakedModel`, (b) unique model identities vs state entries, or (c) exclusive wall/CPU owned by second-and-later states for an already-seen simple-model identity. The full slot ceiling is only 852.055 ms hosted, and the already-measured exact pixel-repeat component is 395.731 ms direct wall. An external BootOptim interception of a MoreCulling-added method/listener would add Mixin/version/maintenance risk that is not justified from counts alone.

Safety boundary if reopened:

- key by exact current-generation **model object identity**, never resource id or pack bytes;
- scope to one reload/listener invocation; no persistence and no cross-generation reuse;
- restrict reuse to implementations whose derived result is proven state-independent (`SimpleBakedModel`; `BuiltInModel` only if measured material);
- never reuse `WeightedBakedModel`, `MultiPartBakedModel`, arbitrary/custom `BakedOpacity`, or platform `getQuads` results by model identity;
- keep all `MoreStateCulling` state writes on the original apply/owner thread in original state-map order;
- preserve stock fallback for unknown model classes or any instrumentation mismatch.

### 3. Detached prepare + owner-thread commit

A stricter SimpleBakedModel-only split is mechanically conceivable after the listener gets its current-generation map: snapshot eligible `(state, exact-model)` entries, compute per-model summaries detached, join, then publish state flags on the original apply thread.

This is **not authorized now**. The source proves direct GL absence, but does not provide an upstream thread-safety contract for concurrent reads of all baked-quad/sprite/`NativeImage` data or for interaction with other modded model wrappers. Starting workers only at slot 26 also cannot overlap the already-finished ModelManager preparation gate; it would only try to reduce this <=852 ms serial slot through parallel CPU. That ceiling is too small to justify adding executor/join semantics before a model-identity profile proves a much larger strict-simple subset and thread-read safety is established.

### 4. Reuse metadata from the current model generation

The cleanest architecture would be for MoreCulling itself (or a controlled fork) to attach a current-generation opacity summary to `SimpleBakedModel` while the model/quads are already being built, then have listener 26 perform only owner-thread state publication. BootOptim currently has no safe generic metadata handoff from ModelManager that proves MoreCulling's exact bounded-UV semantics, and model-building callbacks/custom loaders remain arbitrary. Therefore precomputing this summary earlier would duplicate MoreCulling behavior or move it across lifecycle boundaries without equivalence evidence.

## Decision

**Technical close; no runtime change, no hosted optimization A/B, no laptop build.**

What is proven:

- slot 26 is MoreCulling 1.0.8's second apply listener;
- its #184 hosted ceiling is **852.055 ms exclusive serial wall**;
- exact source paths and publication fields are known;
- bounded image/bounds repetition is real: **450,293** repeats / **395.731 ms direct repeat wall**, zero mismatches in #108;
- #119 already tested that exact pixel-reuse family and did not produce a stable startup win;
- only `SimpleBakedModel`/`BuiltInModel` have a source-level state-independent derived result suitable for exact-model-identity reuse; Weighted/MultiPart/custom models do not.

What is not proven:

- current hosted slot-26 CPU time separate from wall;
- exact per-model implementation share;
- exact model-identity fan-out among non-occluding states;
- second-and-later simple-model identity wall/CPU;
- a thread-safety contract for detached NativeImage/quad reads;
- any TTMM saving from a model-summary mechanism.

## Reopening condition

Reopen only with a **low-overhead diagnostic** that stays in stock listener order and reports, for slot 26 only:

1. non-occluding state entries by exact model implementation;
2. unique exact model identities and repeated-state fan-out for strict `SimpleBakedModel`/`BuiltInModel`;
3. wall + current-thread CPU attributable to first vs repeated exact model identities, with pixel-scan wall kept separate;
4. state/output verification showing that repeated exact-simple-model identities produce identical `(emptyFaces, translucency)` before any reuse.

Proceed to a runtime candidate only if that diagnostic establishes at least a **material majority of the 852.055 ms exclusive slot** (suggested >=400-500 ms hosted) as strict state-independent repeated-model work. Then use a default-off, exact-version fail-open implementation with a per-reload identity index, owner-thread commit, activation marker, exact-pack smoke and fresh-VM A/B 3x. Promote to physical only if the critical-path/reload boundary moves coherently; hits, task-sum CPU, or inclusive work reduction are insufficient.

## References

BootOptim: PR #108 diagnostic, PR #119 rejected translucency cache, PR #184 critical-slot profiler, PR #189 dynamic callsite attribution, PR #191 static callsite attribution, PR #192 shape-cache experiment.

MoreCulling v1.0.8 (`6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`):

- `common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/models/SimpleBakedModel_cacheMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/models/BuiltInModel_cacheMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/models/WeightedBakedModel_cacheMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/models/MultiPartBakedModel_cacheMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/BakedQuad_cacheMixin.java`
- `common/src/main/java/ca/fxco/moreculling/mixin/TextureAtlasSprite_opacityMixin.java`
