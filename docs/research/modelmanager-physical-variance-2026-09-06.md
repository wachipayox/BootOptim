# ModelManager physical variance / critical reload audit — 2026-09-06

Status: **PROFILED / NO PRODUCTION CHANGE YET**

Base authority: `agent/integration-current` @ `b0aa2472d58e3afc56a380e026c99ffe87000f22`.

Agent 39 branch: `agent39/modelmanager-variance-20260906`.

This audit consumes the corrected physical run `p0.2-variance-harness-20260906b` together with the current research ledger. It does not change runtime code, executor policy, resource ordering, model semantics, OpenGL ownership, JVM flags or gameplay.

## Corrected physical run

The supplied low-noise harness result reached the menu and reports:

- startup/menu opening: `350,330 ms` wall;
- first post-opening display completion: `356,274 ms` (`+5,944 ms` after opening);
- initial resource reload: `186,709.874 ms` inclusive wall;
- final `ModelManager.reload` future: `150,078.409 ms` inclusive wall;
- block states future: `9,657.986 ms` wall;
- block models future: `24,960.395 ms` wall;
- aggregate atlas load future: `34,314.295 ms` wall;
- `ModelBakery` construction: `65,459.832 ms` wall;
- synchronous `loadModels`: `46,874.146 ms` wall;
- `bakeModels`: `41,152.538 ms` wall, nested inside `loadModels`;
- Minecraft process CPU by menu: approximately `1,053,438 ms`;
- effective processors: `4`;
- GC: `153` collections / approximately `15,421 ms` aggregate collector time;
- heap: approximately `3.63 GiB` used against a `6 GiB` maximum.

The async/future scopes are inclusive and overlap. They are not summed as savings. `loadModels` contains `bakeModels`, so `46.874 + 41.153` is not a valid total.

## Critical-path reconstruction

Minecraft 1.21.1 already starts block-model loading, block-state loading and atlas preparation concurrently. `ModelBakery` waits on the model/state inputs; `loadModels` waits on the bakery plus the atlas results. This dependency graph was source-audited in #132 and the barrier interpretation was validated by #47/#57.

The corrected physical ordering changes the diagnosis from the earlier atlas-first suspicion:

1. block states finish in about `9.658 s`;
2. block models finish in about `24.960 s` and are the later JSON/model input;
3. atlas preparation finishes in about `34.314 s`;
4. `ModelBakery` itself takes `65.460 s` after its inputs are available;
5. therefore the model branch reaches the `loadModels` join roughly `24.960 + 65.460 = 90.420 s` after the ModelManager start, while the atlas branch is already ready around `34.314 s`;
6. `loadModels` then takes another `46.874 s`, of which `41.153 s` is `bakeModels`.

Using the structural durations, the model branch accounts for roughly `24.960 + 65.460 + 46.874 = 137.294 s` before final ModelManager completion. This is an approximate structural chain rather than a substitute for exact monotonic timestamps, but it explains most of the measured `150.078 s` ModelManager wall. The residual after that chain is about `12.784 s` and must be classified with the harness's preparation-barrier/apply-turn rows before being called apply, GPU, I/O or scheduler time.

The atlas future is **not the direct join gate in this run**. An atlas optimization can still reduce CPU, allocation or storage contention and thereby accelerate the model branch indirectly, but early atlas upload or a per-atlas join redesign has no direct critical-path ceiling here. PR #132's no-go on early publication remains valid: live `TextureAtlas` mutation and NeoForge complete-map callbacks are generation/publication boundaries, and GL must remain render-thread-owned.

## Scaling against the current hosted boundary probe

PR #138's hosted exact-pack boundary run, also with `ActiveProcessorCount=4`, measured:

| Phase | Hosted #138 | Physical corrected | Physical / hosted |
| --- | ---: | ---: | ---: |
| block states | `2,934.157 ms` | `9,657.986 ms` | `3.29x` |
| block models | `7,136.946 ms` | `24,960.395 ms` | `3.50x` |
| atlas load | `7,575.593 ms` | `34,314.295 ms` | `4.53x` |
| ModelBakery | `11,732.996 ms` | `65,459.832 ms` | `5.58x` |
| bakeModels | `7,780.860 ms` | `41,152.538 ms` | `5.29x` |
| loadModels | `9,142.227 ms` | `46,874.146 ms` | `5.13x` |
| ModelManager final future | `29,792.249 ms` | `150,078.409 ms` | `5.04x` |

