# Decocraft quarter-turn geometry reuse

## Current project decision

**RETAIN / PROMOTE THE HARDENED VERSION.**

Historical PR #37 was initially closed as rejected under the project's earlier significance threshold because replacing about 2.7 s of repeated Decocraft geometry CPU translated to only around ~1 s of critical-path improvement. That historical decision has been explicitly overridden: the project wants to keep the hardened production version because the mechanism is real, bounded, fail-open and removes substantial redundant CPU in the reference pack.

Do not read the old #37 state in isolation and conclude this optimization should be removed.

## Scope

Optional compatibility optimization for Decocraft 3.0.11's Blockbench/BBModel geometry path.

Kill switch: `-Dboot_optim.decocraftQuarterTurnReuse=false`

BootOptim does not link to Decocraft classes. Eligibility is determined by exact runtime class names and model-shape invariants.

## Bottleneck and measured experiment

The original profiler found horizontal orientation variants rebuilding the same Blockbench geometry repeatedly:

- Decocraft BBGeometry calls: 14,108
- structural groups / authoritative bakes: 3,527
- derived horizontal variants: 10,581
- derived quads: about 2.89 million
- derivation CPU: about 425 ms
- repeated geometry CPU replaced: about 2.7 s
- estimated critical-path gain in that run: around ~1 s

The important distinction is CPU versus wall clock: much of the removed CPU overlapped other reload workers. The optimization nevertheless removes genuinely redundant work and is intentionally retained.

## Hardened mechanism

The cache exists only for one `ModelBakery.bakeModels()` call. For the exact Decocraft BBGeometry class, the first compatible geometry/context/override/UV-lock combination executes Decocraft's original bake and becomes authoritative. Later calls can be derived only if the relative transform is an exact pure horizontal 0/90/180/270-degree rotation.

For derived quarter turns BootOptim clones the baked quad data, rotates positions and normals around block center, preserves relevant NeoForge quad metadata, and wraps the authoritative model with `BakedModelWrapper`. Unknown shapes or extensions fall back to Decocraft's original bake.

## Hardened safety invariants

- exact BBGeometry class name required;
- exact Decocraft BlockbenchModel class required for reusable bases;
- same baking-context object identity;
- same `ItemOverrides` object identity;
- matching UV-lock state;
- standard expected vertex stride;
- no unexpected side-specific quad lists;
- relative transform must classify as a pure unit-scale Y quarter turn;
- packed-normal padding/high byte and ambient-occlusion flag preserved;
- model must still use NeoForge's default ModelData/render-type-aware quad path;
- any failed guard uses the original Decocraft bake;
- cache is cleared when `bakeModels()` finishes.

## Exact-pack promotion validation — 2026-08-31

PR #50's hardened implementation was smoke-tested in the real reference pack after Build + Startup CI were green. The user manually verified asymmetric Decocraft models in multiple horizontal orientations and reported correct rendering/behavior.

Observed marker:

`calls=14108 base_bakes=3527 derived_bakes=10581 exact_reuses=0 fallbacks=0 rejected_models=0 derived_quads=2892138 derived_ms=410.534 fallback_ms=0.000 rotation_90=3527 rotation_180=3527 rotation_270=3527`

This exactly matches the expected 3,527 authoritative bases plus 10,581 derived variants, with zero guard rejection/fallback. Future Decocraft/version changes must continue to fail open rather than weaken these guards blindly.
