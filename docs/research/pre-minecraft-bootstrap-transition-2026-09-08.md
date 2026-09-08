# Pre-Minecraft bootstrap transition trace — 2026-09-08

Status: **ACTIVE DIAGNOSTIC / NO PERFORMANCE CLAIM**

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

This matters because the previously attempted direct `ModLoader` target lives in `MC-BOOTSTRAP/fml_loader` and is not a reliable ordinary transformer target. The launcher classes above are themselves already active before a transformation-service transformer can safely target them; broadening a matcher into that layer previously broke FML and is not repeated.

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

No new launcher/FML class transformer is required. The existing BootOptim transformation service itself already owns a safe callback in the exact place needed:

`EarlyStartupProbeService.transformers()`

By the exact ModLauncher ordering above, this callback happens only after all `completeScan` callbacks have returned and GAME resources have been registered, while ModLauncher is in `initialiseServiceTransformers()`. It therefore provides a real post-scan SERVICE-layer edge without transforming `ModLoader`, `Launcher`, `TransformationServicesHandler`, or other already-active bootstrap classes.

The new trace-only task is deliberately named for its literal boundaries:

`modlauncher_transformers_to_minecraft_bootstrap`

- begin: entry into BootOptim's existing `transformers()` callback in trace mode;
- dependency: `dependency_discovery` task if present;
- end: immediately before `minecraft_bootstrap` begins;
- `minecraft_bootstrap` now depends on this task when present and falls back to `dependency_discovery` otherwise.

The interval is inclusive monotonic wall between those exact observable edges. It includes whichever remaining service transformer registration follows BootOptim's callback, launch-plugin processing, launch-target validation, GAME transforming-classloader/module-layer construction, TCCL transition, launch-handler entry, and subsequent game-layer classloading/Mixin work before Bootstrap. It is **not** named as CPU time, a ModLauncher-exclusive phase, or a performance opportunity.

A residual temporal gap may remain between `dependency_discovery` end and this new task begin. That residual must remain an unnamed gap unless another semantically exact edge is proven.

## Safety and failure behavior

- `boot_optim.bootTrace.mode=off` still returns no diagnostic transformers and emits no transition task.
- profile/development tracing uses the existing #200 writer/schema; no second writer, buffer or JSON format is introduced.
- the transition hooks catch `Throwable` and fail open.
- no executor, thread, callback, classloading order, module ordering, Mixin ordering, launch target or game behavior is modified.
- no GL/render work is moved.
- Bootstrap's existing strict transformer remains unchanged: exact class `net/minecraft/server/Bootstrap`, exact `bootStrap()V`, exactly one method, at least one normal return; missing/ambiguous/wrong targets remain rejected without bytecode mutation.

## Tests

`MinecraftBootstrapTraceTransformerTest` retains matcher/return/rejection coverage and adds structural ordering assertions that:

1. `MinecraftBootstrapTraceHooks.beginBootstrap()` closes `ModLauncherTransitionTraceHooks` before calling `StructuredBootTrace.beginTask` for Bootstrap;
2. `EarlyStartupProbeService.transformers()` starts the transition before constructing/returning the diagnostic Bootstrap transformer.

Hosted validation and final JSONL inspection are required before this entry can be closed. No A/B or laptop run is justified because this is diagnostic-only instrumentation.
