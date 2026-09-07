# Model/resource-reload scheduling and canonicalization closure — 2026-09-06

Status: **LIMITED / NO-GO for a new runtime scheduler change**

Base audited: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

This audit asks whether the remaining 1.21.1 client resource/model critical path contains a false dependency that BootOptim can remove by rescheduling or canonicalizing work, without repeating top-level bake parallelism, superficial identity caches, FancyMenu preload work, global hot-path locks, renderer deferral, or custom-loader callback parallelization.

The result is a source-level closure rather than a runtime experiment. The stock graph already overlaps the independent ModelManager preparation branches, and the remaining serial edges either carry real data dependencies or are publication/compatibility boundaries. No candidate with a material demonstrated critical-path ceiling survives the semantic screen, so no hosted A/B is requested.

## Integration and overlap check

The task's starting SHA `8fdcce08...` is stale. `agent/integration-current` is currently `145c10c2...`, a descendant of that SHA. This audit uses the current integration tree as authority.

Relevant existing work was checked before proposing anything:

- #14 rejected eager top-level model-bake parallelism: isolated bake improved, TTMM did not.
- #36 rejected top-level identity reuse as a significance play: 64.57% reuse moved bake only ~0.413 s and did not improve end-to-end startup.
- #43 rejected Mixin side-load memoization (~41.7 ms estimated saved for ~55 MiB retained).
- #47 established the correct barrier/turn interpretation for `SimpleReloadInstance`; listener-inclusive durations cannot be summed.
- #57 measured post-promotion ModelManager residuals.
- #75 attributed the historical ordered apply tail and identified `ModelManager.apply` as atlas upload + publication/callback work.
- #95/#102 rejected renderer first-consumer defer after a black/frozen physical menu.
- #116/#117 close the unsafe FancyMenu defer/cooperative-wait directions.
- #119/#121 reject the globally locked MoreCulling hot-path cache.
- #124/#126 separately own constrained-hardware executor-parallelism policy; this audit does not change worker counts or executors.

The task also names `docs/research/fancymenu-initial-layout-preload-2026-09-05.md`. That file is not present in the public current integration tree or public PR search. The task explicitly states that the corresponding FancyMenu optimization lives in a private fork, so this audit treats it as an external fixed boundary and does not duplicate it in BootOptim.

## Exact 1.21.1 dependency graph

