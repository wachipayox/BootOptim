# BakePlan safe-domain census for the exact 1.21.1 pack

**Date:** 2026-09-11  
**Agent:** 132  
**Authority:** `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`  
**Diagnostic PR:** #265  
**Status:** **COMPLETE — NO-GO for the generic 1.21.1 BakePlan runtime candidate**

## Question

PR #261 found one architecture that was worth testing before any runtime scheduler: build a version-pinned `BakePlan` from the current 1.21.1 graph, detach compute only for transitive closures that are demonstrably vanilla-safe and effect-free, and commit results in the original stock order. Any custom/dynamic/effectful or unprovable closure would remain entirely stock.

This PR performs only the prerequisite census. It does **not** schedule a bake, retain or reuse a `BakedModel`, publish a partial result, alter the baked cache, change iteration order, touch GL/render ownership, or move a callback.

## Probe and semantic key

The probe is opt-in with:

```text
-Dboot_optim.profileBakePlanCensus=true
```

It observes `ModelBakery$ModelBakerImpl.bake(ResourceLocation, ModelState, Function)` using the real 1.21.1 cache-key semantics confirmed by NeoForge's 1.21.1 `ModelBakery` patch:

```text
(model id, ModelState rotation/transformation, uv-lock)
```

The census key therefore follows the semantic operation rather than model-object identity. This deliberately does not reopen PR #36's rejected identity-result-cache premise.

`bakeUncached` frames use the same same-thread exclusive-time principle as PR #217: child bake elapsed time is removed from the parent frame. The classifier runs outside the measured frame. Top-level roots are wrapped around the existing serial `Map.forEach` action and execute in exactly the same order.

The diagnostic retains only classification metadata keyed by semantic operation. It never retains a baked result.

## Strict classification gate

A closure can be `safe` only when every observed operation in its transitive bake closure is audited as effect-free under the exact 1.21.1/NeoForge contract.

Local classification in this census is intentionally conservative:

- exact vanilla `MultiVariant` / `MultiPart`: locally eligible, but the final classification is the transitive closure;
- custom geometry inherited or installed through `BlockGeometryBakingContext`: opaque (`custom_geometry_loader`);
- generated-item root: opaque (`dynamic_generated_item`);
- built-in entity root: opaque (`builtin_entity_extension`);
- `BlockModel` subclass: opaque (`extension_model_class`);
- unknown/custom `UnbakedModel`: opaque (`custom_or_unknown_unbaked_model`);
- ordinary exact `BlockModel`: opaque (`warning_effect_unproven`).

The last rule is the important one. In 1.21.1 an ordinary `BlockModel` bake still crosses the supplied texture getter and NeoForge's block-geometry baking context. This census does not wrap, suppress, reorder, or virtualize those surfaces, so runtime class identity is not evidence that missing-texture diagnostics, warning ordering, extension lookups, or other observable effects are absent. Under #261's gate, absence of proof means opaque.

Cache hits inherit the already-observed classification for the same semantic key. A hit before any observed definition would become `dynamic_lookup_unknown_cache_hit`, never speculative-safe.

## Hosted exact-pack gate

Exact-pack workflow run: `34614433689`  
Benchmark job: `103312870129`  
Artifact: `10269862911`  
Artifact digest: `sha256:2d08867a565a8e57a78148025530ab3f71564bfaaa6e6a5c39310c8a12939699`

Contract result:

- resource selection: **valid 14/14, exact same order**;
- `reload_count=1`;
- main menu reached: **91,552 ms** (health only, not a performance comparison);
- blocks atlas: **8192 x 8192 x 2**;
- BootOptim Mixin errors: **0**;
- census corrupt frames: **0**.

Build run `34614433796` and normal Startup Benchmark `34614433690` are also green.

No A/B was run because this PR changes no runtime scheduling and claims no speedup.

## Census result

Exact summary:

```text
calls=411257
safe_calls=0
opaque_calls=411257
safe_exclusive_ms=0.000
opaque_exclusive_ms=9640.235
safe_share_percent=0.00
roots=334964
safe_roots=0
opaque_roots=334964
semantic_keys=76294
recursive_key_calls=3270236
cache_hit_key_calls=3193942
unknown_hit_keys=0
corrupt_frames=0
```

