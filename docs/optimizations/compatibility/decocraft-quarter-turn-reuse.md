# Decocraft quarter-turn geometry reuse

## Scope

Optional compatibility optimization for Decocraft's Blockbench/BBModel bake path. The measured target is Decocraft `3.0.11` on Minecraft 1.21.1.

Kill switch: `-Dboot_optim.decocraftQuarterTurnReuse=false`

BootOptim does not link against Decocraft classes. Eligibility is checked by exact runtime class name and runtime model-shape invariants.

## Bottleneck

Profiling showed Decocraft dominating custom model geometry bake CPU. Its BBModel path baked each measured structural model exactly four times, corresponding to horizontal orientation variants. Decocraft rebuilt the Blockbench faces/quads for every orientation even though geometry/material data was shared and the final difference was a `ModelState` rotation.

Measured distribution before the optimization:

- BBGeometry bakes: `14,108`
- structural groups: `3,527`
- calls per structural group: exactly `4`
- BBGeometry time in the profiling run: about `3.50 s`

## Mechanism

For the exact Decocraft BBGeometry class, BootOptim keeps a cache only for the duration of `ModelBakery.bakeModels()`.

For each geometry + exact baking-context identity + exact `ItemOverrides` identity + UV-lock state:

1. The first orientation executes Decocraft's original bake and becomes the authoritative base.
2. BootOptim validates the resulting exact `BlockbenchModel` shape.
3. A later orientation is reusable only when the relative `ModelState` matrix is an exact affine Y-axis rotation of 0/90/180/270 degrees.
4. For 90/180/270, BootOptim clones each baked quad and rotates positions/normals around block center instead of asking Decocraft to rebuild the full Blockbench geometry.
5. Any failed invariant executes Decocraft's original bake.

## Production hardening

The production implementation is stricter than the original experiment:

- exact BBGeometry and exact BlockbenchModel class names are required;
- baking context and `ItemOverrides` must be the same object identity as the base;
- UV-lock state must match;
- only standard BLOCK vertex layout is accepted;
- side-specific quad lists must be empty, matching the measured Decocraft model shape;
- relative matrix must be a pure unit-scale horizontal quarter turn;
- NeoForge's per-quad `hasAmbientOcclusion` flag is preserved;
- packed-normal high-byte/padding is preserved;
- the derived model uses NeoForge `BakedModelWrapper` so normal vanilla/NeoForge model behavior delegates to the authoritative Decocraft model;
- the render-type/ModelData-aware `getQuads` path is replaced only if Decocraft still inherits NeoForge's default implementation; a future custom override makes the model ineligible;
- cache state is cleared immediately after `ModelBakery.bakeModels()`.

These checks intentionally prefer losing the optimization after a Decocraft/API change over guessing that a new model implementation is equivalent.

## Safety invariants

- Non-Decocraft geometry => original bake directly.
- Disabled property => original bake directly.
- Unknown/changed baked model class => original behavior.
- Different context/overrides/UV-lock => separate authoritative base or original behavior.
- Unexpected side quads/vertex layout/NeoForge extension => original behavior.
- Non-quarter-turn transformation => original behavior.
- No persistent model cache survives resource bake completion.

## Resource trade-offs

Derived orientations allocate cloned quad vertex arrays and wrapper models instead of Decocraft allocating/reconstructing full Blockbench geometry. This shifts CPU toward simple array/matrix work. The cache exists only during model bake.

The optimization reduces CPU work more clearly than critical-path wall time because model baking overlaps other resource-reload workers.

## Measured evidence

Original exact-pack experiment:

- Decocraft calls: `14,108`
- authoritative base bakes: `3,527`
- derived quarter-turn bakes: `10,581`
- fallbacks: `0`
- rejected models: `0`
- derived quads: about `2.89 million`
- derivation CPU time: about `425 ms`
- repeated Decocraft geometry CPU replaced: about `2.7 s`

The end-to-end run was `76.033 s`; comparison of resource-reload timing suggested only roughly ~1 second landed on the startup critical path because much of Decocraft's saved CPU overlapped other workers.

The production-hardened version must be revalidated in the exact pack because its additional safety guards are intentionally capable of rejecting cases that the experiment accepted.

## Expected results

A pack with many Decocraft 3.0.11 BBModels should use materially less model-bake CPU. The time-to-main-menu improvement is expected to be smaller than the raw CPU reduction when other resource tasks are simultaneously on the critical path. Updated Decocraft builds may automatically fall back until their model path is re-profiled and revalidated.
