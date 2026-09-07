# ElementsModel cull-direction precomputation — 2026-09-07

**Status: CURRENT IMPLEMENTATIONS REJECTED / DIRECTION OPEN.** This branch is not
production. The candidate is disabled unless
`-Dboot_optim.elementsCullDirectionCache=true` is present. The eager implementation
was physically disproven; two safer revisions avoided its large regression but did
not yet produce a startup win.

## Why this is a different premise

The rejected `BlockModel.getMaterial` cache added per-face string/map bookkeeping
and regressed the exact pack. This experiment does not cache materials or model
results. It targets the remaining `ElementsModel.addQuads` face loop: stock
repeatedly maps each culled face's direction through the same immutable
`ModelState` rotation. The first candidate computed all six direction mappings
once per `ElementsModel` bake invocation, then kept stock material lookup,
`FaceBakery`, culling decisions and `IModelBuilder` callbacks.

The candidate also walks `faces.entrySet()` so the face value is obtained with
the same map iteration rather than a key-set iteration followed by a second
`Map.get`. It does not move work across threads, retain resources across a
reload, or alter atlas/model objects.

The revised candidate resolves a direction lazily on first use and adds an
identity-rotation fast path. These changes specifically address the physical
regression caused by doing six matrix rotations for models that use only one or
two cull directions. A second revision leaves the stock `ElementsModel` face
loop intact and redirects only the `Transformation.rotateTransform` call for
the sixteen immutable vanilla rotations; this is the surgical alternative
tested below.

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

The same branch was rerun after moving the cache-access interface out of the
Mixin package (commit `f412e7a`), which removed the earlier
`IllegalClassLoadError`. The exact-pack workflow completed all six jobs with
zero BootOptim Mixin errors and unchanged atlas dimensions. Its medians were:

| variant | main-menu ms | reload→FancyMenu ms | panorama ms |
| --- | ---: | ---: | ---: |
| candidate | 90,204 | 41,453 | 4,563.6 |
| control | 90,423 | 42,743 | 4,445.3 |

The `-219 ms` total delta and `-1,290 ms` reload-to-FancyMenu delta are too
small for the hosted variance, and the candidate did not emit an
`elements_cull_direction_cache` marker in the exact-pack logs. This is a
successful compatibility/build gate, not evidence that the direction loop is
on the hosted critical path.

## Reopening

The current implementations are not production candidates, but the broader
direction is not a closed alley. Reopen only with a materially different
source-level premise and attribution, for example:

- a diagnostic count of distinct cull directions per `ElementsModel` and the
  proportion of identity/non-identity `ModelState` rotations;
- a no-allocation direct lookup table keyed by a safely bounded transformation
  identity, with explicit reload/lifetime rules; or
- a profiler proving that the repeated work is inside `BlockModel.bakeFace` or
  `IModelBuilder`, rather than the direction mapping itself.

Do not return to generic per-face material caches. Do not close the broader path
until a verified redirect invocation has either produced a coherent negative
result or a profiler/counting pass has shown that direction mapping is not on
the critical path. If the sixteen-object identity table has no hits, the next
alternative is a bounded value-keyed/rotation-signature cache with explicit
reload lifetime, not another eager per-model array.

## Physical laptop evidence (2026-09-07)

All three candidates used the same isolated Prism instance, the same Java 25.0.4
process-start-to-main-menu boundary, and exactly one BootOptim JAR. The control and
candidate were the same experimental build with the system property set to
`false`/`true`; the laptop was restored to the production JAR after the runs.

| run | build/variant | option | total to main menu |
| --- | --- | ---: | ---: |
| `elements-cull-control-20260907` | first experimental build | false | 316,682 ms |
| `elements-cull-candidate-20260907` | eager six-direction map | true | 477,411 ms |
| `elements-cull-baseline-after-20260907` | production JAR | n/a | 341,420 ms |
| `elements-cull-lazy-candidate-20260907` | lazy per-used-direction map | true | 349,752 ms |
| `elements-cull-identity-lazy-candidate-20260907` | lazy map + identity fast path | true | 370,508 ms |

The production baseline after the first pair returned to the normal 329--344 s
band, confirming that the 316 s control is a low but plausible sample rather than
the correct baseline to use alone. The eager candidate was still 135,991 ms slower
than the adjacent production baseline, so its regression is real and not explained
by ordinary laptop variance. The lazy revision removed almost all of that damage
(349,752 ms versus 341,420 ms), while the identity fast path was slower in this
single run (370,508 ms). Neither revision has evidence for promotion.

The result closes the **eager implementation**, not the whole optimization idea.
The physical evidence instead says that any useful version must avoid unconditional
per-model allocations/matrix work and must first measure how often the mapping is
actually repeated on this pack.

## Stock-loop redirect attempt (2026-09-07)

The first redirect build targeted the NeoForge extension interface. Bytecode
inspection of the actual NeoForge `ElementsModel.addQuads` showed an
`invokevirtual` whose owner is `com.mojang.math.Transformation`, even though the
method is supplied by `ITransformationExtension`. That first laptop run was
invalid because its redirect marker never appeared.

The target was corrected to the owner and descriptor present in the compiled
class, and the candidate reached the main menu without a Mixin failure:

| variant | total to main menu |
| --- | ---: |
| corrected stock-loop redirect | 396,849 ms |

This is slower than the nearby production baseline (`341,420 ms`) and is not a
promotion result. The run also exposed a diagnostics gap: the startup report
contained the older cache marker but no redirect-hit marker, so this result
cannot yet prove that the bounded sixteen-entry identity table was used. The
next iteration adds separate invocation and table-hit markers and clears the
report between diagnostic runs. Until those markers are observed, treat this
candidate as inconclusive rather than as evidence that the entire direction is
unoptimizable.
