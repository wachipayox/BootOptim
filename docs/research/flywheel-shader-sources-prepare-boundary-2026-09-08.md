# Flywheel ShaderSources prepare-boundary probe — 2026-09-08

Status: **PROFILED / hypothesis confirmed; BootOptim runtime candidate NO-GO**.

Base refreshed before branching: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Continuation of Agent 64 audit commit `5dc05edcfd08a5157f16006029f20c6d388c3312`.
Diagnostic PR: #197, closed without merge after hosted validation.

## Scope and hypothesis

This branch tests only Flywheel 1.0.6 `FlwProgramsReloader`. PR #184 measured its hosted exclusive ordered slot at `166.733 ms`; Flywheel's own log nested inside that slot reported `Loaded 106 shader sources in 105.736 ms`. The 105.736 ms is attribution only, not a savings claim.

Upstream 1.0.6 source establishes the exact synchronous call chain:

- `FlwProgramsReloader.onResourceManagerReload(ResourceManager)` calls `FlwPrograms.reload(manager)` and then `NoiseTextures.reload(manager)` (upstream commit `72af7b915a555a8e23f3c2638652a9f482a36deb`, blob `e3350f108b0e6c345b9e3dd7dc94ce045462bf4e`).
- `FlwPrograms.reload(ResourceManager)` resets/replaces Flywheel program holders, executes `new ShaderSources(resourceManager)`, publishes it as `FlwPrograms.SOURCES`, then rebuilds lazy program compiler state.
- `ShaderSources(ResourceManager)` enumerates `flywheel/*`, opens UTF-8 resources and parses recursive includes into its cache; its 1.0.6 blob is `4599871ae3ae59a48abd3c2bbe9073033cdc259e`.
- `NoiseTextures.reload` remains GL/render-thread work and is deliberately untouched.

Minecraft 1.21.1 `ResourceManagerReloadListener` already implements `PreparableReloadListener`, but its default `reload` does no preparation: it waits for the stock preparation barrier and then executes `onResourceManagerReload` on the game executor. Therefore the current `ShaderSources` constructor was expected to occur after `allPreparations` and inside Flywheel's ordered apply/commit slot.

## Diagnostic implementation

Opt-in only:

```text
-Dboot_optim.profileFlywheelShaderSources=true
```

Exact Flywheel version gate: `1.0.6`. Optional targets use `@Pseudo`; absent or different Flywheel versions fail open and log disabled status. With the property absent, the helper returns before recording anything.

The probe does **not**:

- construct a detached or second `ShaderSources`;
- replace/register a resource listener;
- wrap an executor or create a pool;
- cache any resource/source object across reloads;
- redirect `FlwPrograms.reload` or `NoiseTextures.reload`;
- move GL, `DynamicTexture`, RenderSystem, publication or callbacks off the owner thread.

Markers:

```text
BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_commit_start
BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_sources_prepare_start
BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_sources_prepare_end
BOOTOPTIM_FLYWHEEL_PROBE marker=flywheel_commit_end
BOOTOPTIM_FLYWHEEL_PROBE marker=allPreparations
BOOTOPTIM_FLYWHEEL_PROBE marker=allDone
BOOTOPTIM_FLYWHEEL_PROBE marker=main_menu_presented
BOOTOPTIM_FLYWHEEL_PROBE marker=first_world_render
```

`flywheel_commit_*` is injected around the existing `onResourceManagerReload`; the constructor markers are injected into the one stock `ShaderSources(ResourceManager)` constructor. Both log `System.identityHashCode(ResourceManager)` plus a per-Flywheel reload generation. The constructor additionally records the dynamic stack callsite.

`allPreparations` / `allDone` observe the existing `SimpleReloadInstance` futures only; no listener/task timings are summed. In probe mode, benchmark auto-exit is delayed from title opening to the first already-existing `Window.updateDisplay()` completion so `main_menu_presented` is real rather than inferred. `first_world_render` is armed at the same presentation boundary when `Minecraft.level != null`; standard title-only exact-pack smoke does not exercise a world and therefore does not emit that marker.

## Hosted validation

Validated code head: `3d5e771de9bdd480fdb4dd1abe0483dc7071e86d`.

- Build Actions `34237006850`: **success**. Static probe contract tests and `./gradlew build` passed.
- Startup Actions `34237006934`: **success**.
- Hosted exact-pack Actions `34237006951`: **success** including benchmark and aggregate.
- Exact-pack result artifact: `10060473774`, digest `sha256:b1e77abc6e2bdbdba5abaf61c96905ef573caa403fdd3a67c8187285ae297190`.
- Exact-pack summary artifact: `10060482516`, digest `sha256:dc15881154fe2c1740eb19a092338ed0471ac5a453aebebf4b6e304c3ce6d448`.
- Resource selection: valid; one effective reload; block atlas `8192x8192x2`; BootOptim Mixin errors `0`.
- Process-origin `main_menu`: `91.825 s`. This is TTMM-to-opening, not `main_menu_presented`.

The successful smoke emitted, in order:

