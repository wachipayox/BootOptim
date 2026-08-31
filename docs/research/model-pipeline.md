# Client model / ModelManager research history

This file records the ModelManager/model-bake work already performed in BootOptim so future investigations start from the accumulated evidence instead of repeating earlier experiments.

## Current state — 2026-08-31 exact reference pack

PR #47's barrier/turn profiler confirmed that `ModelManager` is the **actual preparation gate**, not merely a listener with a large inclusive duration.

Exact-pack warm run using the PR #47 diagnostic build:

- resource-reload listeners: `72 expected / 72 observed`
- all preparations complete: `24,282.021 ms`
- `ModelManager` reaches its preparation barrier at `24,268.140 ms`
- `ModelManager` global wait after its own preparation: only `13.881 ms`
- `ModelManager` apply/post-turn work: `231.500 ms`
- all listeners complete: `43,542.690 ms`

ModelManager preparation subphases from the same run:

| Phase | Wall time | Notes |
| --- | ---: | --- |
| block states | `1,117.431 ms` | async future; 11,435 entries |
| block models | `4,670.360 ms` | async future; 44,102 entries |
| atlas stitch | `2,737.710 ms` | 9 futures |
| ModelBakery construction | `5,930.927 ms` | synchronous after required inputs become available |
| `bakeModels` | `12,598.561 ms` | synchronous, dominant measured bake stage |
| `loadModels` | `13,569.437 ms` | enclosing synchronous stage; **contains** bake-related work and must not be added to `bakeModels` |

These timings overlap. They are not additive. The important new fact is that ModelManager itself is now proven to hold the global preparation barrier for almost the entire ~24.28 s preparation window.

The same reload also has a separate post-preparation bottleneck: the final anonymous Minecraft listener spends `12,486.835 ms` after receiving its apply turn, aligned in the log with FancyMenu's resource preloader. ModelManager and that tail are separate opportunities; improving one does not remove the other.

## PR #13 — initial ModelManager profiling

**Status: PROFILED / SUPERSEDED by more precise critical-path profiling**

PR: #13 `Profile client model reload phases`

This was the first dedicated split of the client model reload. It already instrumented:

- `ModelManager.reload`
- `loadBlockModels`
- `loadBlockStates`
- `AtlasSet.scheduleLoad`
- `ModelBakery` construction
- `ModelBakery.bakeModels`
- blockstate registration inside the ModelBakery constructor
- item-model/dependency loading
- parent resolution

Recovered exact-pack measurements from that research round:

- `ModelManager.reload`: `18.050 s`
- `ModelBakery.bakeModels`: `8.857 s`
- blockstate registration/processing inside ModelBakery: `3.894 s`
- block model JSON: `2.083 s`
- atlas preparation: `1.321 s`
- blockstates JSON: `0.752 s`
- parent resolution: `0.611 s` across `327,029` models
- item model/dependency registration: `0.127 s` across `12,283` items

Lesson: do not re-create these probes from scratch. In particular, parent resolution and item registration were already measured and were not multi-second targets in that run. A new constructor investigation must focus on work not explained by those old categories or prove that their cost distribution has materially changed.

## PR #14 — parallel eager top-level model baking

**Status: REJECTED**

PR: #14 `Experiment: parallel 1.21.1 model baking`

Hypothesis: backport the newer-Minecraft direction of parallel top-level model baking while preserving 1.21.1's eager lifecycle. The experiment parallelized the top-level `ModelBakery.bakeModels` loop and made shared bake output/cache maps thread-safe.

Same-runner A/B result:

- `model_bake`: `668 ms` serial -> `500 ms` parallel (`-25.2%`)
- `ModelManager` reload: `3,285 ms` -> `2,875 ms` (`-12.5%`)
- main menu: `15,406 ms` -> `15,554 ms` (`+1.0%`, regression)

Conclusion: simple eager top-level parallelism improved the isolated phase but did not improve startup and added concurrency/correctness risk.

**Do not repeat:** "parallelize the existing top-level `forEach`" as the whole optimization.

**Reopen only if:** a new design changes the dependency/scheduling architecture rather than merely parallelizing the same eager loop, or exact-pack critical-path evidence proves a materially different contention regime and the implementation addresses thread-safety of custom model loaders explicitly.

## PR #36 — exact-identity MultiVariant / MultiPart bake reuse

**Status: LIMITED / REJECTED as a standalone significance play**

PR: #36 `Experiment: deduplicate vanilla blockstate model baking`

Starting exact-pack profile:

- `ModelManager.reload`: about `18.05 s`
- `ModelBakery.bakeModels`: about `8.86 s`

The experiment reused the first baked result when multiple top-level entries referenced the exact same vanilla `MultiVariant` or `MultiPart` object identity.

Measured distribution:

- total top-level entries: `327,029`
- safe exact-vanilla entries: `313,364`
- unique safe bakes: `111,014`
- reused bakes: `202,350`
- apparent reuse: `64.57%`

