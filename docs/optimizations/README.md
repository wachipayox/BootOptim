# BootOptim production optimization catalog

This directory is the source of truth for optimizations the project intends to ship or retain. Historical experiments and rejected ideas belong under `docs/research/` instead.

Before adding a new entry, verify the implementation is actually present on `agent/integration-current` or in the promotion PR that adds the documentation. A successful experiment in another branch is not production by itself.

## Current catalog

[Exact Decocraft model JSON batching](compatibility/decocraft-model-json-batch.md): default enabled on its strict guarded path. Actual CPU removal is demonstrated; no total startup/reload gain claimed. Clean packaging, startup and exact-pack gates passed in production PR #325.

| Optimization | Scope | Default | Main mechanism |
| --- | --- | --- | --- |
| [Sodium face-axis quad classification](compatibility/sodium-quad-classifier.md) | Exact BakedQuad class + Sodium (validated 0.8.12-beta.1+mc1.21.1, not version-pinned) | Enabled with runtime differential guard | Replace normal-axis extrema with equivalent sentinel-aware plane checks |
| Persistent mod scan cache | Global / FML discovery | Enabled | Reuse versioned mod metadata scan results across warm launches |
| Asynchronous scan-cache writes | Global / cache persistence | Enabled with scan cache | Keep cold-cache persistence off FML scan workers |
| [FancyMenu panorama preload overlap](compatibility/fancymenu-panorama-preload.md) | FancyMenu 3.9.x | Enabled when compatible target exists | Start existing async panorama PNG suppliers before FancyMenu serially waits for them |
| [Decocraft quarter-turn geometry reuse](compatibility/decocraft-quarter-turn-reuse.md) | Decocraft guarded BBModel path (validated 3.0.11, not version-pinned) | Enabled when class/shape invariants match | Bake one authoritative horizontal orientation and derive safe quarter-turn variants |
| [Indexed blockstate variant matching](../research/blockstate-indexed-matching.md) | Minecraft 1.21.1 blockstate variants | Enabled | Keep stock predicate validation but replace the O(variants × possible states) candidate scan with reload-scoped property/value BitSet indexes |
| [Direct generated-item quad baking](../research/production-optimizations.md#direct-generated-item-quad-baking) | Strict vanilla `builtin/generated` item path | Enabled when eligible | Feed stock/NeoForge `FaceBakery` directly from primitive sprite-edge topology instead of allocating the temporary `BlockElement` graph |
| [MCEF first-consumer defer](compatibility/mcef-first-consumer-defer.md) | MCEF (validated 2.1.6-1.21.1, not version-pinned) + guarded consumers | Enabled when MCEF lifecycle methods are present | Suppress automatic pre-title CEF init and invoke the real initializer at the first actual browser/video consumer |
| [CITResewn base item-model cache](compatibility/citresewn-base-item-model-cache.md) | CITResewn item-CIT loading | Enabled when the compatible target class exists | Reuse repeated base-model parses and skip repeated resource opens during one ModelBakery reload |
| [CITResewn Glowing Trim legacy-name warning suppression](compatibility/citresewn-glowing-trim-legacy-warning-suppression.md) | Exact historical `CITResewn` logger + `Glowing Trim Armors v5.0.zip` startup warning family | Enabled | Deny only the authorized legacy `nbt.display.Name` log events with an exact logger/level/message/path/pack matcher; fail open on drift |
| [Xaero World Map startup update-check skip](compatibility/xaero-startup-update-check.md) | Xaero World Map (validated 1.41.0, not version-pinned) | Enabled; `-Dboot_optim.xaeroSkipStartupUpdateCheck=false` restores stock | Omit only the blocking render-thread `checkModVersion()` network call in `WorldMap.loadLater()`; no time-to-menu claim |
| [Block shape builders `Shapes.or` replay](compatibility/block-shape-or-memo.md) | Vanilla `WallBlock` / `CrossCollisionBlock` / `PipeBlock.makeShapes` (not version-dependent) | Enabled with differential guard; `-Dboot_optim.blockShapeOrMemo=false` restores stock | Record the `Shapes.or` results per builder and float parameters, replay for the other blocks that repeat them (builder wall ≈ 1.0 s → ≈ 0.16 s hosted) |

The old vanilla top-level blockstate identity dedup experiment is intentionally **not** listed as production. PR #36 showed a 64.57% call-count reduction but only about 0.413 s / 4.7% reduction in `bakeModels` and no end-to-end improvement. See `docs/research/model-pipeline.md`.

A retention audit on 2026-09-03 verified that the six pre-MCEF production mechanisms above were still physically present on integration and unchanged on the MCEF promotion branch; see [`docs/research/production-retention-audit-2026-09-03.md`](../research/production-retention-audit-2026-09-03.md). The previous shorter table omitted indexed blockstate matching and direct generated-item baking from the index only; their runtime code had not been removed.

## Promotion rules

Production optimizations must preserve/fail open to original behavior, document validated versions and code-shape assumptions (never gate on an exact mod version, JAR size or hash; see "Mod compatibility policy" in `docs/agent-guide.md`), expose a kill switch when appropriate, and survive build/startup CI plus exact-pack validation when runtime behavior changes. A production catalog entry records current product intent; the research ledger keeps the complete historical evidence, including earlier reject decisions that may later be overridden deliberately.


Clean promotion: [ordered ZIP prefix lookup](compatibility/ordered-zip-prefix-index.md), guarded default-on, CPU reduction retained; no total-time win claimed.

Clean promotion: [strict resource-path segment scan](compatibility/strict-path-segment-scan.md), guarded default-on, exact measured helper; physical CPU removal retained, no TTMM claim.
