# P1 next step: strict structural model preparation — 2026-09-07

Status: **investigation / no production change**.

## What the current source proves

NeoForge 1.21.1's `ModelManager.reload` already overlaps block-model/block-state loading with atlas scheduling, then joins the atlas futures and runs `ModelManager.loadModels` on the preparation executor. `ModelBakery.bakeModels` itself iterates `topLevelModels` and the `ModelBakerImpl` cache is already authoritative for repeated `(model,state)` bakes. Reopening a second generic executor, top-level identity cache, or material lookup map would repeat rejected work.

The exact `ElementsModel.addQuads` loop still does, for every face, a parent/texture-chain material resolution, sprite lookup, stock `BlockModel.bakeFace` and builder callback. The source-level material cache premise is real (a fresh reference-chain list is allocated by vanilla `BlockModel.getMaterial`), but PR #57/#152 already showed that 90–96% cache hits can regress both the affected phase and the critical path. The overhead is in the hot path, so hit rate is not a performance argument.

## Current P1 decision

The next serious candidate is the strict-domain immutable preparation plan described in PR #160, not another lookup cache. The first implementation must be a reload-local shadow verifier:

1. Keep stock `ModelBakery` discovery, parent resolution, additional-model registration and complete-map callbacks authoritative.
2. Classify only ordinary vanilla-element `BlockModel`s with no custom loader, generated/block-entity marker, non-identity root transform, unknown extended data or unresolved/cyclic parent.
3. Build a compact pre-sprite structural representation (ordered element/face scalar data and texture identifiers) and lower it with the current reload's material/sprite getter and stock/NeoForge `FaceBakery`.
4. Compare canonical quads/metadata against stock while always returning stock. Record structural-plan build time, stock/candidate lowering time, allocation/GC and the ModelManager preparation barrier separately.

This changes the premise from “add a map lookup” to “remove repeated object/parent-chain traversal and allocation while preserving the current generation's sprites, callbacks and baked-model publication.” It must first prove a direct critical-path ceiling; a persistence layer is not justified by cache hits alone.

## Gates and non-goals

- The first branch is diagnostic/default-off and must not alter the production integration branch.
- Hosted exact-pack A/B precedes a physical laptop run. A hosted microphase win without movement of the ModelManager barrier/time-to-menu is inconclusive.
- `FaceBakery` work remains current-generation and stock; the plan cannot claim the full enclosing `ModelBakery`/`loadModels` duration as removable.
- Persistent storage, executor changes, early atlas publication, lazy complete-map publication and JVM tuning are deferred until the reload-local representation proves a coherent critical-path win.
- Every candidate mismatch, callback/order difference, custom loader, malformed input or reconstruction failure fails open to stock and poisons only that plan key.

The physical laptop's next corrected diagnostic run is deliberately independent of this research note: its job is to measure phase boundaries and variance, not to validate a structural plan.
