# Post-promotion ModelManager residuals

Date: 2026-08-31

Source experiment: draft PR #57 `Profile post-promotion ModelManager residuals`

This record exists to prevent future investigations from repeating the same hypotheses after the production promotion of FancyMenu preload overlap, hardened Decocraft quarter-turn reuse, and indexed blockstate variant matching.

## Exact-pack post-promotion state

Warm reference-pack run with the PR #57 diagnostic build:

- startup to main menu: `72,397 ms`
- resource reload all preparations: `16,714.306 ms`
- resource reload all done: `26,052.329 ms`
- `ModelManager` preparation barrier: `16,698.258 ms`
- `ModelManager` global wait after its own preparation: `16.047 ms`
- final anonymous listener post-turn: `2,576.803 ms`
- FancyMenu panorama preload: `2,565.753 ms`, `120` suppliers prelaunched, `0` failures

`ModelManager` therefore remains the actual preparation gate after the production promotions, but its gate time is materially lower than the earlier PR #47 reference (`24,268.140 ms`). The final FancyMenu-aligned apply tail is also far smaller than the earlier `12,486.835 ms` reference.

ModelManager subphases in the PR #57 run:

| Phase | Wall time | Notes |
| --- | ---: | --- |
| block states | `803.424 ms` | async future; 11,435 entries |
| block models | `2,629.861 ms` | async future; 44,102 entries |
| atlas stitch | `1,631.079 ms` | 9 futures |
| ModelBakery construction | `4,852.089 ms` | synchronous |
| `bakeModels` | `8,129.116 ms` | synchronous |
| `loadModels` | `9,118.205 ms` | enclosing stage; contains bake work |

These phases overlap/nest and must not be added together.

## Residual uncached bake distribution

PR #57 measured exclusive uncached bake time after the production Decocraft optimization:

- total calls: `402,882`
- top-level calls: `327,029`
- nested calls: `75,853`
- exclusive time: `7,601.877 ms`
- top-level exclusive: `2,959.263 ms`
- nested exclusive: `4,642.614 ms`
- abandoned/corrupt profiler frames: `0 / 0`

Largest categories:

| Category | Calls | Exclusive time | Share |
| --- | ---: | ---: | ---: |
| `BlockModel/generated_item` | 14,865 | `2,619.177 ms` | 34.45% |
| `BlockModel/elements` | 59,790 | `1,980.676 ms` | 26.06% |
| Decocraft `BBGeometry` | 14,108 | `1,619.858 ms` | 21.31% |
| `MultiPart` | 146,765 | `803.724 ms` | 10.57% |

Do not return to top-level identity dedup from PR #36 based on these call counts. That mechanism was already shown to remove many cheap wrapper calls without meaningful end-to-end improvement.

## Generated-item span topology cache

**Status: REJECTED as a significance play**

Hypothesis: cache the topology returned by `ItemModelGenerator#getSpans` by exact `SpriteContents` identity for the duration of a reload, while still constructing fresh mutable `BlockElement` objects.

Exact-pack measurement:

- generated model calls: `14,869`
- generated model time: `986.860 ms`
- generated elements: `1,019,203`
- `processFrames` calls: `23,467`
- `processFrames` time: `871.096 ms`
- `getSpans` calls: `23,467`
- total `getSpans` time: `673.732 ms`
- unique sprite identities: `7,702`
- repeated span calls: `15,765`
- first-call span time: `535.231 ms`
- repeated span time: **`138.501 ms`**
- NeoForge seam-fix calls: `23,457`
- seam-fix time: `60.620 ms`

The exact-pack removable ceiling for the proposed cache is therefore only about `138.5 ms` before cache lookup/allocation overhead. Despite a large repeat count, the repeated work is cheap.

**Do not implement:** a `SpriteContents -> spans` cache whose significance argument is based on the `15,765` repeated calls. The measured wall-time ceiling is too small for BootOptim's goals.

**Reopen only if:** a materially different pack/version produces a much larger measured repeated-span wall time, not merely a higher repeat percentage.

