# Pre-Minecraft bootstrap transition trace — 2026-09-08

Status: **PROFILED DIAGNOSTIC EDGE / NO PERFORMANCE CLAIM**

## Scope and stacking

This investigation is limited to the uncovered interval between the end of `dependency_discovery` and entry into `net.minecraft.server.Bootstrap.bootStrap()`.

Authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This branch is intentionally stacked on diagnostic PR #203 head `5995a3e7f914ae07483219ee1ccf947ed10b3365`, which is stacked on #202 -> #201 -> #200. It reuses the single `StructuredBootTrace` schema/writer from #200 and the SERVICE classpath repair from #201. It must be rebased after the lower stack is resolved; this diagnostic is not production code and is not integrated into `agent/integration-current`.

No scheduling, callback, classloading, module-layer, Mixin, render/OpenGL, resource-loading or gameplay behavior is changed.

## Starting evidence

Validated #203 hosted exact-pack profile run `34280786382` reached `main_menu` with one trace header, one summary, four balanced task pairs, zero dropped events, zero flush/development-sink failures and zero trace errors.

Its monotonic boundaries were:

| Boundary | Type | Wall |
| --- | --- | ---: |
| `dependency_discovery` | inclusive task wall | 7331.573 ms |
| Discovery end -> Bootstrap begin | **untraced temporal gap** | 10939.394 ms |
| `minecraft_bootstrap` | inclusive task wall | 4234.562 ms |
| Bootstrap end -> gather begin | **untraced temporal gap** | 3191.482 ms |
| `fml_gather_and_initialize_mods` | inclusive task wall | 5472.826 ms |

These are not savings and are not a task sum.

The same artifact identifies the exact launcher line as ModLauncher `11.0.5+main.901c6ea8`, FML `4.0.43`, NeoForge `21.1.248`.

## Static causal map: what runs after Discovery

ModLauncher commit `901c6ea8` gives an exact ordering in `cpw.mods.modlauncher.Launcher.run`:

1. `TransformationServicesHandler.triggerScanCompletion(...)` calls every transformation service's `completeScan`.
2. returned GAME resources are collected and added to the GAME layer description.
3. `TransformationServicesHandler.initialiseServiceTransformers()` calls `TransformationServiceDecorator.gatherTransformers(...)`, which calls each service's `transformers()`.
4. scan results are offered to launch plugins.
5. the launch target is validated.
6. `buildTransformingClassLoader(...)` builds the GAME module layer around the transforming classloader.
7. the current thread context classloader is switched to that loader.
8. `LaunchServiceHandler.launch(...)` enters the chosen game launch target.
9. game-layer class loading/transformation and Mixin processing occur before the first observed `Bootstrap.bootStrap()` entry.

This matters because the previously attempted direct `ModLoader` target lives in `MC-BOOTSTRAP/fml_loader` and is not a reliable ordinary transformer target. The launcher classes above are already active before an ordinary transformation-service transformer can safely target them; broadening a matcher into that layer previously broke FML and is not repeated.

## Runtime corroboration from #203

`run-pack-benchmark/logs/latest.log` from artifact `10077639319` corroborates the static map without turning log timestamps into task scopes:

- the monotonic trace places dependency Discovery end at about `21:30:59.491` wall time;
- `21:30:59.952`: Connector `ModuleLayerMigrator` reports making `authlib` and `brigadier` transformable;
- `21:30:59.956`: `FMLServiceProvider` begins loading a coremod script engine;
- `21:31:02.834`: Mixin compatibility level is set to Java 21;
- `21:31:03.400`: `LaunchServiceHandler` logs the `forgeclientdev` launch;
- from `21:31:03.574` through at least `21:31:06.804`, the main thread emits many Mixin config/plugin/target/class-resolution messages, including C2ME, ModernFix, Lithium/Sodium compatibility decisions, missing-target probes, AsyncParticles class adjustment and MixinExtras initialization;
- `21:31:09.764`: the `Datafixer Bootstrap` thread reports completion of 229 DataFixer optimizations;
- `21:31:10.427`: `pool-8-thread-1` enters Minecraft Bootstrap (ModernFix marker), matching the structured Bootstrap edge.

Those timestamp separators show that the uncovered interval really contains post-discovery/module/coremod setup followed by launch/game-layer class transformation and Mixin work. They are not independently measured scopes and must not be added or interpreted as a savings budget.

## Minimum safe observable edge

No new launcher/FML class transformer is required. The existing BootOptim transformation service itself already owns a safe callback in the exact place needed: `EarlyStartupProbeService.transformers()`.

By the exact ModLauncher ordering above, this callback happens only after all `completeScan` callbacks have returned and GAME resources have been registered, while ModLauncher is in `initialiseServiceTransformers()`. It therefore provides a real post-scan SERVICE-layer edge without transforming `ModLoader`, `Launcher`, `TransformationServicesHandler`, or other already-active bootstrap classes.

Schema-v1 task spans are intentionally lexical to one thread. The exact pack enters the SERVICE callback on `main` but reaches Minecraft Bootstrap on `pool-8-thread-1`; therefore the long cross-thread interval must **not** be represented as one task. Doing so would violate the existing analyzer contract and would be rejected as an out-of-order close.

The final representation uses the existing schema without relaxing it:

