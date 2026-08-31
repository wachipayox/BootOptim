# Production startup optimizations

This file lists startup optimizations that have crossed the project's evidence bar and are intended to live in `agent/integration-current`.

## FancyMenu panorama preload overlap

Promoted via PR #54 after exact-pack validation. It starts FancyMenu panorama/resource suppliers concurrently while preserving result order, failure propagation and GPU-facing semantics. Exact-pack smoke validation showed 20 panoramas / 120 suppliers with zero failures and about 2.39 s preload time versus the much larger serialized baseline seen during profiling.

## Decocraft quarter-turn geometry reuse

Promoted via PR #54 after the hardened implementation was exact-pack smoke tested. The validation produced 3,527 authoritative bakes and 10,581 derived quarter-turn bakes with zero fallbacks/rejected models. The implementation preserves the authoritative base bake and derives only safe horizontal quarter-turn variants.

## Indexed blockstate variant matching

Validated in PR #55 and promoted through the clean production path after exact-pack verification.

It preserves stock blockstate predicate parsing and validation but replaces the subsequent O(variants × possible states) candidate scan with reload-scoped property/value BitSet indexes. The full exact reference pack verified all 110,053 indexed variants against stock with zero mismatches and zero fallbacks, eliminating 10,689,708 candidate visits in the measured workload.

See `blockstate-indexed-matching.md` for the complete evidence and compatibility invariants.

## Direct generated-item quad baking

Validated in experimental PR #62 after post-#56 profiling identified `BlockModel/generated_item` as the largest remaining generic model-bake residual.

Minecraft/NeoForge 1.21.1 normally expands every `builtin/generated` sprite into a temporary graph of `BlockElement`, face maps, `BlockElementFace`, `BlockFaceUV`, float arrays and vectors before ultimately feeding those values into `FaceBakery`. The production candidate keeps stock/NeoForge `FaceBakery` authoritative but replaces the temporary graph with primitive ordered edge topology and emits the same final quads directly.

Semantic validation on the exact reference pack compared the stock and direct result exhaustively for all 14,865 eligible generated-item bakes: 14,865 matches, zero mismatches and zero fallbacks. The comparison covered model metadata plus quad count/order, complete vertex arrays, tint, direction, shade, ambient occlusion, sprite identity, vanilla and NeoForge quad paths, transforms, overrides and render-pass behavior. Trimmable Tools 2.0.5 required an explicit compatibility adjustment because it mixes into `ItemModelGenerator#createSideElements`; that adjustment was included before the final 14,865/14,865 verification.

On the same verified workload, the stock generated-item route measured about 2.801 s while the candidate-only route measured about 0.927 s when run cold in performance mode, removing about 1.875 s / 66.9% of that work. The same-build end-to-end A/B also moved in the expected direction (67.503 s ON versus 77.637 s OFF), but the OFF run was globally slower in unrelated work as well, so the full 10.134 s wall-clock difference must not be attributed to this optimization. The internal generated-item measurement is the reliable attribution.

Production keeps a fail-open runtime path and an explicit kill switch:

`-Dboot_optim.generatedItemDirectBake=false`

Reopening criteria: if Minecraft/NeoForge changes the generated-item pipeline, or a newly added mod also transforms `ItemModelGenerator` semantics, re-run the verifier from historical PR #62 before extending the fast path.
