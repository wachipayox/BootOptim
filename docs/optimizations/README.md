# BootOptim production optimization catalog

This directory is the source of truth for optimizations the project intends to ship or retain. Historical experiments and rejected ideas belong under `docs/research/` instead.

Before adding a new entry, verify the implementation is actually present on `agent/integration-current` or in the promotion PR that adds the documentation. A successful experiment in another branch is not production by itself.

## Current catalog

| Optimization | Scope | Default | Main mechanism |
| --- | --- | --- | --- |
| Persistent mod scan cache | Global / FML discovery | Enabled | Reuse versioned mod metadata scan results across warm launches |
| Asynchronous scan-cache writes | Global / cache persistence | Enabled with scan cache | Keep cold-cache persistence off FML scan workers |
| [FancyMenu panorama preload overlap](compatibility/fancymenu-panorama-preload.md) | FancyMenu 3.9.x | Enabled when compatible target exists | Start existing async panorama PNG suppliers before FancyMenu serially waits for them |
| [Decocraft quarter-turn geometry reuse](compatibility/decocraft-quarter-turn-reuse.md) | Decocraft 3.0.11 guarded BBModel path | Enabled when exact invariants match | Bake one authoritative horizontal orientation and derive safe quarter-turn variants |
| [MCEF first-consumer defer](compatibility/mcef-first-consumer-defer.md) | MCEF 2.1.6-1.21.1 + guarded consumers | Enabled on exact MCEF version | Suppress automatic pre-title CEF init and invoke the real initializer at the first actual browser/video consumer |

The old vanilla top-level blockstate identity dedup experiment is intentionally **not** listed as production. PR #36 showed a 64.57% call-count reduction but only about 0.413 s / 4.7% reduction in `bakeModels` and no end-to-end improvement. See `docs/research/model-pipeline.md`.

## Promotion rules

Production optimizations must preserve/fail open to original behavior, document version/shape assumptions, expose a kill switch when appropriate, and survive build/startup CI plus exact-pack validation when runtime behavior changes. A production catalog entry records current product intent; the research ledger keeps the complete historical evidence, including earlier reject decisions that may later be overridden deliberately.