```text
allPreparations   reload_generation=1 since_attach_ms=28730.013
flywheel_commit_start generation=1 manager_id=480634098 thread="Render thread"
flywheel_sources_prepare_start generation=1 manager_id=480634098 manager_match=true
  callsite=dev.engine_room.flywheel.backend.compile.FlwPrograms#reload:30 thread="Render thread"
flywheel_sources_prepare_end generation=1 manager_id=480634098 manager_match=true wall_ms=45.013
flywheel_commit_end generation=1 manager_id=480634098 manager_match=true
  shader_sources_constructors=1 wall_ms=73.921 thread="Render thread"
allDone           reload_generation=1 since_attach_ms=41746.929
main_menu_presented thread="Render thread"
```

Classification:

| measurement | value | type / interpretation |
| --- | ---: | --- |
| `ShaderSources` constructor | `45.013 ms` | inclusive hosted wall nested inside the Flywheel commit; CPU-pure source enumeration/read/include parse, **not** a demonstrated saving |
| Flywheel commit | `73.921 ms` | inclusive hosted wall around stock `FlwPrograms.reload + NoiseTextures.reload`; contains the constructor and therefore must not be added to it |
| `allPreparations` | `28.730013 s` | monotonic wall since this `SimpleReloadInstance` probe attached; confirms the global preparation barrier precedes Flywheel work |
| `allDone` | `41.746929 s` | monotonic wall from the same attach origin; global reload completion |
| post-preparation reload tail | `13.016916 s` | critical reload wall `allDone - allPreparations`; not a sum of listeners |
| `main_menu` | `91.825 s` | process-origin TTMM-to-opening from exact-pack result JSON |
| `main_menu_presented` | observed | actual existing `Window.updateDisplay()` completion; logger timestamp was about `1.698 s` after the `main_menu` log, so that delta is only coarse log-wall evidence, not a process-origin benchmark metric |
| first-world render | not exercised | title-only hosted smoke exits after presented title; marker is instrumented but no world was entered, so no first-world latency claim is possible |

The ordering and identity premise is therefore confirmed: the one stock `ShaderSources(ResourceManager)` construction is inside the stock Flywheel commit, on the Render thread, after the global preparation barrier, with the exact same `ResourceManager` object and dynamic callsite `FlwPrograms#reload:30`.

The successful smoke's `45.013 ms` constructor is materially smaller than #184's nested Flywheel log `105.736 ms`; this reinforces that the old value is an attribution observation, not a stable savings ceiling. No performance conclusion is drawn from the count of 106 sources or from either isolated constructor duration.

### Invalid first attempt

Exact-pack run `34235781853` is **invalid for runtime attribution/performance**. It exposed a probe implementation error: the constructor `HEAD` injector handler was non-static, and Mixin rejected it before `super()` with `InvalidInjectionException`. The run was used only to diagnose the probe. Commit `4e2a104d04837c9045445a1fabd9f8d31491fd01` made both constructor handlers static, and `3d5e771d...` added a regression assertion before the successful smoke above. No metric from the failed run is used as optimization evidence.

## Candidate injectability decision

The preparation computation itself is GL-free, but Flywheel 1.0.6 exposes no supported prepared-state handoff. A BootOptim candidate would have to do at least one of the following:

1. redirect the third-party `NEW ShaderSources(ResourceManager)` bytecode and return a detached Flywheel instance, coupling BootOptim to Flywheel's concrete internal type/constructor descriptor;
2. reproduce/overwrite `FlwPrograms.reload` sufficiently to publish a prepared instance, duplicating owner-controlled static reset/publication order; or
3. construct another `ShaderSources` during commit, which duplicates the work and invalidates the intended saving.

The probe establishes that replacing the listener is unnecessary for observation, but it does **not** create a stable consumption boundary. The object's consumer is an internal constructor call inside owner-controlled `FlwPrograms.reload`; no public/interface-level hook accepts a prepared source set. Exact-version gating can detect version drift, but it cannot make a bytecode `NEW` redirect or duplicated static publication order into a supported compatibility contract.

Therefore no behavior-changing candidate was implemented. In particular, `NoiseTextures.reload`, `DynamicTexture`, RenderSystem calls, program-holder reset/publication and all owner-thread ordering remain entirely stock.

## Decision

**Hypothesis confirmed; runtime optimization line closed / NO-GO.**

A 3x A/B is intentionally not run because there is no candidate whose behavior differs safely from stock. Running probe on/off three times would measure observer overhead, not startup optimization. No physical laptop run is requested.

Reopen only if one material premise changes:

- Flywheel exposes a stable `prepare(ResourceManager) -> prepared state` and owner-thread `commit(prepared)` API; or
- a future exact Flywheel version itself adopts a preparable listener and leaves measurable CPU parsing after the barrier through a supported hook; or
- upstream offers another stable owner-controlled factory/injection point for supplying a prepared `ShaderSources` without redirecting `NEW`, duplicating construction, caching across reloads, or reproducing publication logic.

Until then, the demonstrated safe BootOptim saving from this boundary is **0 ms**. The measured 45–106 ms source parse remains a cost attribution, not a recoverable critical-path claim.