Minecraft 1.21.1 `ModelManager.reload` has this shape (cross-checked against the public 1.21.1 source and NeoForge's `1.21.1` ModelManager patch):

```text
reload
  ├─ loadBlockModels ───────┐
  ├─ loadBlockStates ───────┼─> ModelBakery construction ─────┐
  └─ AtlasSet.scheduleLoad ─────────────────────────────────────┤
                                                               v
                         all(ModelBakery, all atlas StitchResult futures)
                                                               |
                                                               v
                         loadModels / bakeModels
                           - sprite lookup from StitchResults
                           - complete baked top-level registry
                           - NeoForge ModifyBakingResult callback
                           - build BlockState -> BakedModel cache
                                                               |
                                                               v
                         all StitchResult.readyForUpload
                                                               |
                                                               v
                         PreparationBarrier.wait
                           = global allPreparations + ordered turn
                                                               |
                                                               v
                         apply on apply/render executor
                           - StitchResult.upload for every atlas
                           - publish baked/model-group/missing-model state
                           - NeoForge onModelBake callback
                           - replace BlockModelShaper cache
                                                               |
                                                               v
                         listener future complete -> ordered next apply
```

`ProfiledReloadInstance`/`SimpleReloadInstance` therefore expose two different constraints that must not be conflated:

1. preparation listeners may overlap on the prepare executor;
2. apply is held behind the global preparation barrier and then ordered by listener registration.

PR #47 is the project precedent for measuring those edges: `allPreparations`, each listener's ordered turn, post-turn work, and `allDone` are the useful critical-path boundaries.

## Candidate dependency checks

### 1. Atlas preparation versus ModelBakery construction

**Already overlapped.** `loadBlockModels`, `loadBlockStates`, and all atlas loads are launched before the join. `ModelBakery` construction depends only on the two model JSON futures and starts independently of atlas completion.

PR #57 post-promotion exact-pack measurements were:

- block states: **803.424 ms wall**;
- block models: **2,629.861 ms wall**;
- atlas stitch: **1,631.079 ms wall**;
- ModelBakery construction: **4,852.089 ms wall**;
- `bakeModels`: **8,129.116 ms wall**;
- ModelManager preparation barrier: **16,698.258 ms critical-path elapsed from listener start**;
- global `allPreparations`: **16,714.306 ms critical-path elapsed**.

The subphase values overlap/nest and are not additive. Structurally, however, they show the important ordering: after block-model JSON finishes, ModelBakery construction alone is much longer than the atlas branch. The all-atlas join is therefore already satisfied well before the bakery branch reaches `loadModels`; replacing it with per-atlas readiness cannot remove a material gate in this measured workload.

### 2. Bake before all atlas StitchResults

**Real data dependency.** `bakeModels` resolves each `Material` to a sprite through the atlas-specific `StitchResult`. A per-model/per-atlas DAG could theoretically start models whose referenced atlases are ready, but the exact-pack evidence above gives it no current wall ceiling because all atlas futures finish earlier than ModelBakery construction.

Implementing that DAG would also require discovering a model's complete material dependency set before its authoritative bake. Custom model geometry/loaders can resolve models/materials dynamically, and NeoForge's baking callbacks expect the complete eager result. A safe fallback for every custom/dynamic case would retain the current global join while adding complexity to a branch that is not currently critical.

**Decision:** reject without implementation; no measurable critical-path premise.

### 3. Overlap atlas upload with `bakeModels`

This is the only source-level edge that looks independent in pure computation: prepared atlas pixels exist before the bake completes, while the upload itself does not compute baked quads.

It is **not semantically independent** in the live client. `StitchResult.upload` mutates the already registered `TextureAtlas` objects on the render/apply side. Running those uploads before `PreparationBarrier.wait` would publish new texture contents while the old baked registry/model cache is still authoritative and while other reload listeners are still in preparation. Loading overlays, mods, or other render consumers could observe a mixed generation.

A shadow/staging texture design would avoid publishing pixels early only by introducing new GL objects and a later swap. That changes texture identity/lifetime, must preserve references held by mods, requires extra VRAM/cleanup/failure semantics, and is no longer a small fail-open scheduler change. It also cannot move GL work off the render thread.

**Decision:** semantic/visual NO-GO. #95/#102 is a concrete project precedent that reaching title lifecycle with partially published renderer state is insufficient; the first visible frame must remain correct.

### 4. Move NeoForge bake callbacks earlier or parallelize them

**NO-GO by contract.** NeoForge 1.21.1 adds `ClientHooks.onModifyBakingResult(...)` after the complete top-level bake and `ClientHooks.onModelBake(...)` during apply after publication of the baked registry/model groups/missing model. These are arbitrary mod compatibility boundaries. Reordering or parallelizing them would change observable callback order/threading and can break custom model loaders or mods that inspect the complete maps.

### 5. Canonicalize work across preparation and apply

No duplicate material intermediate was found at the boundary. `StitchResult` is already the prepared atlas object consumed by bake sprite lookup, `readyForUpload`, and later upload; the model maps built during preparation are the same maps published during apply. Canonicalizing by superficial model/sprite identity would repeat #36/#57-style rejected premises without a new equality/invalidation argument.

## Why the global barrier is not the current target

PR #57 measured ModelManager's preparation barrier at **16,698.258 ms** and global `allPreparations` at **16,714.306 ms**: only **16.047 ms critical-path wall** separated ModelManager reaching its barrier from the global preparation gate.

On the older slow-laptop #75 trace, ModelManager was likewise effectively the preparation gate (~94,350.502 ms versus ~94,350.817 ms all preparations). Relaxing the global barrier specifically for ModelManager could therefore recover at most a tiny observed gap before apply, while taking on the mixed-generation publication hazards above.

The large historical time after preparation was ordered apply/turn work, not a reason to make arbitrary apply listeners concurrent. #75's slow-laptop intervals were post-turn critical-path wall, including ~11.551 s ModelManager apply, ~5.316 s block-entity dispatcher, ~6.839 s entity dispatcher, ~6.507 s LevelRenderer, and ~22.457 s final anonymous/FancyMenu-correlated work. Later subsystem investigations already showed that several of those ceilings were stale, hardware-sensitive, or unsafe to defer. They must not be summed or revived as one scheduler saving.

## Small dependency graph / final classification

```text
(block models || blockstates) -> ModelBakery ┐
                                              ├-> bake -> readyForUpload -> GLOBAL BARRIER
(atlas load/stitch) --------------------------┘                            |
                                                                            v
                                                                    ordered apply turns
                                                                            |
                                  ModelManager upload+publish+callbacks -----┤
                                  renderer/listener state -------------------┤
                                  FancyMenu/private-fork boundary -----------┘
                                                                            |
                                                                            v
                                                                         allDone
```

Safe overlap that exists is already expressed on the left side. The right side is publication/ordered-callback work. The only apparent cross-boundary overlap, early atlas upload, exposes a mixed resource generation and fails the visual/semantic contract.

## Decision

**Close this scheduling/canonicalization lane without runtime code.** There is no single new change that is both:

- source-supported as independent work;
- material on the measured critical path;
- compatible with custom model loaders and NeoForge callbacks;
- render-thread/GL safe;
- and narrow enough to fail open without maintaining two resource generations.

Do not open an exact-pack A/B merely to test a source-level candidate whose demonstrated ceiling is ~16 ms or whose semantics already fail. The separate #124/#126 executor-parallelism lane should make its own decision from its direct reload metric; it does not create a reason to change barriers or callbacks here.

## Reopening criteria

Reopen only if a new #47-style exact-pack diagnostic on the then-current production stack proves one of these materially different premises:

1. an atlas future becomes the actual ModelManager gate by multiple seconds *after* ModelBakery construction is ready; or
2. `readyForUpload` becomes a multi-second non-GL preparation gate that can be safely started earlier without publication; or
3. a newer Minecraft/NeoForge model architecture exposes an immutable, callback-safe compiled intermediate with explicit generation/lifetime semantics that can be backported without changing custom-loader callbacks.

If none occurs, the next useful work should be intrinsic to the current dominant preparation/bake operation, not another generic reload scheduler rewrite.

## Evidence

- BootOptim #14: https://github.com/wachipayox/BootOptim/pull/14
- BootOptim #36: https://github.com/wachipayox/BootOptim/pull/36
- BootOptim #43: https://github.com/wachipayox/BootOptim/pull/43
- BootOptim #47: https://github.com/wachipayox/BootOptim/pull/47
- BootOptim #57: https://github.com/wachipayox/BootOptim/pull/57
- BootOptim #75: https://github.com/wachipayox/BootOptim/pull/75
- BootOptim #95/#102 renderer visual rejection: https://github.com/wachipayox/BootOptim/pull/95 and https://github.com/wachipayox/BootOptim/pull/102
- BootOptim #124/#126 scheduler lane: https://github.com/wachipayox/BootOptim/pull/124 and https://github.com/wachipayox/BootOptim/pull/126
- NeoForge 1.21.1 `ModelManager` patch: https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/client/resources/model/ModelManager.java.patch
- Public 1.21.1 decompiled `ModelManager` cross-check used only for control-flow inspection: https://github.com/NadienDev-MC-Mods/ModernChickens/blob/d22dc53bc8a47f348cdb5adf3b1eb05914a36a29/Minecraft_Client_Source_1.21.1/net/minecraft/client/resources/model/ModelManager.java
