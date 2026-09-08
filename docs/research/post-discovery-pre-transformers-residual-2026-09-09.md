# Post-discovery -> transformation-service transformers residual — 2026-09-09

Status: **PROFILED / diagnostic-only pending hosted validation**

Stack: #200 -> #201 -> #202 -> #203 -> #205 -> Agent 73 branch.
Integration authority at branch time: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Scope

This investigation covers only the residual that #205 exact-pack profile `34282685222` measured between the final `dependency_discovery` task end and BootOptim's own `EarlyStartupProbeService.transformers()` callback: **1428.590455 ms** in that one hosted profile.

It explicitly does not cover #205's later `modlauncher_transformers_to_minecraft_bootstrap` phase, Minecraft Bootstrap, ClientModLoader/CommonModLoader, resource reload, render/GL, gameplay, or any optimization A/B.

## Static control-flow map

For ModLauncher 11.x, `Launcher.run` executes the relevant portion in this order:

1. `TransformationServicesHandler.triggerScanCompletion(...)` iterates every transformation service and calls `ITransformationService.completeScan(IModuleLayerManager)`.
2. Resources returned for `Layer.GAME` are registered into the GAME layer.
3. `TransformationServicesHandler.initialiseServiceTransformers()` iterates services and invokes each service's `transformers()` callback.

The existing `dependency_discovery` end marker is not a ModLauncher boundary. It is BootOptim's last-priority `IDependencyLocator` callback inside FML discovery and therefore can occur before FML's surrounding transformation-service `completeScan` has returned.

`EarlyStartupProbeService` itself is already a ModLauncher `ITransformationService`, so its own `completeScan(IModuleLayerManager)` default-method slot is a safe observable SERVICE callback. Importantly, that callback is only **BootOptim's position inside ModLauncher's complete-scan iteration**. ModLauncher stores transformation services in a `HashMap`; therefore BootOptim's callback is not named or treated as “all completeScan callbacks finished”. No phase below attributes work to global complete-scan closure, GAME registration, or another service without a literal edge proving that attribution.

## Hook design

The diagnostic adds no launcher/FML transformer. It does not touch `MC-BOOTSTRAP/fml_loader` and does not broaden any matcher.

Two schema-v1 **phase** pairs partition the literal residual around BootOptim's own existing service callback:

- `dependency_discovery_to_bootoptim_complete_scan_callback`: begins immediately after the structured `dependency_discovery` task ends; ends when ModLauncher invokes BootOptim's `completeScan(...)`.
- `bootoptim_complete_scan_callback_to_transformers`: begins at that same BootOptim `completeScan(...)` callback and ends immediately before #205 opens `modlauncher_transformers_to_minecraft_bootstrap` in BootOptim's `transformers()` callback.

They are phases rather than tasks because the intervening launcher/FML work is not owned by BootOptim and should not be inserted into producer CPU, inclusive task-wall sums, or the task DAG's critical-path calculation. Their values are inclusive temporal intervals between literal observable edges only.

`bootTrace.mode=off` records neither phase and still returns no diagnostic transformers. `completeScan(...)` returns the same empty resource list as the interface default. All hook methods fail open.

## Safety invariants

- No classloading target or transformer matcher is changed.
- No scheduling, executor, thread, callback order, module/resource ordering, render/GL, or gameplay behavior is changed.
- No direct `net.neoforged.fml.ModLoader` instrumentation is attempted.
- The callback names deliberately encode `bootoptim_complete_scan_callback` rather than claiming a global ModLauncher/FML phase boundary.

## Tests

Structural bytecode tests require:

1. `dependency_discovery` task end precedes the first residual phase begin;
2. BootOptim `completeScan(...)` invokes only the literal intermediate marker;
3. BootOptim `transformers()` closes the residual before #205 opens its later callback-to-Bootstrap phase.

## Measurement semantics

The inherited `dependency_discovery` event is a same-thread task with inclusive monotonic wall. The two new events are phase intervals and are not task CPU or critical-path task savings. #205's later transition remains its own inclusive cross-thread phase. None of these values may be summed as optimization opportunity.

## Hosted validation

Pending. Required gates are package/build, normal startup with trace default/off, and hosted exact-pack smoke with `boot_optim.bootTrace.mode=profile`, `origin=hosted_exact_pack`, endpoint `main_menu`. The JSONL must contain one valid header/summary, balanced phase pairs, contiguous sequence, zero drops/flush/sink failures/errors, and the expected literal ordering.

## Reopening / stronger attribution criterion

Do not reinterpret either phase as “remaining completeScan” or “GAME registration”. Stronger attribution requires a stable public callback/API at one of these exact ModLauncher boundaries, or a version-pinned launcher-owned instrumentation point proven safe without transforming bootstrap/FML implementation classes. A future ModLauncher API exposing an after-all-completeScan or after-GAME-registration callback would satisfy that criterion.
