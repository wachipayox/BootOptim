# Production startup optimizations

This file lists startup optimizations that have crossed the project's evidence bar and are intended to live in `agent/integration-current`.

## FancyMenu panorama preload overlap

Promoted via PR #54 after exact-pack validation. It starts FancyMenu panorama/resource suppliers concurrently while preserving result order, failure propagation and GPU-facing semantics. Exact-pack smoke validation showed 20 panoramas / 120 suppliers with zero failures and about 2.39 s preload time versus the much larger serialized baseline seen during profiling.

## Decocraft quarter-turn geometry reuse

Promoted via PR #54 after the hardened implementation was exact-pack smoke tested. The validation produced 3,527 authoritative bakes and 10,581 derived quarter-turn bakes with zero fallbacks/rejected models. The implementation preserves the authoritative base bake and derives only safe horizontal quarter-turn variants.

## Indexed blockstate variant matching

Validated in PR #55 and being promoted from the clean production branch `agent/promote-blockstate-indexed-matching`.

It preserves stock blockstate predicate parsing and validation but replaces the subsequent O(variants × possible states) candidate scan with reload-scoped property/value BitSet indexes. The full exact reference pack verified all 110,053 indexed variants against stock with zero mismatches and zero fallbacks, eliminating 10,689,708 candidate visits in the measured workload.

See `blockstate-indexed-matching.md` for the complete evidence and compatibility invariants.
