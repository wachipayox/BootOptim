# FML mod-construction observed critical path — 2026-09-10

## Scope and authority

This is Agent 105 diagnostic work only. Authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

The experiment deliberately does **not** rewrite FML futures, executor/ready-queue behavior, dependency edges, callback ordering, active-container state, or failure propagation. PR #218 established that the construction gate is inclusive wall, not additive work; PR #225 established that FML 4.0.43 already schedules each mod after its direct predecessors complete. The missing question was therefore: which mod/construction subphases actually lie on the observed wall-clock critical chain?

No optimization and no A/B are part of this PR.

## Instrumentation

A profile-only Java `premain` agent is loaded only when `-Dboot_optim.fmlChainProfile=true` is present. It instruments FML service/module classes directly and records monotonic timestamps into an in-memory queue. JSONL is written only by a shutdown hook after the measured construction gate.

Boundaries:

- `ModLoader.constructMods(...)`: construction gate begin/end;
- `LoadingModList.getDependencies(modInfo)`: direct predecessor relations;
- `FMLModContainer.constructMod()`: per-mod JavaFML construction;
- `AutomaticEventSubscriber.inject(...)`: nested automatic-subscriber work;
- `ModContainer.acceptEvent(...)`: only `FMLConstructModEvent` begin/end;
- immutable JVM thread id for every observed mod node.

The probe is fail-closed on the named runtime module `fml_loader@4.0.43`. The JAR package `Implementation-Version` is only `4.0`, so the exact gate intentionally uses `ModuleDescriptor.rawVersion()`; the trace header retains both values for auditability.

The agent keeps ByteBuddy out of the bootstrap loader. Premain creates a temporary bootstrap JAR containing only the JDK-only recorder classes so ModLauncher/FML loaders can resolve the advice bridge without duplicating ByteBuddy classes.

## Critical-chain method

The analyzer never sums durations of parallel tasks.

For each complete node it records the node interval and its constructor-exclusive, automatic-subscriber, and construct-event subphases. For each node it then considers two observed blockers:

1. the direct dependency whose node ended latest; and
2. the previous mod task that executed on the same immutable worker thread.

The later-finishing blocker is the observed causal predecessor for that run. Starting at the last-finishing node and following those predecessors backward reconstructs the observed execution critical chain. This captures real executor serialization without inventing dependency edges. `dependency-ready -> start` and causal dispatch gaps remain explicit residuals; they are not assigned to a mod as savings.

A dependency-only last-predecessor lineage is retained as a secondary view.

## Valid hosted exact-pack run

Accepted evidence is GitHub Actions run `34494959092`, job `102930931775`, artifact `10159518047`, on diagnostic head `e7437ba10efedbf83e2d2414567cefa1f633fee9`.

