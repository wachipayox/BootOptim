# Model material-resolution cache experiments

Status: **REJECTED as a performance path** (2026-08-31 exact-pack measurements).

This document exists to prevent future agents from rediscovering the same high-hit-rate but low-value cache idea.

## Why this was investigated

Post-promotion PR #57 profiling showed that NeoForge `ElementsModel` still spent substantial time outside `BlockModel.bakeFace`. One plausible source was repeated `IGeometryBakingContext#getMaterial(name)` / `BlockModel#getMaterial(name)` resolution for millions of faces.

The reference pack performs about **2,203,625 material lookups** during the measured ElementsModel workload. The repetition rate looked extremely high, so several progressively cheaper memoization designs were tested.

## Iteration A — short scope per `ElementsModel.addQuads`

Exact-pack (`pr57_2`):

- material calls: `2,203,625`
- hits: `2,015,603`
- misses: `188,022`
- apparent hit rate: `91.47%`
- `bakeModels`: `9,405.159 ms`
- `ElementsModel`: `3,543.393 ms`
- ModelManager preparation gate: `18,424.439 ms`
- startup: `76,545 ms`

Despite the high hit rate, direct affected timings regressed versus the no-material-cache PR57 baseline.

## Iteration B — cache attached to stock `BlockGeometryBakingContext`

Exact-pack (`pr57_3`):

- material calls: `2,203,625`
- hits: `2,114,962`
- misses: `88,663`
- apparent hit rate: `95.98%`
- `bakeModels`: `8,710.831 ms`
- `ElementsModel`: `3,160.739 ms`
- ModelManager preparation gate: `16,628.107 ms`
- startup: `73,453 ms`

This removed most of iteration A's own overhead, but still did not beat the no-material-cache baseline clearly enough to justify promotion.

## Iteration C — selective complex-only cache

The final variant bypassed the cache for a direct material stored locally in `BlockModel.textureMap` and memoized only references, parent-chain lookups, missing paths, and other non-trivial resolutions.

Exact-pack (`pr57_4`):

- material calls: `2,203,625`
- direct-local fast-path calls: `1,992,105` (`90.40%`)
- complex calls: `211,520`
- complex hits: `179,349`
- complex misses: `32,171`
- complex hit rate: `84.79%`
- maximum cached entries/context: `11`
- `bakeModels`: `9,757.533 ms`
- `ElementsModel`: `3,757.312 ms`
- face time: `1,746.173 ms`
- non-face time: `2,011.139 ms`
- ModelManager preparation gate: `18,730.482 ms`
- startup: `81,996 ms`

This run was globally slower even before ModelManager, so its end-to-end number alone is not causal evidence against the cache. However, after three designs there is still no positive exact-pack signal in the directly affected metrics. The redundancy is real, but the avoided `BlockModel#getMaterial` work is too cheap to make ordinary memoization worthwhile.

## Durable conclusion

**Do not reopen material memoization because the hit rate looks high.** A 90-96% cache hit rate here does not mean 90-96% of material-resolution CPU is removable. Most calls are already trivial direct map lookups, and even the selective complex-only design failed to produce a positive measured result.

Reopen this area only if a materially different representation or algorithm eliminates the lookup entirely (for example, pre-resolved immutable model data), or if a future Minecraft/NeoForge version changes the cost profile and fresh profiling proves that material resolution itself is expensive.

Small safe wins remain welcome in BootOptim, but they must still be **positive**. High indirect counters are not a substitute for direct timing evidence.

## Next direction

PR #57 shows a larger remaining structural target in vanilla generated-item models: roughly `14,865` generated-item bakes, about `2.6-3.1 s` exclusive depending on run, around `1,019,203` intermediate `BlockElement` objects, and `~0.7-0.8 s` in sprite span scanning. The next research should investigate representation/pipeline redesign (pixel topology -> baked quads) rather than another per-call cache.