These are cross-hardware ratios, not same-machine A/B effects. Their value is diagnostic: the largest current amplification is no longer blockstate/block-model JSON enumeration. It is the already-loaded model graph / ModelBakery / bake portion.

That is materially different from the 2026-09-01 slow-hardware campaign (#65/#68), where block states, atlas and block models scaled about `20.42x`, `15.65x` and `9.36x` against the fast PC while `ModelBakery` and `bakeModels` scaled only `3.79x` and `3.40x`. The old conclusion that resource-facing work disproportionately dominated weak-hardware scaling was correct for that campaign, but it is not sufficient to explain the corrected current run. The workload/integration/resource-selection state also changed between campaigns, so this shift must not be attributed to Decocraft or any single promotion without a same-build comparison.

## Causal ranking for current variance

### High confidence

**1. ModelManager is still the dominant reload-side bottleneck, and the current critical branch is model construction/bake rather than atlas readiness.** Historical #47/#57 barrier evidence proves ModelManager can be the global preparation gate, and the corrected run places about `137 s` of structural work on the block-model -> ModelBakery -> loadModels chain. The supplied summary does not include the exact current `allPreparations`/listener-turn row, so the final `150.078 s` future is not claimed as 150 s of removable work; the harness row must remain authoritative for the exact barrier edge.

**2. The current run is CPU-scarce enough that scheduling/JIT/allocation competition is first-order.** `1,053,438 ms` process CPU over `350,330 ms` wall is about `3.01` equivalent CPU cores across the whole startup on a four-processor JVM. That does not prove every ModelBakery millisecond is CPU, because the supplied run summary lacks phase-local process-CPU deltas, but it rules out a simple mostly-idle process story. The earlier JFR campaign independently found heavy compiler time, allocation on resource workers/render/main threads and contention on a four-thread laptop.

**3. GC is material but not the primary direct cause.** `15.421 s` of aggregate collector time is about `4.4%` of the `350.330 s` menu-opening wall. Even an unrealistically perfect elimination of all collector time cannot explain a `150 s` ModelManager future or the full 350–380 s physical spread. Allocation reduction remains valuable because it can also reduce memory bandwidth and page-cache pressure, but changing G1 flags is not justified as the primary BootOptim solution.

**4. ZIP/page-cache sensitivity is real but too small to explain the current ModelManager wall by itself.** #140/#141 measured the same FilePackResources enumeration mechanism at `5,275.585 ms` and `1,321.009 ms` inclusive physical wall, proving strong hardware/cache sensitivity. The roughly `3.955 s` spread is real, but those calls overlap and cannot be summed as TTMM savings. #142's ordered ZipEntry snapshot is semantically narrow, but hosted reload-to-FancyMenu moved only about `-103 ms`; the candidate therefore remains **sin evidencia física** and cannot be promoted. Conversely, that small hosted delta does not disprove a laptop-only storage effect.

### Probable, not yet proven for this exact run

**5. CPU throughput variation and cross-pool contention are likely amplifiers of run-to-run wall variance.** ModelBakery and bake scale about `5.3–5.6x` versus the hosted four-processor surrogate, and the JVM averages about three busy cores over the full startup. On the 2C/4T target, small changes in background CPU, compiler activity, resource workers, native renderer work, SMT sharing, memory bandwidth or effective clock can therefore move tens of seconds of a roughly 137 s model critical chain. This is a causal prediction, not proof of which external owner caused a particular slow run.

**6. Memory pressure can couple Java allocation to storage/page-cache behavior.** The run reaches about `3.63/6 GiB` heap and performs 153 GCs. The earlier JFR campaign saw byte arrays, reflection, JSON, voxel/model work and resource workers among the major allocation families. This makes cache eviction / physical-memory pressure plausible as a variance amplifier, but available-memory snapshots are not hard-fault/page-read evidence.

### Unresolved external bucket

The current Java data still cannot distinguish Windows hard faults/page reads, Defender/antivirus ownership, external-process CPU/I/O, thermal/power throttling, effective clocks or OS descheduling. `wall - process CPU` is not assigned to disk or GPU. A software/native renderer may still contribute to the later `+5.944 s` opening-to-presented gap or indirectly compete for CPU; it is not the direct explanation for the pre-upload ModelBakery/bake interval.

## Scheduling / worker policy decision

**NO-GO on another generic parallelism or worker-count patch.**

- #14 already showed that eager top-level bake parallelism can improve the isolated bake while regressing TTMM.
- Minecraft already overlaps the safe block-model, block-state and atlas preparation branches.
- #126/#133's temporary ModernFix `ForkJoinPool` target `3 -> 2` had an unstable sign hosted and on the physical laptop; the reversed physical pair did not reproduce the initial reload improvement.
- `ForkJoinPool.setParallelism` is a target, not a strict cap, and blocked-task compensation plus third-party reload listeners make queue depth a poor hardware controller input.
- Historical #57 had ModelManager only about `16 ms` ahead of the global preparation barrier. A priority scheme can simply transfer the gate to another listener and may change mod callback/task timing.
- Changing `ActiveProcessorCount` is JVM-global and changes GC/JIT/ForkJoin/mod heuristics simultaneously. There is no evidence from which to derive a safe automatic hardware rule.

Do not create a second model executor, globally tune the common pool, prioritize arbitrary ModelManager tasks, or ship an adaptive processor-count controller from the current evidence.

## Cache / persistence decision

**NO-GO on a production baked-model or generic ModelBakery cache today.** Existing negative evidence already covers the shallow forms:

- #36 top-level identity reuse removed 64.57% of eligible calls but only about `0.413 s` of bake wall and no E2E win;
- the recursive `BakedCacheKey` path already showed about 97.60% hits in the deep audit, so another generic recursive cache attacks mostly cached work;
- #59 material-resolution caches reached roughly 91–96% hit rates without a positive direct metric;
- #119 demonstrates the additional risk of a global synchronized cache in a reload hot path.

Persisting `BakedModel` objects across reloads/processes is also semantically unsafe: baked quads reference current atlas sprites/generation state, custom geometry loaders can inject arbitrary model types, and NeoForge `onModifyBakingResult` / `onModelBake` are complete-map compatibility boundaries. Reusing old runtime objects would require explicit current-generation remapping and callback-equivalence proofs.

### Architecture worth reopening only after current physical attribution

A materially different candidate is a **persistent immutable model-preparation plan for a strict vanilla-safe subset**, not a BakedModel cache.

The cacheable object would contain only atlas-independent, callback-independent structural data proven pure for strict vanilla `BlockModel` inputs: normalized parent/dependency relationships and immutable element/face bake instructions. Each reload would still bind current `TextureAtlasSprite` identities, call stock/NeoForge `FaceBakery`, construct fresh runtime models and fire NeoForge model callbacks at the original complete-map boundaries. Custom geometry, custom loaders, model custom data or any unknown shape would fail open to stock.

This architecture is not implemented because the current evidence does not yet prove that the `65.460 s` ModelBakery cost is dominated by structural work that such a plan can remove. Historical parent/material subphases were much smaller, so implementing persistence now risks another high-hit/low-value cache.

If the physical exclusive profile proves a multi-second deterministic vanilla structural ceiling, a prototype must have:

- default-off kill switch, e.g. `-Dboot_optim.modelPreparationPlanCache=true` only on the experiment branch;
- immutable cache payloads; no `BakedModel`, `TextureAtlasSprite`, GL object or mutable model registry retained;
- exact schema + Minecraft + NeoForge + BootOptim versioning;
- a strong fingerprint of selected resource-pack order and every contributing model/blockstate source, plus exact safe-domain classification;
- generation IDs so a manual/subsequent reload cannot observe a previous generation;
- atomic temp-file -> replace publication only after a successful authoritative reload;
- fail-open on any fingerprint mismatch, corrupt/incomplete cache, unknown loader/custom data, unexpected model type or verifier mismatch;
- no global lock on the reload hot path;
- verification mode that recomputes stock for the eligible subset and compares canonical outputs/quad metadata before performance mode is considered.

## Deferral decision

A generic lazy ModelManager is **not currently safe**. Initial resource reload completion and NeoForge model-bake callbacks expose complete-map semantics; allowing the menu to publish while arbitrary models remain unbaked would change what callbacks/listeners can observe. A true lazy architecture would need a generation-staged registry, an explicit pre-gameplay fence, callback replay/ordering semantics and fail-open publication. That is a much larger lifecycle redesign, not a narrow startup patch. No implementation is justified from one current physical distribution sample.

## G1 / memory / JVM policy

The current JVM uses a 6 GiB heap and G1 with a forced `32M` region size plus `ActiveProcessorCount=4`. The observed 153 collections / 15.421 s collector time justify recording phase-local GC/allocation pressure, but not shipping JVM flag changes from BootOptim. A G1/region-size experiment would change the execution environment rather than the mod and could also change page-cache availability. If evaluated, it must be a separately labelled physical environment study with identical software/pack state, not mixed into a BootOptim production candidate.

## Minimum physical validation plan

One corrected harness launch is a causal sample, not a variance distribution. The minimum next evidence is intentionally small:

1. Keep `p0.2` software, resource-pack order, Java/G1/heap/processor settings and OS/driver state fixed. Collect **two additional valid harness runs** so the current low-noise instrumentation has `n=3` comparable physical samples. Reject stale-JVM/process-age failures before aggregation.
2. Compare exact monotonic `allPreparations`, ModelManager barrier/turn/final-future, block-model/bakery/load/bake wall and **phase-local process CPU / GC / available-memory deltas**. Report medians/ranges; do not sum futures/listeners.
3. If ModelBakery+bake remains the gate and its wall varies with high process CPU, take one bounded host trace on a representative run for runnable/descheduled time, hard faults/page reads, effective clock/throttling and external-process CPU/I/O ownership. If process CPU is low while wall grows, prioritize the host/storage/descheduling branch instead. Do not infer from subtraction.
4. Only after the current ModelBakery constructor/bake exclusive work is attributed should a production candidate be coded. Hosted exact-pack then remains the first semantic/A-B rejection gate, but any small hosted delta is labelled **sin evidencia física** rather than used to close a hardware-scaling hypothesis.
5. Promotion requires an interleaved physical control/candidate comparison with unchanged pack/JVM state, matching resource selection, atlas dimensions, zero new Mixin/model errors and the same visible/gameplay semantics. The decisive metrics are TTMM/presented-menu wall plus the actual ModelManager preparation barrier, not the candidate microphase alone.

## Decision

The corrected run materially changes priority: **the direct critical bottleneck is the block-model -> ModelBakery -> load/bake branch, not atlas readiness and not ZIP enumeration.** The strongest present causal model for the 350–380 s variability is a CPU-dense model pipeline amplified by four-thread scheduling/JIT/allocation/memory contention, with proven but smaller storage/page-cache sensitivity and secondary GC cost. The specific external source of run-to-run throughput changes remains unmeasured.

Therefore this branch makes **no runtime optimization**. A worker-count/adaptive-parallelism patch, generic baked-model cache, early atlas publication, JVM tuning or lazy complete-map publication would outrun the evidence or violate established compatibility boundaries. The next implementation should be selected only after the existing low-noise harness has a three-run physical distribution and current ModelBakery exclusive attribution. The strongest new architecture to keep on the roadmap is a strict-domain immutable preparation-plan cache with exact fingerprints/generations/fail-open semantics, but only if that attribution proves a material removable structural ceiling.

Related evidence: #14, #36, #43, #47, #57, #59, #64, #65/#68, #69, #71/#72, #119, #126/#133, #132, #136–#142, #147 and #148.
