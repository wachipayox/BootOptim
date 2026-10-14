# ModelBakery owner / geometry attribution — 2026-09-09

## Scope

PR #214 proved the first-reload ModelManager serial dependency chain on hosted exact-pack: `model_bakery_prepare` -> `model_manager_load_models` (containing `model_bakery_bake`) -> owner-thread `ModelManager.apply`. Its final profile measured 8.960 s prepare, 9.925 s loadModels with 8.780 s nested bakeModels, and 0.420 s apply; the dependency-union task path was 19.305 s. Those are causal task intervals, not additive listener timings.

This experiment asks what data/model families own the prepare and bake work. It does not reopen PR #36 top-level identity reuse, PR #43 side-load memoization, PR #14 eager bake parallelism, atlas/block-model phase addition, or the Stage-B structural plan rejected by PR #196.

## Instrumentation

Activation is default-off:

`-Dboot_optim.profileModelBakeryAttribution=true`

During `ModelBakery.bakeModels`, the probe observes `ModelBakerImpl.bakeUncached`. A per-thread lexical stack subtracts nested elapsed time before aggregation, so the reported quantity is exclusive work that survived Minecraft's real recursive baked-model cache. Bounded top tables are emitted for:

- semantic family (`generated_item`, ordinary element model, multipart/multivariant, custom geometry runtime class, other unbaked runtime class);
- resource namespace when the `BlockModel` carries one;
- runtime class plus protection-domain code-source leaf, useful for distinguishing model/geometry implementation owners;
- custom-geometry identity first-vs-repeat exclusive work, diagnostic only.

The custom-geometry identity counter never substitutes a result. It exists to discover a repeated expensive computation below the already-effective baked cache; a high repeat count without repeat exclusive time is not a candidate.

The constructor probe reuses only the three disjoint 1.21.1 boundaries already validated in PR #13: blockstate registration, item/dependency registration, and parent resolution. Item registration is additionally aggregated by item namespace. `constructor - these disjoint spans` is reported as a residual; atlas and async block-model/blockstate resource preparation are outside this subtraction.

No per-face/material clock is added. PR #57 already measured the repeated material-resolution premise and both tested material-cache mechanisms failed to establish a bake or TTMM win. Millions of additional clocks would increase observer effect without answering a new question.

## Semantic contract

The probe does not replace an executor, future, listener, barrier, resource, model result, callback, classloader, thread, or GL operation. Constructor redirects invoke the same original method once, synchronously and in original order. `bakeUncached` uses HEAD/RETURN observers only. The attribution mixin has lower priority than the existing generated-item direct-bake mixin so its early-cancel return is part of the observed method and frames can be validated as balanced. Mixin configuration remains `required=false` / `defaultRequire=0`.

Model equivalence is therefore by construction: the observer has no model-output write/cancel path. Runtime validation additionally requires the exact-pack resource contract, normal menu endpoint, unchanged block-atlas dimensions, zero BootOptim Mixin errors, and zero abandoned/corrupt attribution frames.

## Decision rule

A family/owner becomes a redesign target only if it owns material **exclusive time**, not merely calls. A custom geometry family with large repeat-exclusive time can justify a loader/mod-specific canonical immutable preparation or transformation reuse experiment, but only after checking state/material/sprite/callback dependencies. A large constructor residual instead calls for a new exact boundary inside preparation rather than guessing from subtraction.

PR #199 scaling/closure tooling is a second-stage causal test only for a dominant mod family. A reduced variant with invalid resource selection remains diagnostic-only and cannot establish model/menu equivalence or authorize production. A valid complement/closure may corroborate ownership, but the full-pack probe remains the primary attribution because it preserves the exact resource graph.