- `bootoptim_transformation_service_transformers_callback`: a real same-thread task opened and closed entirely inside BootOptim's `transformers()` callback; dependency = `dependency_discovery` when present;
- `modlauncher_transformers_to_minecraft_bootstrap`: a `phase_begin/phase_end` pair spanning from that callback to the exact Bootstrap entry. Phase pairs may cross threads and are not included in task CPU/critical-path sums;
- `minecraft_bootstrap` depends on the already-closed callback task when present, falling back to `dependency_discovery` otherwise.

The phase name states literal observable boundaries. Its duration is an inclusive monotonic interval, not CPU, not an exclusive ModLauncher phase, and not a performance opportunity. It may include whichever service transformer registration follows BootOptim's callback, launch-plugin processing, launch-target validation, GAME transforming-classloader/module-layer construction, TCCL transition, launch-handler entry, game-layer classloading/Mixin work and other work before Bootstrap.

## Hosted validation

Validated runtime-code commit: `69d3ae9f3695dbdeee36e3145c5eac5233a3dae2`.

- Build/package run `34282585186`: **success**.
- normal Startup Benchmark `34282585112`: **success**.
- hosted exact-pack profile run `34282685222`: **success to `main_menu`**.
- exact-pack artifact `10078383863`, digest `sha256:9f7b1b42a5fa8dae0d9528d65ae57c279c2bd2db92cd3233194f121eb7fa3898`.
- result: `main_menu_ms=91220`, `mod_entrypoint_ms=31095`, `reload_to_fancymenu_finish_ms=41064`, `bootoptim_mixin_errors=0`.

The JSONL has exactly one `bootoptim.boottrace` v1 header (`mode=profile`, origin `hosted_exact_pack`, endpoint `main_menu`) and one summary. It contains 12 events: 5 `task_begin`, 5 `task_end`, 1 `phase_begin`, 1 `phase_end`. All task pairs are balanced and thread-lexical; the phase pair intentionally crosses `main` -> `pool-8-thread-1`. Sequence is contiguous, `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, trace `error=0`.

Observed dependency chain and threads:

- task 2 `dependency_discovery` on `main`;
- task 3 `bootoptim_transformation_service_transformers_callback` on `main`, dependency `[2]`, inclusive wall `0.638301 ms`;
- task 4 `minecraft_bootstrap` on `pool-8-thread-1`, dependency `[3]`, inclusive wall `4075.423190 ms`;
- task 5 `fml_gather_and_initialize_mods` on `Render thread`, dependency `[4]`, inclusive wall `5459.203273 ms`.

Same-run monotonic intervals around the requested boundary:

| Boundary | Type | Wall |
| --- | --- | ---: |
| `dependency_discovery` | inclusive task wall | 7477.854396 ms |
| Discovery end -> Bootstrap entry | direct monotonic temporal interval | 11501.525978 ms |
| Discovery end -> transition phase begin | **remaining unnamed temporal gap** | 1428.590455 ms |
| `modlauncher_transformers_to_minecraft_bootstrap` | inclusive monotonic phase interval | 10072.921316 ms |
| `minecraft_bootstrap` | inclusive task wall | 4075.423190 ms |
| Bootstrap end -> gather begin | **remaining unnamed temporal gap** | 2909.262013 ms |
| `fml_gather_and_initialize_mods` | inclusive task wall | 5459.203273 ms |

The direct Discovery-end -> Bootstrap-entry interval is reported from its two monotonic endpoints; the phase and residual gap describe internal boundaries in that same run. None of these intervals is a TTMM saving, CPU sum or A/B result. Do not compare the 11.502 s same-run interval numerically with #203's 10.939 s as a performance delta; they are separate hosted profile processes.

The first attempted exact-pack workflow `34282585045` was cancelled because updating the PR body retriggered the same concurrency group; its partial artifact contained only build-console output and no game trace. The immediately retriggered run `34282685222` is the valid profile evidence above.

## Safety and failure behavior

- `boot_optim.bootTrace.mode=off` still returns no diagnostic transformers and emits no transition phase/task.
- profile/development tracing uses the existing #200 writer/schema; no second writer, buffer or JSON format is introduced.
- the transition hooks catch `Throwable` and fail open.
- no executor, thread, callback, classloading order, module ordering, Mixin ordering, launch target or game behavior is modified.
- no GL/render work is moved.
- Bootstrap's existing strict transformer remains unchanged: exact class `net/minecraft/server/Bootstrap`, exact `bootStrap()V`, exactly one method, at least one normal return; missing/ambiguous/wrong targets remain rejected without bytecode mutation.

## Tests and disposition

`MinecraftBootstrapTraceTransformerTest` retains matcher/return/rejection coverage and adds structural ordering assertions that:

1. `MinecraftBootstrapTraceHooks.beginBootstrap()` closes the transition phase before calling `StructuredBootTrace.beginTask` for Bootstrap;
2. `EarlyStartupProbeService.transformers()` opens the transition/callback task before diagnostic transformer construction and closes the callback task on the same SERVICE thread afterwards.

Decision: **keep as a stacked diagnostic edge, not an optimization**. It safely attributes most of the pre-Bootstrap temporal gap to the concrete post-scan -> transformer registration -> launch/game-layer/Mixin transition without targeting untransformable `MC-BOOTSTRAP/fml_loader` classes. A remaining `1.428590 s` same-run pre-phase gap is deliberately unnamed. The next valid edge, if further attribution is required, must lie after dependency discovery and before BootOptim's `transformers()` callback (for example a proven `completeScan`/GAME-resource registration boundary) and must not be obtained by broadening a bootstrap-layer transformer matcher.

No A/B and no physical-laptop run were performed or requested.
