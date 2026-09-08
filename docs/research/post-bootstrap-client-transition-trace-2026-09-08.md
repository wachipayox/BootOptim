# Post-Bootstrap client transition trace — 2026-09-08

Status: **DIAGNOSTIC / HOSTED PROFILED / CLIENT HOOK NO-GO**

This branch adds no startup optimization and makes no TTMM claim. It subdivides the post-Bootstrap temporal gap observed by PR #203 between the normal return of `net.minecraft.server.Bootstrap.bootStrap()` and PR #202's begin hook immediately before `ModLoader.gatherAndInitializeMods(...)`.

## Base and stacking

Integration authority was refreshed as `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This diagnostic is intentionally stacked on PR #203 head `5995a3e7f914ae07483219ee1ccf947ed10b3365`, which is stacked on #202, #201 and trace-core #200. None of those diagnostics is integrated production code. After the lower stack is resolved, rebase so the net delta contains only the surviving `Bootstrap.validate()` edge, its tests, the gather dependency adjustment and this record.

## Exact client control map

NeoForge's client `Main` patch executes, in order:

1. `BackgroundWaiter.runAndTick(() -> Bootstrap.bootStrap(), ImmediateWindowHandler::renderTick)`;
2. `GameLoadTimesEvent.INSTANCE.setBootstrapTime(...)`;
3. `Bootstrap.validate()`;
4. `ClientModLoader.begin()`.

`BackgroundWaiter.runAndTick` submits `Bootstrap.bootStrap()` to a single-thread executor. The caller repeatedly executes the supplied tick, sleeps 50 ms, and then re-checks `Future.isDone()`. A worker-side bootstrap return therefore does not imply that the caller has already returned from `BackgroundWaiter`; an in-flight tick/poll sleep may still be outstanding.

`ClientModLoader.begin()` then registers the Log4j shutdown hook, obtains/updates the early loading screen, executes `LanguageHook.loadBuiltinLanguages()`, builds the periodic-tick callback and delegates synchronously to `CommonModLoader.begin(periodicTick, false)`. `CommonModLoader.begin` obtains its sync executor and directly invokes `ModLoader.gatherAndInitializeMods(...)`; PR #202 brackets that direct call.

The FML `BackgroundWaiter`/`ImmediateWindowHandler` implementation remains deliberately untransformed. PR #202 established that bootstrap-loaded FML classes are not a safe target for this diagnostic lane, and this branch does not broaden into `MC-BOOTSTRAP`.

## Surviving minimal hook

`MinecraftBootstrapTraceTransformer`, already proven transformable by #203 on exact `net.minecraft.server.Bootstrap`, now independently brackets exact `validate()V`:

- begin before the first executable instruction;
- end before every normal `RETURN`;
- fail closed if the method is absent, ambiguous or has no normal return.

The structured task is `minecraft_bootstrap_validate`. It depends on `minecraft_bootstrap` when available, and the existing gather task prefers `minecraft_bootstrap_validate` as its predecessor with fallback to bootstrap/Discovery if validation emitted no task.

`boot_optim.bootTrace.mode=off` still installs zero diagnostic transformers. Hook failures remain fail-open.

## Rejected ClientModLoader hook

A first exact attempt also targeted only `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V`. Its matcher required exactly one `LanguageHook.loadBuiltinLanguages()V`, exactly one inherited `begin(Runnable, boolean)` call, and the two anchors in source order. Synthetic tests verified exact order plus rejection of missing, duplicated, reversed and FML targets.

Hosted exact-pack profile `34282385838` reached `main_menu`, but emitted **no** `client_mod_loader_pre_gather` or `client_builtin_languages` pair. The trace otherwise remained clean and the independently targeted `Bootstrap.validate()` task did emit, proving profile mode and the transformation service were active. The client target was therefore discarded rather than broadening its matcher or moving into FML/bootstrap-loaded classes. The hosted evidence does not distinguish target availability from exact bytecode-anchor drift, so no stronger classloading claim is made.

This is the no-go boundary for this assignment: further subdivision of `ClientModLoader.begin()` through this transformation-service matcher is not justified without a new independently exact observable edge. No render/GL, scheduling, callback, thread or classloading behavior may be changed merely to expose it.

## Hosted validation

Validated runtime-code commit containing the surviving validation edge plus the deliberately fail-closed client attempt: `08b1651619de6463505709dbce1d3afbedc2c0c8`.

- Build/package run `34282385870`: **success**; Gradle build and bootstrap tests passed; packaged bootstrap validation passed. Artifact `10078141738`, sha256 `317daf58c858ca382fc8de24c41bb77ea2ab1644ad25d2a2656257346ea79099`.
- Normal Startup Benchmark `34282385846`: **success** to `main_menu` with normal trace-off configuration.
- Hosted exact-pack profile `34282385838`: **success** to `main_menu`.
- Exact-pack diagnostics artifact `10078243027`, sha256 `4e08208aab1593c7e6894ba98712568058e19681a1000f1a5f5dc6d44da1314f`.
- `main_menu_ms=91121`, `mod_entrypoint_ms=31254`, `reload_to_fancymenu_finish_ms=42138`, `bootoptim_mixin_errors=0`.

JSONL integrity:

- exactly one `bootoptim.boottrace` v1 header and one summary;
- `mode=profile`, `measurement_origin=hosted_exact_pack`, `endpoint=main_menu`;
- 5 balanced task-begin/task-end pairs;
- `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, `error=0`;
- task id 3 `minecraft_bootstrap` depends on dependency-discovery id 2;
- task id 4 `minecraft_bootstrap_validate` depends on bootstrap id 3;
- task id 5 `fml_gather_and_initialize_mods` depends on validation id 4.

