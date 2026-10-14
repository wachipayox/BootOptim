# Physical resource-reload / ModelManager boundary audit — 2026-09-06

Status: **ACTIVE DIAGNOSTIC — hosted semantic gate first; physical cause still unproven**

Base audited: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

Diagnostic branch: `agent32/profile-resource-reload-boundaries`.

## Question

The fixed exact-pack laptop has repeatedly spent roughly 163–227 s from resource-reload listener registration to `Minecraft resource reload: FINISHED`, while hosted exact-pack `reload -> FancyMenu` is normally only tens of seconds. Run `laptop-fancymenu-active-layout-006` has a particularly large interval before the existing Decocraft bake marker:

- `Reloading ResourceManager`: 00:20:20.775
- first ModelManager failure: 00:20:31.684
- `BOOTOPTIM_DECOCRAFT_QUAD_REUSE`: 00:21:46.411
- resource reload finished: 00:22:49.419

The purpose of this diagnostic is to decide whether the large pre-bake wall interval is held by model/blockstate resource preparation, atlas sprite preparation, ModelBakery construction/bake, or a later barrier/apply interval. It does not attempt an optimization.

## Historical constraints

- PR #14 already rejected simple eager top-level bake parallelism: isolated bake improved but TTMM regressed slightly.
- PR #36 removed 64.57% of eligible repeated top-level bakes but saved only ~0.413 s in `bakeModels` and did not improve end-to-end startup. Count-based ModelManager caches are not reopened.
- PR #47 established that listener durations are inclusive/overlapping and must not be summed; barrier/turn timing is the correct critical-path interpretation.
- PR #57 showed ModelManager remained the preparation gate after current production model optimizations.
- PR #69 is the strongest physical decomposition evidence but its per-resource profiler had observer effect, so its task sums are attribution ceilings, not TTMM savings.
- PR #120 reiterates that low Java process CPU across a wall interval does not identify disk/GPU by subtraction.
- PR #132 closes atlas-upload rescheduling: 1.21.1 already overlaps safe preparation, atlas upload publishes live texture state, and GL work must remain render-thread-bound.

## What the existing physical decomposition actually says

PR #69 slow-laptop run (`main_menu=378128 ms`) measured:

### Block states

- 11,435 resource-stack tasks: 8,649.468 ms task-sum
- `Resource.openAsReader`: 7,961.276 ms task-sum
- Gson parse: 549.682 ms task-sum

This shows resource opening/access dominated the measured blockstate task work, but task-sum is not critical-path wall.

### Block models

- 44,103 resource tasks: 63,243.150 ms task-sum
- `BlockModel.fromStream`: 5,896.289 ms task-sum

The ~57.35 s residual was not decomposed into open/read/decompression/materialization and therefore cannot be named as one cause.

### Atlas

- 20,054 delegated sprite loads: 65,326.153 ms task-sum
- blocks atlas `loadAndStitch`: **55,377.455 ms wall scope**
- actual blocks-atlas `stitch(...)`: only **875.884 ms**
- sprite supplier discovery: 943.057 ms
- atlas-definition load: 205.170 ms
- Decocraft sprite loads: 5,771 calls / 40,553.154 ms task-sum

The important correction is that the old label `atlas_stitch` mostly represented sprite/resource/decode work and waiting, not final packing. This is a plausible explanation for a 50–80 s physical pre-bake gap, but it has not yet been correlated to active-layout-006 and is therefore still a hypothesis.

## GPU/backend boundary

`AtlasSet.scheduleLoad`/sprite preparation occurs before stock atlas upload. The expensive #69 `loadAndStitch` interval therefore cannot be explained directly by OpenGL upload on the Microsoft Basic Render Driver / Mesa D3D12 path. A slow software/native graphics backend can still matter later during apply/upload/presentation or indirectly through system contention, but it is not a source-level explanation for a long aggregate atlas-preparation future.

This distinction is why the diagnostic records the aggregate atlas future separately from final `ModelManager.reload` completion and does not move any GL work.

## Diagnostic implementation

Property:

```text
-Dboot_optim.profileResourceReloadBoundaries=true
```

Primary marker:

```text
BOOTOPTIM_RELOAD_BOUNDARY
```

Only aggregate stock boundaries are observed:

- `block_models` future completion
- `block_states` future completion
- all futures returned by `AtlasSet.scheduleLoad`
- `ModelBakery` constructor
- `ModelBakery.bakeModels`
- synchronous `ModelManager.loadModels`
- final `ModelManager.reload` future completion

