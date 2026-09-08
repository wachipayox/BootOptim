# Post-discovery -> transformation-service transformers residual — 2026-09-09

Status: **PROFILED / diagnostic-only**

Stack: #200 -> #201 -> #202 -> #203 -> #205 -> Agent 73 branch.
Integration authority at branch time: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Scope

This investigation covers only the residual that #205 exact-pack profile `34282685222` measured between the final `dependency_discovery` task end and BootOptim's own `EarlyStartupProbeService.transformers()` callback: **1428.590455 ms** in that hosted profile.

It explicitly does not cover #205's later `modlauncher_transformers_to_minecraft_bootstrap` phase, Minecraft Bootstrap, ClientModLoader/CommonModLoader, resource reload, render/GL, gameplay, or any optimization A/B.

## Static control-flow map

For the ModLauncher line used by the exact pack, `Launcher.run` executes the relevant portion in this order:

1. `TransformationServicesHandler.triggerScanCompletion(...)` iterates transformation services and calls `ITransformationService.completeScan(IModuleLayerManager)`.
2. Resources returned for `Layer.GAME` are registered into the GAME layer.
3. `TransformationServicesHandler.initialiseServiceTransformers()` iterates services and invokes each service's `transformers()` callback.

The existing `dependency_discovery` end marker is not a ModLauncher boundary. It is BootOptim's last-priority `IDependencyLocator` callback inside FML discovery and therefore can occur before FML's surrounding transformation-service `completeScan` has returned.

`EarlyStartupProbeService` is already a ModLauncher `ITransformationService`, so its own `completeScan(IModuleLayerManager)` slot is a safe observable SERVICE callback. Importantly, that callback is only **BootOptim's position inside ModLauncher's complete-scan iteration**. ModLauncher stores transformation services in a `HashMap`; therefore BootOptim's callback is not named or treated as “all completeScan callbacks finished”. No phase below attributes work to global complete-scan closure, GAME registration, or another service without a literal edge proving that attribution.

## Hook design

The diagnostic adds no launcher/FML transformer. It does not touch `MC-BOOTSTRAP/fml_loader` and does not broaden any matcher.

Two schema-v1 **phase** pairs partition the literal residual around BootOptim's own existing service callback:

- `dependency_discovery_to_bootoptim_complete_scan_callback`: begins immediately after the structured `dependency_discovery` task ends; ends when ModLauncher invokes BootOptim's `completeScan(...)`.
- `bootoptim_complete_scan_callback_to_transformers`: begins at that same BootOptim `completeScan(...)` callback and ends immediately before #205 opens `modlauncher_transformers_to_minecraft_bootstrap` in BootOptim's `transformers()` callback.

They are phases rather than tasks because the intervening launcher/FML work is not owned by BootOptim and must not enter producer CPU, inclusive task-wall sums, or the task DAG's critical-path calculation. Their values are inclusive temporal intervals between literal observable edges only.

`bootTrace.mode=off` records neither phase and still returns no diagnostic transformers. `completeScan(...)` returns the same empty resource list as the interface default. All hook methods fail open.

## Safety invariants

- No classloading target or transformer matcher is changed.
- No scheduling, executor, thread, callback order, module/resource ordering, render/GL, or gameplay behavior is changed.
- No direct `net.neoforged.fml.ModLoader` instrumentation is attempted.
- Callback/phase names deliberately encode `bootoptim_complete_scan_callback` rather than claiming a global ModLauncher/FML phase boundary.

## Tests

Structural bytecode tests require:

1. `dependency_discovery` task end precedes the first residual phase begin;
2. BootOptim `completeScan(...)` invokes the literal intermediate marker;
3. BootOptim `transformers()` closes the residual before #205 opens its later callback-to-Bootstrap phase.

Build `34284597449` passed, including Gradle tests and packaged-bootstrap validation.
Normal Startup Benchmark `34284597435` passed to the main menu with trace/default behavior.

## Hosted exact-pack profile

Runtime/profile commit: `b99d96c53ee702ddde15e2fc0c9a33340f24017e`.
Exact-pack run: `34284597581`, smoke/profile, **success** to `main_menu`.
Artifact: `10079086603`, digest `sha256:b8a428d7d12998e034fce830fc1761bcfe38de669d57606bcabdb79c4422e10b`.

Result summary:

- `main_menu_ms=88130`
- `mod_entrypoint_ms=29651`
- `reload_to_fancymenu_finish_ms=40674`
- `bootoptim_mixin_errors=0`
- atlas `8192x8192x2`

JSONL validity:

- exactly one schema-v1 header and one summary;
- 16 contiguous events (`seq=0..15`): 5 balanced task pairs + 3 balanced phase pairs;
- all task begin/end pairs remain same-thread lexical;
- `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, trace `error=0`;
- the new phases occur on `main` and are ordered exactly between `dependency_discovery` task end and #205's later transition begin.

Same-run monotonic intervals:

| Boundary | Type | Inclusive wall |
| --- | --- | ---: |
| `dependency_discovery` | task | 7207.094864 ms |
| `dependency_discovery_to_bootoptim_complete_scan_callback` | phase | **601.751419 ms** |
| `bootoptim_complete_scan_callback_to_transformers` | phase | **794.874557 ms** |
| Discovery task end -> #205 transition begin | direct temporal residual | **1397.434018 ms** |
| `modlauncher_transformers_to_minecraft_bootstrap` | phase, outside this assignment | 8531.219221 ms |

The two new phase intervals sum to 1396.625976 ms; the approximately 0.808 ms difference from the direct residual is only the tiny event/callback-edge spacing between the literal markers, not an attributed phase.

The result closes the observability question but **does not identify 601.8 ms as “completeScan” or 794.9 ms as “GAME registration”**. The first interval includes whatever work occurs after FML dependency discovery until BootOptim's position in the complete-scan iteration. The second includes whatever remains from that literal callback until BootOptim's transformers callback. Without a global post-completeScan or post-GAME-registration API edge, stronger labels would be invented.

## Measurement semantics / disposition

`dependency_discovery` is a same-thread task with inclusive monotonic wall. The two new residual measurements are phases and are not task CPU, task-wall sums, or critical-path task savings. #205's later transition remains its own inclusive cross-thread phase. None of these values may be summed as an optimization opportunity, and no A/B or physical-laptop run is justified by this diagnostic.

Disposition: **keep as stacked diagnostic only**. The residual is safely partitioned at the only additional public SERVICE callback available to BootOptim, but the two pieces remain attribution-neutral.

## Reopening / stronger attribution criterion

Do not reinterpret either phase as “remaining completeScan” or “GAME registration”. Stronger attribution requires a stable public callback/API at one of those exact ModLauncher boundaries, or a version-pinned launcher-owned instrumentation point proven safe without transforming bootstrap/FML implementation classes. A future ModLauncher API exposing an after-all-completeScan or after-GAME-registration callback satisfies the criterion. A renewed broad matcher against `MC-BOOTSTRAP/fml_loader` does not.