## ElementsModel / FaceBakery split

PR #57 found a stronger generic residual:

- `ElementsModel.addQuads` calls: `75,118`
- total measured `addQuads`: `3,110.528 ms`
- `BlockModel.bakeFace` calls inside the measured path: `2,203,625`
- face-bake time: `1,472.434 ms`
- non-face residual: **`1,638.094 ms`**
- face share: `47.34%`
- generated-item faces: `1,042,652`
- generated-item face time: `432.949 ms`

Source inspection of NeoForge/Minecraft 1.21.1 shows that the non-face loop repeatedly performs:

1. `context.getMaterial(face.texture())`
2. `spriteGetter.apply(material)`
3. face-map lookup/iteration
4. `addUnculledFace` or cull-direction rotation plus `addCulledFace`

`BlockGeometryBakingContext#getMaterial` delegates to `BlockModel#getMaterial`. The exact 1.21.1 `BlockModel#getMaterial` implementation allocates a fresh `ArrayList` for reference-chain tracking and walks model-parent texture maps on every resolution. This makes repeated per-face material resolution a new, structurally different hypothesis from the rejected top-level model caches.

## Next experiment

PR #57 is being extended with a deliberately short-scope material-resolution memoization experiment:

- cache lifetime: one `ElementsModel.addQuads` or one `BlockModel.bakeVanilla` invocation only
- first resolution remains stock
- no sharing across models, top-level bake calls, or resource reloads
- no persisted cache and no resource-pack invalidation problem
- log hit/miss counts and compare exact-pack `bakeModels`/gate/E2E against the immediately preceding PR #57 run

This experiment is not production-approved. It must demonstrate a meaningful exact-pack wall-time improvement and preserve runtime behavior before promotion is considered.

## Later outcome — 2026-09-01

The material-resolution line above was completed inside PR #57 and is now **REJECTED** as a startup optimization. Several increasingly narrow implementations showed that the redundancy was real but that hit rate was not a useful proxy for elapsed-time benefit:

- short-scope cache: `91.47%` hits, but exact-pack startup/gate/`bakeModels`/`ElementsModel` all failed to improve;
- context-scoped cache: `95.98%` hits, yet `bakeModels` was `8,710.831 ms` versus the `8,129.116 ms` no-cache baseline and `ElementsModel` was also slower;
- selective complex-only cache: `90.40%` of calls bypassed caching as direct-local lookups, while the remaining complex path still achieved `84.79%` hits (`179,349 / 211,520`), but direct affected metrics again regressed (`bakeModels=9,757.533 ms`, `ElementsModel=3,757.312 ms`).

Conclusion: do not reopen `BlockModel#getMaterial` memoization merely because a future profiler again shows a high reuse percentage. A new attempt requires a materially different source-level mechanism and a positive measured ceiling.

The generated-item part of this residual was subsequently superseded architecturally. PR #62 replaced the vanilla generated-item intermediary `BlockElement` construction with a direct compact-topology-to-stock-`FaceBakery` path; exact-pack verification reached `14,865 / 14,865` matches with zero mismatches/fallbacks after localized Trimmable Tools compatibility. That implementation was promoted to production in PR #64, so the old `getSpans`/generated-element path is no longer a useful optimization target while the direct baker is active.

A later attempt to flatten repeated ordinary `ElementsModel` traversal was tested in PR #66 and is also **REJECTED**. The safe verifier produced `5,448 / 5,448` matches, zero mismatches and zero fallbacks, but the candidate was already slower than stock: `189.190 ms` versus `179.709 ms`. An intentionally unsafe optimistic ceiling test then removed per-call structural validation; it again verified `5,448 / 5,448` with zero mismatches/fallbacks, yet remained slower at `102.394 ms` candidate versus `91.267 ms` stock. PR #66 was closed without merge.

Therefore ordinary `ElementsModel` element/face-map traversal does not have a positive ceiling under that flattening premise. Do not request an exact-pack run or rebuild the same live-plan approach unless the premise changes materially.
