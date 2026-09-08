# MoreCulling 1.0.8 `initShapeCache` algorithmic boundary — 2026-09-08

Status: **PROFILED / NO-GO FOR FACE-CACHE OR GENERIC PREPARE-COMMIT; ONE NARROW REOPENING PROBE DEFINED**

Agent 60. Base authority: `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b` (the assignment SHA `7c2cb82cd68cd86b343b12177810a1116add5902` was stale when this work began).

Related evidence: PR #184 (critical apply-slot attribution), PR #189 (dynamic callsite identity), PR #191 (independent static attribution), PR #192 (rejected face-cache family).

## Question

PR #184 measured the MoreCulling 1.0.8 first reload listener (`Minecraft$$Lambda` index 25) at **3590.560 ms hosted exclusive ordered apply-slot wall**. PRs #189/#191 map it to:

```java
Block.BLOCK_STATE_REGISTRY.forEach(state ->
        ((StateCullingShapeCache) state).moreculling$initShapeCache());
```

The requested premise was whether a MoreCulling-specific algorithm could detach immutable computation from the mutable `BlockState` publication while preserving the original apply-thread callback/order contract.

This pass does not change runtime behavior. It audits the exact 1.0.8 source and the Minecraft 1.21.1 shape/cache machinery to determine whether a safe and material detached subset actually exists after #192.

## Exact MoreCulling 1.0.8 body

Exact upstream tag: `v1.0.8`, commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`.

`BlockStateBase_cullShapeMixin.moreculling$initShapeCache()` does, in order:

1. initialize `voxelShape = null`;
2. for `!canOcclude`, fetch the current baked model and call `BakedOpacity.moreculling$getCullingShape(state)`;
3. if still null, call `Block#getOcclusionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`;
4. classify empty shape -> shared six-empty array;
5. call `Block.isShapeFullBlock(voxelShape)` and classify full -> shared six-full array;
6. otherwise allocate `new VoxelShape[6]`, call `voxelShape.getFaceShape(direction)` for each direction, and publish that array to the MoreCulling-owned per-state field `moreculling$cullingShapesByFace`.

The only externally relevant publication from this method is the final per-state array assignment(s), but the input acquisition is not pure: the block fallback is virtual mod code, and the baked-model path can mutate MoreCulling model state because `SimpleBakedModel_cacheMixin.moreculling$getCullingShape` lazily calls `VoxelShape.optimize()` once and stores the optimized result back in the model.

## New static finding: Minecraft already owns the per-`VoxelShape` face cache

Minecraft 1.21.1 `VoxelShape` already contains:

```java
@Nullable
private VoxelShape[] faces;
```

and `getFaceShape(Direction)` is itself memoized. For a non-empty/non-full shape it first checks `faces[direction.ordinal()]`; only a miss calls `calculateFace`, stores that face in the six-entry array, and returns it.

Therefore a reload-local identity map, a second per-object field, or a global table around `VoxelShape#getFaceShape` cannot remove repeated face derivation that vanilla has not already removed for the same shape object. Those mechanisms add lookup/field/injection overhead around an existing object-local cache.

This materially changes the interpretation of PR #192. Its startup-scoped per-object field mixin added another `VoxelShape[6]` and HEAD/RETURN injections around a method that already has the same cache shape in vanilla. The hosted median regression (`+1540 ms` main menu; `+981 ms` reload->FancyMenu) and the corrected physical post-entrypoint delta (`+697 ms`) are therefore consistent with a redundant cache layer. **Do not reopen any face-cache variant of #192.**

This is stronger than the previous empirical rejection: the mechanism is statically redundant on Minecraft 1.21.1.

## Candidate A: detached callback capture + face preparation

A theoretically semantics-preserving split would be:

1. on the original MoreCulling apply thread, iterate states in stock registry order and execute exactly the stock input acquisition (`BakedOpacity.moreculling$getCullingShape` or `Block#getOcclusionShape`) so arbitrary callbacks/lazy MoreCulling model mutation remain ordered;
2. store detached `(state, voxelShape)` records without publishing `BlockState` cache arrays;
3. derive classification/faces from those captured shapes;
4. on the same original apply turn, commit `moreculling$cullingShapesByFace` in original registry order.

This split is semantically cleaner than moving callbacks, but it is not useful under the current project constraints:

- no generic worker pool is authorized;
- without parallel/overlapped execution, it only adds snapshot storage and a second pass;
- repeated `getFaceShape` work on the same `VoxelShape` is already memoized by vanilla;
- moving the pure phase earlier than the listener would require capturing model/block inputs earlier, which changes callback/apply ordering or observes model state before intervening listeners finish.

Conclusion: **no material prepare/commit win is established**. The detached boundary exists conceptually after input capture, but no critical-wall mechanism remains that can exploit it safely without reintroducing the forbidden scheduling premise.

## Candidate B: reuse `BlockStateBase.Cache.occlusionShapes`

Minecraft 1.21.1 creates `BlockStateBase.Cache` for blocks that do not declare `dynamicShape`. For `canOcclude` states, that cache calls the same block callback with `EmptyBlockGetter.INSTANCE` / `BlockPos.ZERO` and stores all six face-occlusion shapes. `BlockStateBase.getFaceOcclusionShape(...)` returns those cached faces when available.

At first glance this looks like an exact MoreCulling shortcut for the fallback path. It is **not exact MoreCulling equivalence**:

- MoreCulling 1.0.8 deliberately invokes `Block#getOcclusionShape` again at reload time;
- simply reusing the old vanilla state cache can skip that third-party callback and its side effects;
- even if the callback is still invoked for ordering/side effects, publishing faces from the older vanilla cache can differ if a mod returns a different shape now;
- `!dynamicShape` is sufficient for vanilla's own caching contract, but BootOptim's requirement is equivalence to the observed MoreCulling callback execution, not merely to vanilla's minimum contract.