Each row contains monotonic elapsed wall, JVM uptime, cumulative Minecraft-process CPU, and process-CPU delta for that phase. No resource is wrapped, no file read is intercepted, no executor is replaced, no listener is reordered, and no model/atlas data is modified. With the property absent, the hooks only encounter the static disabled gate.

This deliberately strips the high-cardinality resource bookkeeping that perturbed #69.

## Mechanical interpretation

The useful comparison is completion order, not sums.

1. If `atlas_schedule_load` finishes last and `load_models` begins immediately after it, the atlas/sprite branch is the ModelManager join gate for that run.
2. If ModelBakery construction finishes later than atlas and `load_models` follows the bakery, the model JSON -> bakery branch is the gate.
3. If `bake_models` itself owns the large wall interval, the pre-existing bake residual work remains the target; do not infer savings from Decocraft/count markers alone.
4. If `load_models` finishes substantially before final `model_manager_reload`, the residual is post-bake readiness/global barrier/apply work; a #47-style barrier/turn probe is then the next required attribution, not speculative atlas scheduling.
5. For any interval, `cpu_delta_ms / elapsed_ms` is the average number of CPU cores consumed by the Minecraft JVM during that scope. High wall with high process CPU supports CPU contention/work; high wall with little Minecraft-process CPU leaves filesystem/page cache, native waits, OS descheduling or external system contention unresolved. It does **not** by itself identify which one.

Because process CPU is read from inside the Minecraft JVM, launcher/harness CPU is not charged to the Minecraft process. Conversely, system contention caused by another process can lengthen wall without appearing in Minecraft process CPU; that case remains an external bucket until host telemetry/JFR is added.

## Warning counts

Run 006 contains 146 `Missing textures in model`, 13 `Missing sprite` warnings and one failed model. They are not treated as a cause. The boundary diagnostic does not silence or alter them. Only temporal/CPU correlation could justify investigating their underlying model/resource paths.

## Hosted gate

Hosted exact-pack is an instrumentation/semantic gate, not hardware equivalence. Before asking for physical data the branch must:

1. build successfully;
2. reach the hosted exact-pack title/menu with the diagnostic property enabled;
3. emit non-empty `block_models`, `block_states`, `atlas_schedule_load`, `model_bakery_init`, `bake_models`, `load_models`, and `model_manager_reload` rows;
4. show zero new BootOptim/Mixin failures;
5. preserve the exact pack/resource selection and normal atlas dimensions;
6. show plausible monotonic ordering (the async branches may overlap, but `bake_models` must occur within `load_models`, and final ModelManager completion must not precede it).

A small hosted atlas interval does not close the physical line because storage, CPU speed, page cache and native backend differ materially from the laptop.

## One physical run, only after hosted passes

Exactly one fixed-selection active-layout launch is requested. Collect the existing reload/FancyMenu/title markers plus every `BOOTOPTIM_RELOAD_BOUNDARY` row. No repeated laptop A/B is needed because this is causal classification, not a performance candidate.

Decision-changing outcomes:

- **Atlas gate reproduced:** `atlas_schedule_load` is tens of seconds and is the last prerequisite immediately before `load_models`. Keep the line open; use its CPU ratio to choose the next diagnostic. High CPU -> bounded sprite decode/resource-hotpath profiler. Low CPU -> bounded JFR FileRead/ZipFile/page-cache/OS-contention probe. Do not optimize final stitch or GL upload.
- **Model/bakery gate instead:** atlas completes materially earlier; focus on whichever of block-model future, bakery construction or bake is last. Existing #36/#14 mechanisms remain closed unless the new hot work is structurally different.
- **Large post-loadModels residual:** add the minimal #47 preparation-barrier/ordered-turn boundary before touching apply scheduling. PR #132's early-upload idea remains closed without evidence that a current atlas-ready/upload boundary itself owns seconds.
- **No large boundary on the physical run:** classify the 001–006 discrepancy as non-reproduced/variance and do not request another laptop campaign without a new falsifiable premise.

## Decision

There is **no production optimization justified yet**. The strongest current hypothesis is a hardware-sensitive atlas sprite/resource-preparation gate, because #69 measured 55.38 s wall in the blocks-atlas `loadAndStitch` future while actual stitch packing was <0.9 s. That hypothesis remains **sin evidencia física comparable for active-layout-006** until the one low-overhead boundary run above correlates it to the current fixed-selection workload.
