# ElementsModel cull-direction precomputation — 2026-09-07

**Status: LIMITED / INCONCLUSIVE hosted experiment.** This branch is not production. The
candidate is disabled unless `-Dboot_optim.elementsCullDirectionCache=true` is
present.

## Why this is a different premise

The rejected `BlockModel.getMaterial` cache added per-face string/map bookkeeping
and regressed the exact pack. This experiment does not cache materials or model
results. It targets the remaining `ElementsModel.addQuads` face loop: stock
repeatedly maps each culled face's direction through the same immutable
`ModelState` rotation. The candidate computes the six direction mappings once
per `ElementsModel` bake invocation, then keeps stock material lookup,
`FaceBakery`, culling decisions and `IModelBuilder` callbacks.

The candidate also walks `faces.entrySet()` so the face value is obtained with
the same map iteration rather than a key-set iteration followed by a second
`Map.get`. It does not move work across threads, retain resources across a
reload, or alter atlas/model objects.

## Safety boundary

The mixin is opt-in and fail-open when absent. Root-transform composition is
performed once exactly as in NeoForge's stock method. Every face still calls
`IGeometryBakingContext.getMaterial`, `BlockModel.bakeFace`, and the original
builder method in source-map order. The candidate must be rejected if any
custom geometry path, model/atlas count, visual output, Mixin status or startup
critical-path metric changes unexpectedly.

## Decision gate

Run the pinned exact-pack 3×3 A/B first. A positive result must move the
ModelManager/bake or time-to-main-menu critical path, not merely task-sum CPU.
A small coherent hosted win can justify one physical laptop check because the
operation is CPU-dense and the laptop's model-bake path has historically scaled
about 5–9× versus the hosted four-processor surrogate. A neutral or negative
hosted result closes this specific loop without spending a laptop launch.

## Hosted A/B result (2026-09-07)

The candidate activated successfully, with zero BootOptim Mixin errors and an
unchanged `8192x8192x2` atlas. The three fresh-VM totals were:

| Variant | Run 1 | Run 2 | Run 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| candidate | 64,930 ms | 66,132 ms | 91,575 ms | 66,132 ms |
| control | 93,771 ms | 95,727 ms | 74,592 ms | 93,771 ms |

The nominal median delta is `-27,639 ms`, but it is not a coherent mechanism
signal: the third candidate is `16,983 ms` slower than the third control while
the first two candidate VMs are roughly 28–30 seconds faster than their
controls. The exact-pack hosted VMs are too variable in this campaign to
attribute the median movement to a direction-map micro-optimization. No laptop
run is justified from this A/B, and the broader `ElementsModel` section remains
open for a more discriminating premise or measurement.

## Reopening

Reopen only with a materially different source-level premise, such as a
profiler proving a separate repeated operation inside `BlockModel.bakeFace` or
`IModelBuilder` is dominant. Do not return to generic per-face material caches.