A hard whitelist of exact vanilla block implementations could make a subset provable, but no exact-pack coverage/timing evidence currently shows that such a whitelist owns material time. Adding a broad class whitelist before measuring it would trade compatibility surface for an unknown saving.

Conclusion: **safe only with a much narrower proof, materiality unproven; do not implement yet.**

## Candidate C: algorithmic `Block.isShapeFullBlock` negative specialization

The remaining obvious pure computation in the body is `Block.isShapeFullBlock(voxelShape)`. Minecraft implements it through a global Guava `LoadingCache<VoxelShape, Boolean>` with `maximumSize(512)`, `weakKeys()`, and a miss calculation of:

```java
!Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME)
```

A fail-open MoreCulling-local specialization could return `false` without touching that cache only for a mathematically strict negative domain (for example, shapes whose extents prove they cannot equal the unit block), and delegate every ambiguous case to stock `Block.isShapeFullBlock`.

This is algorithmically distinct from #192: no face cache, no identity table, no new field, no worker, no callback movement, and no `BlockState` publication change. However **there is no timing evidence yet that cache misses in `isShapeFullBlock` own a material fraction of the 3590.560 ms slot**. Bounds/min-max work also has a cost, so implementing before attribution risks another always-on micro-regression.

This is the only reopening premise retained by this pass, and it must start as a low-overhead attribution probe, not an optimization.

Required probe fields for listener 25, preserving the stock body/order:

- state count;
- model-shape vs block-fallback input count and wall;
- empty/full/partial counts;
- exclusive wall spent in `Block.isShapeFullBlock`;
- exclusive wall spent in the six `getFaceShape` calls;
- count of strict negative-fast-path eligibility without changing the return value;
- total listener wall and marker proving the probe actually matched MoreCulling 1.0.8.

Only if `Block.isShapeFullBlock` is itself a material portion of the **exclusive apply slot** and the strict negative domain covers a material portion of that time should a default-off specialization be implemented and sent through hosted A/B.

## Critical-path accounting

Known origin: PR #184 hosted exact-pack Linux/Xvfb/llvmpipe, process-origin startup, ordered apply-turn profiler.

- listener 25 / MoreCulling `initShapeCache`: **3590.560 ms exclusive ordered serial wall**;
- listener 26 / MoreCulling translucency reset: **852.055 ms exclusive ordered serial wall**;
- two MoreCulling listeners together: **4442.615 ms exclusive ordered serial wall**;
- main reload serial tail: about **13.558 s exclusive wall**;
- external tail excluding FancyMenu: about **8.921 s exclusive wall**.

These values are wall on the post-preparation apply critical path. They are not CPU task sums and are not additive to ModelManager's preparation gate. The known physical ModelManager ~150 s gate does not convert a 3.59 s hosted listener slot into a physical saving claim.

## Decision

**No safe-and-material detached subset is demonstrated for listener 25. No runtime candidate and no laptop run are justified.**

What is closed by this investigation:

- per-object/field face cache: closed; vanilla already has it;
- identity/global face cache: closed by project rule and redundant with vanilla per-object caching;
- generic detached prepare/commit: closed under current no-worker/no-callback-reorder constraints because capture remains serial and the reusable face work is already memoized;
- direct reuse of vanilla `BlockStateBase.Cache.occlusionShapes`: not exact MoreCulling equivalence without a stricter whitelist/proof.

What remains open exactly once:

- a diagnostic-only timing/eligibility probe for `Block.isShapeFullBlock` plus source-path attribution. Reopen only if it proves a strict fail-open negative domain owns material **exclusive apply wall**, then run hosted smoke and 3x A/B with an activation marker before considering hardware.

No physical laptop tie-break should be requested from this result. A reduction in calls or a replay/task-sum improvement would not be sufficient; the next candidate must reduce the ordered listener slot and move reload->usable-menu coherently.

## Reproducible source anchors

- BootOptim PR #184: https://github.com/wachipayox/BootOptim/pull/184
- BootOptim PR #189: https://github.com/wachipayox/BootOptim/pull/189
- BootOptim PR #191: https://github.com/wachipayox/BootOptim/pull/191
- BootOptim PR #192: https://github.com/wachipayox/BootOptim/pull/192
- MoreCulling v1.0.8 `BlockStateBase_cullShapeMixin`: https://github.com/FxMorin/MoreCulling/blob/6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682/common/src/main/java/ca/fxco/moreculling/mixin/models/cullshape/BlockStateBase_cullShapeMixin.java
- MoreCulling v1.0.8 `SimpleBakedModel_cacheMixin`: https://github.com/FxMorin/MoreCulling/blob/6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682/common/src/main/java/ca/fxco/moreculling/mixin/models/SimpleBakedModel_cacheMixin.java
- Minecraft 1.21.1 mirror `VoxelShape`: https://github.com/hackersense/OptiFine-Source/blob/b77c5c6995874f6cf2755bc5234428906b337b75/1.21.1/net/minecraft/world/phys/shapes/VoxelShape.java
- Minecraft 1.21.1 mirror `BlockBehaviour`: https://github.com/hackersense/OptiFine-Source/blob/b77c5c6995874f6cf2755bc5234428906b337b75/1.21.1/net/minecraft/world/level/block/state/BlockBehaviour.java
- Minecraft 1.21.1 mirror `Block`: https://github.com/hackersense/OptiFine-Source/blob/b77c5c6995874f6cf2755bc5234428906b337b75/1.21.1/net/minecraft/world/level/block/Block.java