Environment/fixture follows the project exact-pack contract: `exact-pack-2026-09-02-v1`, fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`, Oracle JDK 25.0.4, `-XX:ActiveProcessorCount=4`, Xvfb/llvmpipe, and JCEF commit `a78e832f9f13c2c688caea3d04d8b84fcd238d94`.

Validation gates all passed:

- main-menu smoke completed;
- `bootoptim_mixin_errors=0`;
- resource selection valid: 14 expected / 14 observed in the same order, no issues, `reload_count=1`;
- exact FML header accepted: `expected_fml=4.0.43;module_name=fml_loader;module_fml=4.0.43;package_fml=4.0`;
- 250 complete construction nodes and 250 dependency records;
- 4 observed executor workers;
- analyzer completed fail-closed with no missing dependency timing nodes or cross-thread node anomalies.

This smoke measured `main_menu_ms=91587` and `mod_entrypoint_ms=32586`, but those are **not** A/B results and are not savings claims.

## Result

Construction gate wall: **5033.278 ms**. The last observed mod node ended at **5029.994 ms**, leaving only **3.284 ms** of post-node gate residual.

The last-finishing node was `ratatouille_fried_delights`. Its dependency-only lineage was:

`colorwheel -> flywheel -> ponder -> create -> ratatouille -> ratatouille_fried_delights`

The observed execution chain additionally includes worker serialization. The largest individual nodes on that observed chain were:

| mod | node ms | constructor excl. ms | subscriber ms | construct-event ms | relation |
| --- | ---: | ---: | ---: | ---: | --- |
| `create` | 1171.165 | 978.182 | 192.942 | 0.034 | dependency + worker critical |
| `veil` | 536.084 | 530.486 | 5.555 | 0.036 | worker critical |
| `securitycraft` | 522.131 | 404.760 | 117.344 | 0.021 | worker critical |
| `jei` | 325.529 | 325.373 | 0.120 | 0.030 | worker critical |
| `dndecor` | 260.380 | 255.682 | 4.675 | 0.017 | dependency + worker critical after `create` |
| `fancymenu` | 210.969 | 209.664 | 1.270 | 0.029 | worker critical |
| `entity_model_features` | 196.750 | 196.586 | 0.118 | 0.035 | worker critical |
| `patpat` | 153.013 | 152.931 | 0.064 | 0.015 | worker critical |

Long nodes on other workers are intentionally not promoted to critical-path savings. For example, `createrailwaysnavigator` was 1187.544 ms and `tfmg` 1047.121 ms but neither lay on the observed sink chain in this run.

The construct-event callbacks themselves are sub-millisecond for every material row above. Therefore a generic `FMLConstructModEvent` callback investigation is not justified by this profile.

## Selected follow-up target: Create 6.0.10 constructor

The exact pack runtime reports `Create 6.0.10` with Create commit `ac0c444d9828da3453ae8cc65338e8de063286fb`. That exact public commit exists in `Creators-of-Create/Create`.

The mod entry point `com.simibubi.create.Create` calls `onCtor(eventBus, modContainer)`. `onCtor` performs a long serial registration sequence: Registrate listener setup, sound/creative-tab/material/display/block/item/fluid/palette/menu/entity/block-entity/recipe/particle/structure/data-serializer/packet/worldgen/ingredient/attachment/data-component/map-decoration/mounted-storage registrations, config registration, schematic defaults, bogey initialization, compatibility setup, and event-bus listener registration. The source itself marks some registrations as not thread-safe.

This makes **Create's constructor path the first next diagnostic/fork candidate**, for three reasons:

1. it is the largest node on the accepted observed critical chain (**1171.165 ms**);
2. most of that wall is inside JavaFML construction excluding subscriber injection (**978.182 ms**), while `FMLConstructModEvent` is only **0.034 ms**; and
3. the exact runtime source is public and version-pinned, so a later fork or deeper internal boundary probe can target the real `Create.onCtor` sequence rather than FML scheduling.

A sensible next experiment is coarse, observational instrumentation inside the exact `Create.onCtor` commit to split registration families and static/class-initialization costs. It must preserve registration order and the explicitly non-thread-safe sections. No parallelization or deferred registration is justified by the current evidence.

The **192.942 ms** `AutomaticEventSubscriber.inject` nested under Create is a secondary candidate if the constructor-family split does not account for the node, but it is not the first target.

## Excluded runs / probe bring-up

Bring-up failures are not evidence about startup cost:

- an early attempt failed because FML could not resolve the recorder from its loader;
- another appended the complete fat agent to bootstrap and duplicated ByteBuddy, causing a loader-constraint `LinkageError`;
- another read package `Implementation-Version=4.0` and correctly failed closed against the desired exact version; this led to the named-module version gate;
- run `34494034657` produced an active 4.0.43 trace but failed the exact-pack resource contract with `reload_count=2`, so all its timings are excluded from conclusions.

Run `34494959092` is the accepted profile because both instrumentation analysis and the exact-pack runtime/resource gates passed.

## Decision

Diagnostic question answered: the ~5 s hosted FML construction wall is almost entirely covered by observed mod-node execution, and the accepted run's largest critical mod node is Create. Within Create, the material boundary is constructor/static-registration work, not `FMLConstructModEvent`.

Do **not** reopen the generic FML scheduler. Do **not** treat any duration above as removable savings. The next scoped work should instrument/fork the exact Create 6.0.10 constructor path while preserving its registration semantics and thread-safety constraints.