Observed monotonic intervals in this hosted run:

| Boundary | Type | Wall |
| --- | --- | ---: |
| `minecraft_bootstrap` | inclusive task wall | 4646.526 ms |
| bootstrap end -> validate begin | untraced temporal gap | 1030.105 ms |
| `minecraft_bootstrap_validate` | inclusive task wall | 0.301 ms |
| validate end -> gather begin | untraced temporal gap | 2390.320 ms |
| `fml_gather_and_initialize_mods` | inclusive task wall | 5352.991 ms |
| bootstrap end -> gather begin | elapsed temporal interval | 3420.727 ms |

The run-to-run post-Bootstrap elapsed interval differs from #203's 3191.482 ms, so these absolute hosted values are not treated as an improvement/regression. The important attribution is structural: validation itself is negligible in this run; approximately 1.030 s lies before its entry and approximately 2.390 s lies after its return before the gather hook.

By source order, the first temporal gap contains the caller-side completion tail of `BackgroundWaiter.runAndTick` plus `GameLoadTimesEvent.setBootstrapTime(...)`. The second contains the path from the return of validation through `ClientModLoader.begin()` and the prefix of `CommonModLoader.begin` before the already-instrumented gather call. Those are temporal scopes, not measured CPU or optimization savings.

## Inclusive wall versus critical path

The structured task durations above are inclusive monotonic wall for their exact method/call boundaries. The two residual values are differences between monotonic endpoints, not trace tasks. They must not be summed as hypothetical savings, and the lack of a safe inner `ClientModLoader` hook means the 2.390 s residual cannot be decomposed into a task-sum or CPU attribution from this evidence.

The dependency IDs express causal order only. They do not turn the untraced gaps into named work or prove that every enclosed operation is independently optimizable.

## Safety and disposition

- no executor, scheduling, callback, thread, classloading order, render/GL path, resource reload, ModelBakery or gameplay behavior changed;
- no FML `MC-BOOTSTRAP` class was targeted;
- `off` still installs zero diagnostic transformers;
- diagnostic failures fail open;
- no laptop run is requested or relevant.

Disposition: keep the exact `Bootstrap.validate()` edge as a small diagnostic refinement if the stacked trace series is retained. Treat direct `ClientModLoader.begin()` transformation in this lane as **no-go from the validated exact matcher**; do not broaden it merely to make the gap disappear. This profile is observability evidence only, not TTMM improvement evidence.
