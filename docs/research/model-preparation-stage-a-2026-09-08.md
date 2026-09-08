# Stage A strict ElementsModel preparation verifier — 2026-09-08

Status: **SHADOW VERIFIER / DEFAULT OFF / NO PRODUCTION SPEEDUP CLAIM**

Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b` (refreshed before branching; the earlier `7c2cb82...` prompt SHA was stale).

## Purpose

This is only Stage A of the structural preparation plan from Agent 44. It asks whether a detached pre-sprite representation can reproduce the strict ordinary `ElementsModel` lowering with enough structural work to justify a later reload-local Stage B. It never substitutes candidate output.

Enable explicitly with:

```text
-Dboot_optim.modelPreparationPlan=verify
```

Any other value, including the default `off`, is the kill switch.

Marker:

```text
BOOTOPTIM_MODEL_PREPARATION_PLAN
```

## Strict domain

The verifier is reached only from the real NeoForge `ElementsModel.addQuads` path and then requires all of the following:

- exact `BlockGeometryBakingContext` and exact vanilla `BlockModel` owner;
- no effective custom geometry/loader;
- identity root transform;
- null render-type hint;
- `owner.getElements()` is the exact list owned by the current `ElementsModel`;
- parent closure consists only of exact `BlockModel` objects and has no identity cycle;
- non-empty, well-formed elements/faces;
- default NeoForge `ExtraFaceData` at both element and face level;
- four-value UV data.

Generated-item and block-entity special roots do not enter `ElementsModel`; custom geometry is rejected explicitly. Parent/JSON errors remain stock-authoritative because classification occurs only after stock parsing and parent resolution have reached the bake path. Model overrides, particle metadata, transforms and all other outer baked-model metadata stay entirely on the stock path and are not replaced by Stage A.

## Detached representation

Each reload-local plan is stored privately on its resolved `BlockModel`, whose lifetime is already reload-local. The plan contains no live `BlockElement`, `BlockElementFace`, `Material`, `TextureAtlasSprite`, `BakedQuad`, `BakedModel`, registry, callback result or GL object.

It stores only immutable records:

- raw IEEE-754 bits for element `from`/`to` and optional rotation origin/angle;
- rotation axis/rescale and shade;
- face iteration order, source direction and optional cull direction;
- tint index and texture identifier;
- four raw UV float values and UV rotation.

For the candidate shadow pass those values reconstruct fresh temporary vanilla element/face objects. Material resolution and sprite lookup use the current `BlockGeometryBakingContext` and current `spriteGetter`; quad generation calls the current stock/NeoForge `BlockModel.bakeFace` path.

## Verification and observable behavior

At `ElementsModel.addQuads` HEAD the candidate is lowered into an internal recording list. The original `ElementsModel.addQuads` body then executes unchanged and remains authoritative. Redirects around its existing `BlockModel.bakeFace` and builder calls only time/record the returned stock quads, then invoke the same stock builder calls in the same order.

The verifier never calls `CallbackInfo.cancel`, never writes a model registry, never invokes a NeoForge model event, never wraps/replaces the `ModelManager.reload` future, and never changes the builder receiving stock output. `ModelEvent.ModifyBakingResult` and `ModelEvent.BakingCompleted` therefore remain single stock invocations at their normal boundaries.

Canonical comparison checks per ordered quad:

- culled/unculled bucket and order;
- exact baked-quad runtime class;
- sprite object identity;
- tint, direction, shade and ambient-occlusion metadata;
- every raw vertex integer.

A mismatch logs at most 16 details, increments the mismatch counter and poisons only that owner plan for the current reload. Candidate exceptions/linkage failures fail open to stock.

## Measurements

The summary marker records separately:

- encountered and eligible `ElementsModel` calls;
- unique plans, detached element count and face count;
- matches, mismatches and fallbacks;
- plan-build wall/current-thread CPU/current-thread allocated bytes;
- candidate lowering wall/current-thread CPU/current-thread allocated bytes;
- candidate `BlockModel.bakeFace` wall;
- stock `ElementsModel` wall/current-thread CPU/current-thread allocated bytes;
- stock `BlockModel.bakeFace` wall;
- canonical comparison wall;
- the observed stock `ModelManager.reload` future wall boundary;
- GC collection-time delta over that ModelManager reload window.

The allocation counter uses HotSpot `ThreadMXBean` only in verify mode. GC is process-global collection time and must not be interpreted as allocation attribution by itself.

The candidate and stock phase sums can overlap other ModelManager work across worker threads; they are task-sum/exclusive observations, while `modelmanager_barrier_ms` is the wall boundary. They must never be added together as one saving.

## Relation to rejected work

This does not repeat #36/#66 top-level/traversal reuse, #170/#171 material plans, the rejected per-model material cache, or #186/#188 Gson collection pre-sizing. In particular, a high plan count or 0 mismatches is not a performance result. The candidate deliberately reconstructs detached objects in Stage A so the semantic boundary can be measured before designing a lower-allocation Stage B.

The real-class replay from #183 remains useful only for pre-sprite/pre-bake provenance and error/equivalence checks. It is not TTMM evidence and does not exercise this current-sprite/FaceBakery verifier.

## Hosted exact-pack gate for Stage B

Stage A should run hosted exact-pack with verify enabled and stock control disabled. Promotion to a separate Stage B branch requires all of:

1. zero verifier mismatches and no startup/Mixin errors;
2. non-trivial strict-domain coverage in the exact pack, with exclusion counters reported;
3. `plan_build`, candidate lowering, stock lowering, candidate/stock FaceBakery, allocation, CPU and GC all reported separately;
4. evidence that structural/non-FaceBakery work is materially large relative to the `ModelManager` wall barrier, not merely many hits/plans;
5. unchanged atlas/menu behavior and normal stock publication/callbacks.

Because verify intentionally performs both candidate and stock, its TTMM is expected to be worse and is **not** an acceleration A/B. Stage B is justified only by the measured removable-work ceiling. Stage B must remain reload-local and hosted-first; persistence, disk fingerprints, global serialization and a physical laptop run remain forbidden until a Stage B hosted candidate moves the affected critical wall coherently.

Suggested PR exact-pack directive for the Stage A runtime evidence:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 1
exact-pack-candidate-jvm-arg: -Dboot_optim.modelPreparationPlan=verify
exact-pack-control-jvm-arg: -Dboot_optim.modelPreparationPlan=off
```
