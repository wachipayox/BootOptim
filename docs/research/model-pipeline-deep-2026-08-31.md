# Deep ModelManager follow-up — 2026-08-31

This entry continues the history in `model-pipeline.md`. It exists specifically to distinguish the new source-level/algorithmic findings from the already-rejected top-level identity-dedup and simple parallel-bake experiments.

## Exact-pack warm run

Diagnostic: PR #47 deep ModelManager build.

Startup report:

- BootOptim mod entrypoint: `28,716 ms`
- main menu: `81,744 ms`

Do not interpret the lower end-to-end number versus nearby runs as a profiler optimization win. Resource reload and the pack are variable; this build is diagnostic.

Critical-path profiler:

- listeners expected/observed: `72 / 72`
- all preparations: `18,150.947 ms`
- ModelManager preparation gate: `18,135.714 ms`
- all listeners done: `33,356.338 ms`
- final anonymous listener post-turn: `8,718.000 ms`, still aligned with stock FancyMenu resource preloading in this diagnostic build

ModelManager remains the actual preparation gate. FancyMenu remains a separate late serial tail.

## Recursive baked-cache result: already extremely effective

`ModelBakerImpl` real recursive cache accounting:

- cache lookups: `3,166,766`
- hits: `3,090,912`
- misses: `75,854`
- hit rate: **`97.60%`**
- uncached calls: `402,882`
- top-level uncached: `327,029`
- nested uncached: `75,853`
- nested uncached minus cache misses: `0`
- exclusive uncached work: `7,862.267 ms`
- top-level exclusive: `2,418.602 ms`
- nested exclusive: `5,443.666 ms`
- abandoned/corrupt profiler frames: `0 / 0`

### Conclusion

**Generic recursive bake caching is not the next architecture.** Vanilla's 1.21.1 `BakedCacheKey` cache already removes 97.6% of recursive lookups in the exact pack. A new cache must target a distinct expensive operation proved to survive this cache; another broad model-result cache would mostly duplicate existing behavior.

Reopen only if a later implementation/version materially changes the hit rate or profiling identifies a repeated expensive operation below/alongside this cache with different identity semantics.

## Exclusive uncached bake cost

Top categories by exclusive time:

| Rank | Category | Calls | Exclusive time | Share |
| --- | --- | ---: | ---: | ---: |
| 1 | Decocraft `BBGeometry` | 14,108 | `3,239.379 ms` | `41.20%` |
| 2 | generated item `BlockModel` | 14,865 | `1,775.330 ms` | `22.58%` |
| 3 | element-based `BlockModel` | 59,790 | `1,518.666 ms` | `19.32%` |
| 4 | `MultiPart` top level | 146,765 | `790.861 ms` | `10.06%` |
| 5 | `MultiVariant` top level | 166,599 | `273.702 ms` | `3.48%` |

This independently confirms why PR #36 disappointed: the enormous number of top-level MultiVariant/MultiPart entries is not where most exclusive bake time is spent.

### Decocraft decision update

The exact deep run makes Decocraft the single largest measured uncached bake category: `3.239 s`, `41.2%` of exclusive uncached bake work. The project's current explicit decision is therefore to **retain/promote the hardened Decocraft quarter-turn reuse implementation**, despite PR #37's historical reject decision under the earlier significance threshold. PR #37 remains useful historical evidence but no longer represents current product intent.

## Blockstate structure: new algorithmic target

`BlockStateModelLoader` structural instrumentation:

- ModelBakery constructor: `5,433.181 ms`
- `loadAllBlockStates`: `4,405.969 ms`
- variant predicates: `110,053`
- variant × state predicate tests: **`10,856,307`**
- matching states: `166,599`
- max states tested by one variant: `1,150`
- multipart `Selector.getPredicate` construction: `11,731` calls / only `35.824 ms`

### Source-level cause

Minecraft 1.21.1 processes every variant with the equivalent of:

```java
possibleStates.stream()
    .filter(predicate(stateDefinition, variantKey))
    .forEach(...);
```

The parsed predicate loops its property constraints and calls `BlockState.getValue(property)` for every candidate state. Complexity is therefore approximately:

`O(variants × possible states × constrained properties)`

The exact pack performs about **10.86 million state tests to produce only 166,599 matches**.

### Rejected sub-idea: cache multipart predicate construction

`Selector.getPredicate` construction costs only ~35.8 ms in this exact run. Caching/reusing those predicate objects is not a meaningful startup target here. Do not pursue it as a standalone optimization.

### Active architectural hypothesis: indexed variant matching

A materially different optimization is to index a `StateDefinition` once by `property=value` over its canonical `getPossibleStates()` order, then resolve each variant by intersecting those precomputed state sets/bitsets.

Required invariants:

1. Preserve exact `getPossibleStates()` order for matching states.
2. Preserve parsing errors for unknown properties/values.
3. Preserve overlap detection and the same effective variant application order.
4. Preserve multipart/default/missing-model behavior.
5. Do not parallelize callbacks or custom model-loader semantics merely as a side effect.
6. Fail back to stock matching for any case whose equivalence cannot be established.

Potential work reduction in this run is from 10.86 million predicate-state evaluations toward one index build per state definition plus bitset intersections and the 166,599 actual match visits. This is an algorithmic target, not another model cache.

## Current next steps

1. Prototype indexed/bitset variant matching in an isolated experiment with the PR #47 measurements retained for same-run attribution.
2. Validate exact output equivalence/invariants before using wall-time as evidence.
3. Separately exact-pack smoke the production FancyMenu + hardened Decocraft promotion.
4. Continue Mixin experiments independently: PR #46 ClassInfo negative-cache probe and PR #48 ModLauncher writer-tail probe must be run in separate launches.