The earlier distribution profiler also showed why the count was misleading in its timed sample:

- repeated-identity calls: `20,723 / 28,024`, but only `133.239 ms` (`23.60%` of measured call time)
- first/unique calls: `7,301 / 28,024`, consuming `431.342 ms` (`76.40%`)
- runtime-class totals in that sample included `BlockModel 196.571 ms / 1,336 calls`, `MultiVariant 165.109 ms / 14,974`, and `MultiPart 202.901 ms / 11,714`

Despite eliminating 202k top-level bake calls, bake wall time moved only from about `8.857 s` to `8.444 s`: roughly `0.413 s` / `4.7%`. End-to-end startup did not improve.

Key lesson: **the repeated top-level identities are numerous but cheap**. Count-based dedup badly overstates their contribution to wall time. The expensive part is elsewhere: unique graph expansion/bakes, custom geometry, dependency resolution, or other work inside the pipeline.

**Do not repeat:** another cache whose main premise is "many top-level blockstate entries point to the same object, therefore deduplicating them will remove most bake time."

**Reopen only if:** profiling demonstrates that repeated identities themselves have become expensive in a materially changed implementation/version, not merely because the repeated-entry percentage remains high.

## PR #37 — Decocraft quarter-turn geometry reuse

**Status: LIMITED / REJECTED under the measured risk-benefit**

PR: #37 `Experiment: reuse Decocraft baked geometry across quarter-turn variants`

The profiler found Decocraft repeatedly rebuilding the same Blockbench geometry for horizontal orientation variants.

Measured experiment:

- custom geometry calls: `14,108`
- authoritative bakes: `3,527`
- derived quarter-turn variants: `10,581`
- derived quads: about `2.89 million`
- derivation CPU: about `425 ms`
- repeated geometry CPU replaced: about `2.7 s`
- estimated critical-path / end-to-end gain: only around `~1 s`

Lesson: substantial CPU elimination can still have limited startup wall-clock value when that work overlaps another gate. This is useful evidence for model-pipeline accounting even though the mechanism is mod-specific.

**Do not generalize:** custom-geometry CPU totals are not automatically equal to startup savings.

## PR #35 — built-in resource reload profiling

**Status: PROFILED / SUPERSEDED for critical-path attribution by PR #47**

PR: #35 `Profile real-pack resource reload listeners`

Minecraft's `ProfiledReloadInstance` exposed large per-listener preparation/apply durations, but some listener totals exceeded the entire reload wall time because the numbers include overlapping/waiting work. That made it unsuitable for deciding the critical path directly.

PR #35 also already contained diagnostic distribution profilers for top-level model baking and NeoForge custom geometry. Those tools led directly to #36 and #37. Do not resurrect them unchanged merely to rediscover identity reuse or Decocraft's quarter-turn pattern.

Lesson: never sum listener durations and never call the largest inclusive listener timing the startup gate without barrier evidence.

## PR #47 — barrier/turn critical-path profiler

**Status: ACTIVE diagnostic**

PR: #47 `Profile resource reload critical path`

This profiler records when each listener reaches the preparation barrier, when global preparation opens, when its ordered apply turn becomes available, and when it completes. It separates:

- actual preparation gate
- global wait
- listener-order wait
- post-turn/apply work

The 2026-08-31 exact-pack run proves `ModelManager` is the real preparation gate and provides the current subphase measurements at the top of this file.

## What the next ModelManager investigation must answer

The old experiments rule out two shallow approaches: generic top-level parallelization and exact-identity top-level dedup. The next work should go below that layer.

Priority questions:

1. **What explains the current `5.93 s` ModelBakery construction after excluding already-measured categories?** The older pack spent `3.894 s` in blockstate registration, while parent resolution (`0.611 s`) and item/dependency registration (`0.127 s`) were much smaller. Instrument only the residual/changed work and cache/graph behavior rather than recreating the same inclusive probes.
2. **Where does the current `12.60 s` bake time go after internal cache hits are removed from the accounting?** Measure actual `ModelBakerImpl` cache misses / uncached bakes, recursive dependency work and exclusive custom/vanilla bake cost. The old top-level identity profiler already proved repeated wrapper calls are cheap.
3. **What is already cached internally?** `ModelBakery`/`ModelBakerImpl` already cache by model id + transformation + UV-lock. A new cache is justified only for a computation that profiling proves is both repeated and expensive after that cache and after other optimization mods' caches.
4. **Can the graph be restructured rather than merely loop-parallelized?** Investigate dependency-aware scheduling, immutable intermediate sharing/canonicalization, and safe precomputation/persistence with resource-pack fingerprints.
5. **What changed in newer Minecraft model-loading architecture?** Review upstream rewrites for ideas that can be backported while preserving NeoForge 1.21.1 custom loader and event semantics.

The acceptance criterion remains time-to-main-menu on the exact pack, not a large call-count reduction or an isolated microphase win.