The result is stronger than a small safe domain: **there is no proven-safe transitive closure in the exact pack under the compatibility contract required by #261.**

The exact vanilla wrapper classes are substantial locally but still become opaque after closure propagation:

| Observed class | Calls | Exclusive CPU | Final safe calls |
| --- | ---: | ---: | ---: |
| exact `BlockModel` | 97,887 | 6,320.164 ms | 0 |
| exact vanilla `MultiPart` | 147,906 | 2,821.346 ms | 0 |
| exact vanilla `MultiVariant` | 165,464 | 498.725 ms | 0 |
| **total** | **411,257** | **9,640.235 ms** | **0** |

`MultiPart` and `MultiVariant` are not rejected because their own class is custom. They are rejected because their observed recursive closures reach opaque leaves. Marking those parents safe would therefore require weakening the closure rule that #261 explicitly established.

### Opacity reasons

Observed transitive reason labels:

- `warning_effect_unproven`: 9,272.972 ms associated exclusive CPU;
- `custom_geometry_loader`: 3,086.598 ms;
- `dynamic_generated_item`: 1,705.993 ms;
- `builtin_entity_extension`: 10.358 ms.

These reason totals are **not additive**. A frame can inherit several opacity reasons from different descendants, so reason-associated CPU overlaps by design. Only the class partition / overall 9,640.235 ms total is additive.

Notably, this run observed:

- `unknown_hit_keys=0` despite 3,193,942 semantic-key cache-hit calls;
- no emitted `extension_model_class` reason;
- no emitted `custom_or_unknown_unbaked_model` reason.

So the no-go is not being caused by an unenumerable runtime lookup in this pack. The census can follow the semantic-key activity it sees. The blocker is that the 1.21.1 leaf bake contract does not prove the ordinary `BlockModel` lane effect-free enough to execute ahead of stock order.

## Non-inclusive critical-path context

This census intentionally did not reintroduce the larger #214/#250 structured-DAG instrumentation into the current integration branch. Repeating that observer stack would violate the instruction to reuse existing traces where possible and would add no information to the safe-domain decision.

The latest validated exact-pack non-inclusive ModelManager lane remains PR #250's hosted profile:

- `ModelBakery` prepare: **11,210.177 ms**;
- `bakeModels`: **8,479.859 ms**, nested inside `loadModels` **10,001.565 ms**;
- owner-thread apply/commit: **465.366 ms**;
- ordered apply-turn wait: **1,787.312 ms**;
- lexical dependency-task union `prepare -> loadModels -> commit`: **21,677.108 ms**.

Those are wall/dependency observations from #250 and must not be added to this PR's 9,640.235 ms exclusive CPU total. Likewise, absolute differences between #250/#217 and this single diagnostic run are hosted variance plus observer context, not optimization evidence.

The census does answer the only new causal question needed before runtime work: none of the measured bake CPU belongs to a closure that the proposed generic BakePlan is allowed to detach.

## Decision

**NO-GO: do not implement the generic 1.21.1 BakePlan scheduler/compute-and-commit candidate.**

The gate from #261 was: proceed only if a materially sized transitive closure is both enumerable and demonstrably free of ordering-sensitive effects. The exact pack produced **0.000 ms / 0.00% proven-safe exclusive CPU and 0 safe roots**. Therefore there is no addressable domain for the candidate as specified.

The following are specifically rejected as ways to manufacture a positive result:

1. treating exact `BlockModel.class` as pure merely because no custom geometry object is attached;
2. treating exact `MultiPart` / `MultiVariant` as safe without their full recursive closure;
3. executing first and falling back after an unknown dynamic lookup or warning/effect;
4. suppressing/reordering missing-texture diagnostics to make compute look pure;
5. reusing a baked result or model identity to avoid proving the closure;
6. scheduling opaque work and committing it later in stock order — deterministic commit does not repair earlier side effects.

There is therefore **no next generic runtime candidate** authorized by this line of research. A future reconsideration would require a new, version-pinned proof that ordinary 1.21.1 `BlockModel` leaf computation can be split from every diagnostic/extension side effect while preserving those effects in stock order. That is a different architectural investigation, not a refinement of this census and not permission to parallelize the current bake path.

The previously isolated Decocraft direct-mod preparation path from #250 remains a separate, loader-specific question; this generic census neither validates nor replaces it.
