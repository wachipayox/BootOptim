# Vanilla blockstate bake identity reuse

## Scope

Global client model-bake optimization for Minecraft 1.21.1's `ModelBakery.bakeModels()`.

Kill switch: `-Dboot_optim.deduplicateBlockstateBake=false`

## Bottleneck

`BlockStateModelLoader` can register many top-level `ModelResourceLocation` entries that point to the exact same `UnbakedModel` object. Vanilla still invokes the top-level bake action once for every registered location.

Profiling in the reference pack found 327,029 top-level entries. Among exact vanilla `MultiVariant` / `MultiPart` entries, 202,350 calls referred to an object identity that had already been baked.

## Mechanism

During one `ModelBakery.bakeModels()` call, BootOptim keeps a local `IdentityHashMap<UnbakedModel, BakedModel>`.

Reuse is allowed only when `model.getClass()` is exactly:

- `MultiVariant.class`, or
- `MultiPart.class`.

The first occurrence executes Minecraft's original bake action. Later locations pointing to the identical unbaked object receive the already produced top-level `BakedModel` in `bakedTopLevelModels`.

Custom subclasses and every other `UnbakedModel` type always execute the original path. Iteration remains sequential and preserves the source map's order.

## Why the speedup is smaller than the call-count reduction

Minecraft's internal `ModelBakerImpl` has its own dependency bake cache. Repeating a top-level `MultiVariant`/`MultiPart` bake usually does not repeat all deep geometry work: child model bakes often hit that existing cache. The redundant top-level calls are therefore numerous but comparatively cheap.

## Safety invariants

- Identity (`==`) is required; structural equality is never guessed.
- Only exact vanilla classes are eligible; custom subclasses are excluded.
- The cache exists only for one bake invocation and is then garbage-collectable.
- Execution remains sequential; no shared bake maps are made concurrent.
- The first successful bake remains authoritative.
- Kill switch restores Minecraft's original `Map.forEach` path.

## Observable trade-offs

The optimization creates a transient identity map during model bake. In the measured pack it held roughly 111k reusable identities, so there is a short-lived memory cost rather than a persistent cache.

Because duplicate aliases skip their redundant top-level bake invocation after the first successful identity, a diagnostic tied only to repeating that same successful top-level operation may be emitted fewer times. Rendering/model contents are unchanged for the eligible exact vanilla identities. This is the only known observability difference; custom model behavior is deliberately not deduplicated.

## Measured evidence

Reference-pack warm profiling:

- total top-level entries: `327,029`
- exact safe entries: `313,364`
- unique safe bakes: `111,014`
- reused bakes: `202,350`
- safe-call reuse: `64.57%`
- baseline `model_bake`: about `8,856.6 ms`
- optimized `model_bake`: about `8,444.3 ms`
- isolated bake reduction: about `412 ms` / `4.7%`

No stable end-to-end gain was distinguishable from run-to-run startup variance in the isolated experiment. It remains useful as a low-risk CPU reduction, not as a headline startup optimization.
