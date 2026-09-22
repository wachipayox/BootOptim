# P1 combined low-noise diagnostic build — 2026-09-07

Status: **DIAGNOSTIC ONLY / DEFAULT OFF / PHYSICAL RUN GATED BEHIND P0 HARNESS CERTIFICATION**

Current base authority: `agent/integration-current` @ `493cdb4e05f03c5a180d0f77aaf51ae04b9d1fbb`. The task was initially issued against `8f2c9c32771b6743417b3332030b467d5a4c6fc8`; integration advanced through #164/#161 while this work was in progress, so the diagnostic branch was rebuilt on the refreshed authority before CI.

Branch: `agent46/p1-diagnostic-build-20260907`.

## Decision

Current integration has the transactional remote runner and measurement-validity tooling but no distributable runtime build combining the low-noise JVM/process variance snapshots with detailed ModelManager/ModelBakery boundaries. PR #138 is the earlier aggregate ModelManager probe. PR #148 is the reviewed successor and already includes those aggregate boundaries in one startup-variance observer. Porting both would duplicate the same futures/scopes and add redundant observer work, so this branch selectively ports the validated #148 instrumentation head `523a434dba87e00021f012da715039364599973a` onto current integration.

The port is selective, not a branch copy. Later production changes remain authoritative: current CITResewn mixins are preserved, the no-live-log exact-pack runner from #164 is untouched, and the three-state validity tooling from #161 remains intact.

## Activation

There is exactly one runtime opt-in property:

```text
-Dboot_optim.profileStartupVariance=true
```

Absent or false, the diagnostic snapshotter is disabled. There is deliberately no second `profileResourceReloadBoundaries` switch in this build because #148 already subsumes the relevant #138 ModelManager boundaries.

## Boundaries and state captured

The low-cardinality `BOOTOPTIM_VARIANCE` stream uses explicit scope IDs and observes only coarse lifecycle boundaries:

- earliest ModLauncher transformation-service/JVM-age point;
- root and dependency discovery;
- vanilla `Bootstrap.bootStrap()`;
- BootOptim mod entrypoint;
- first resource reload start/end and global `allPreparations`;
- `block_models` and `block_states` futures;
- aggregate `AtlasSet.scheduleLoad` futures;
- `model_bakery_init` (`ModelBakery` construction);
- `bake_models`;
- `load_models`;
- final `model_manager_reload` future;
- reload-listener preparation barrier, stock ordered apply-turn and listener completion;
- FancyMenu `preLoadAll` entry/return;
- accepted `main_menu` opening and first subsequent `Window.updateDisplay()` completion (`main_menu_presented`).

At each coarse variance boundary the snapshot records monotonic time, JVM start/uptime, whole-process CPU, owner-thread CPU when supported, GC count/time, heap used/committed/max, available physical memory and corresponding phase deltas. Listener lifecycle instrumentation is cheaper: while reload is critical it records monotonic time plus atomics only; formatting/logging happens after `main_menu_presented`.

These scopes are inclusive and overlap. `load_models` contains `bake_models`; listener waits/futures are not additive and must never be converted into a savings sum. `wall - process CPU` is not disk/GPU/descheduling attribution; GC MXBean time is accumulated collector elapsed time, and available memory is not a hard-fault/page-cache counter.

## Perturbation and semantic contract

This build adds diagnosis only:

- no ModelBakery parallelism, new executor or scheduling policy;
- no cache, persistence or preparation IR;
- no model/resource identity map;
- no JSON, model object or stack retention;
- no per-model/per-resource timer or logger;
- no resource/file wrapper;
- no render/OpenGL work moved between threads;
- no selection, ordering or callback change;
- no sleep/live-log polling loop;
- no JFR/profiler library packaged in the JAR.

The diagnostic mixins keep `defaultRequire=0`. When disabled they encounter only the static opt-in gate. When enabled, work scales with the small coarse-boundary set plus one constant-size lifecycle record per reload listener, not with model/resource count. The historical #148 hosted gate showed the reviewed instrumentation could reach the exact-pack menu with complete parser-valid rows, expected atlas state and no BootOptim Mixin errors; its hosted timing delta is not optimization evidence or a precise observer-cost estimate.

## External JFR compatibility (#165)

JFR stays external. This branch packages no `.jfc`, `StartFlightRecording` logic, profiler agent or recording library. PR #165's external wrapper can later add `-XX:StartFlightRecording=...` to a JVM that also uses `-Dboot_optim.profileStartupVariance=true`; such a run is a separate observer profile, collected and partitioned offline after JVM exit. The broad hosted #165 model markers accounting for only about 10–13% of post-entry samples do not authorize a production cache/IR.

The first P1 physical variance launch after P0 should use this low-noise probe alone. Add external JFR only for a later attribution question that justifies the extra observer load.

## Physical use and restoration after P0

One fixed-setting physical P1 attribution launch is allowed only after the transactional P0 harness is certified. The #163 remote tooling remains operational authority:

1. prove Prism is stopped and no stale target JVM survives;
2. stage exactly one packaged wrapper from `bootstrap/build/libs/` and verify its SHA-256;
3. require `-Dboot_optim.profileStartupVariance=true` exactly once in the effective JVM command line;
4. launch through the verified interactive task/session and bind the fresh Java PID + creation time + instance provenance;
5. after Java identity acceptance, block on process completion without polling logs/resources;
6. collect frozen console/logs only after Java exit;
7. run `tools/laptop-bench/variance_probe.py`, resource-selection checks and the measurement-validity gate offline;
8. after Java and Prism are stopped, restore the exact original `instance.cfg` and original BootOptim wrapper, verify restoration hashes and leave exactly one packaged wrapper.

Stale JVM, wrong/duplicate BootOptim JAR, wrong effective properties, wrong resource selection, missing/duplicated mandatory markers or structurally invalid scopes invalidate the run before timing interpretation. A forced shutdown after complete `main_menu_presented` evidence may remain phase-attribution evidence, but is not a clean voluntary-exit benchmark.

## CI / artifact gate

Before disposition can be **ready for one physical P1 measurement after P0**, the refreshed branch must pass:

- Build workflow unit tests, including `test_variance_probe.py`;
- `./gradlew build`;
- packaged-bootstrap validation of the wrapper produced in `bootstrap/build/libs/` (JarJar metadata, early transformation service, `FMLModType: LIBRARY`);
- normal Startup workflow;
- exact-pack smoke with `-Dboot_optim.profileStartupVariance=true`, unchanged resource contract/atlas and no BootOptim Mixin errors;
- SHA-256 computed from the downloaded packaged wrapper (`bootoptim-ci.jar`), never from the root `build/libs/` inner JAR.

A green microphase/count result is not a TTMM claim. This build is for attribution only.
