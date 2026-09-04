# PR #99 FML lifecycle exact-pack results

Status: **ACTIVE / DIAGNOSTIC ONLY**

This appendix records runtime results for PR #99 (`agent/diagnostic-fml-lifecycle-critical-path`) before the profiler is expanded. It exists so the first-stage evidence cannot be overwritten or retrospectively reinterpreted by the second-stage instrumentation.

## Stage 1 exact-pack smoke — 2026-09-04

Environment:

- pinned fixture `exact-pack-2026-09-02-v1`;
- 160 root mod JARs;
- Oracle JDK 25.0.4 runtime;
- `-Xmx6G` and the pack's existing G1 arguments;
- hosted runner constrained with `-XX:ActiveProcessorCount=4`;
- llvmpipe/Xvfb hosted surrogate;
- diagnostic property only: `-Dboot_optim.profileFmlLifecycle=true`;
- no lifecycle work skipped, reordered, cached, parallelized or moved to another executor;
- BootOptim Mixin errors: `0`;
- title reached successfully.

Top-level result:

- `mod_entrypoint_ms = 30,937`;
- `main_menu_ms = 91,852`;
- `post_mod_entrypoint_ms = 60,915`.

These are a one-run smoke sanity result, not an optimization A/B.

## Lifecycle measurements

| phase | placement | wall ms | caller CPU ms | process CPU ms | loaded classes | JIT ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `gather_and_initialize_mods` | pre-resource-reload | **5,237.619** | 112.743 | 16,980 | 27,063 | 5,703 |
| `registry_initialization` | pre-resource-reload | **9,215.363** | **13.356** | **17,910** | 4,545 | 4,831 |
| `config_loading` | pre-resource-reload | 60.876 | 0.412 | 220 | 71 | 119 |
| `common_setup` | resource preparation | 4,522.466 | 9.808 | 17,410 | 5,703 | 6,207 |
| `sided_setup` | resource preparation | 3,783.348 | 7.152 | 13,800 | 2,469 | 4,395 |
| `registration_events` | resource preparation | 389.362 | 0.984 | 1,220 | 335 | 630 |
| `enqueue_imc` | ordered post-barrier | 19.847 | 1.186 | 70 | 36 | 10 |
| `process_imc` | ordered post-barrier | 35.865 | 0.916 | 120 | 26 | 15 |
| `load_complete` | ordered post-barrier | 324.596 | 1.406 | 1,130 | 1,035 | 291 |
| `network_registry_lock` | ordered post-barrier | 243.059 | 0.723 | 400 | 1,299 | 88 |

### Interpretation boundaries

`registry_initialization` is the strongest new critical-path observation from stage 1. It is executed by `CommonModLoader.begin(...)` before the initial resource-reload overlap window, so its **9.215 s wall is directly on the path to reaching reload** in this run.

The ~13 ms caller CPU is the Render thread waiting around FML's `runInitTask`; it is not evidence that the registry task itself is idle. The process accumulated ~17.91 CPU-s during the 9.215 s window. That process CPU is inclusive across JVM threads/JIT and is **not** exclusive registry CPU or a savings estimate.

`common_setup`, `sided_setup`, and `registration_events` are inclusive wall inside resource preparation and must not be summed as TTMM opportunity without proving they are the preparation gate.

The four ordered finish phases total about **623 ms wall** in this smoke. They are directly post-barrier, but their current ceiling is much smaller than the pre-reload registry window, so the second-stage profiler prioritizes registry initialization.

## Existing log evidence inside registry initialization

The uninstrumented per-registry log sequence already makes `minecraft:block` a strong but unresolved suspect:

- `22:10:49.023`: Decocraft logs `RegisterEvent<Block> fired`;
- `22:10:49.113`: Decocraft begins loading 635 unique models;
- `22:10:50.316`: Decocraft reports model loading completed in **1,205 ms**;
- `22:10:50.370`: Decocraft reports block registration completed in **1,259 ms** for 3,527 entries;
- `22:10:51.125`: TFMG logs its block remap;
- `22:10:54.745`: Decocraft logs `RegisterEvent<Item> fired`.

The visible Block→Item interval is therefore roughly **5.72 s**, while Decocraft's own current logging claims ~1.26 s for its block handler. This is enough to justify `Block` attribution but **not** enough to claim Decocraft dominates the registry wall. Other mod handlers, classloading/Mixin/JIT triggered inside handlers, and registry/event-bus overhead can occupy the remaining interval.

## Stage 2 diagnostic decision

The next commit remains instrumentation-only. It must preserve stock registry order and mod-bus order.

Measure:

1. `RegistryManager.postNewRegistryEvent()`;
2. `GameData.unfreezeData()`;
3. total `GameData.postRegisterEvents()`;
4. every stock `RegisterEvent<registry>` dispatch;
5. `GameData.freezeData()`;
6. wall attributed to each `ModContainer` while a `RegisterEvent` is being dispatched, then report the top contributors for the dominant registry;
7. residual `postRegisterEvents` wall not accounted for by the individual register-event dispatches.

No registry or mod-bus parallelization is permitted in this diagnostic.

Because `gather_and_initialize_mods` is also material at **5.238 s**, a safe second-stage split may additionally measure background scan wait, serial `buildMods`/container-entrypoint construction, parallel construction dispatch, and the construction deferred queue. This is attribution only; PR #19 remains evidence against speculative generic class prewarming/parallel classloading.

## Promotion gate

Do not propose a production change from the 9.215 s aggregate alone. First identify the dominant registry and `ModContainer` contribution. Decocraft Block is confirmed only if per-container timing shows its handler accounts for a dominant fraction of the `minecraft:block` dispatch; otherwise record it as a partial contributor and follow the actual top owner.