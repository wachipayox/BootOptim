# Flywheel ShaderSources prepare-boundary probe — 2026-09-08

Status: **PROFILED / probe default-off; runtime gate pending**.

Base refreshed before branching: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Continuation of Agent 64 audit commit `5dc05edcfd08a5157f16006029f20c6d388c3312`.

## Scope and hypothesis

This branch tests only Flywheel 1.0.6 `FlwProgramsReloader`. PR #184 measured its hosted exclusive ordered slot at `166.733 ms`; Flywheel's own log nested inside that slot reported `Loaded 106 shader sources in 105.736 ms`. The 105.736 ms is attribution only, not a savings claim.

Upstream 1.0.6 source establishes the exact synchronous call chain:

- `FlwProgramsReloader.onResourceManagerReload(ResourceManager)` calls `FlwPrograms.reload(manager)` and then `NoiseTextures.reload(manager)` (upstream commit `72af7b915a555a8e23f3c2638652a9f482a36deb`, blob `e3350f108b0e6c345b9e3dd7dc94ce045462bf4e`).
- `FlwPrograms.reload(ResourceManager)` resets/replaces Flywheel program holders, executes `new ShaderSources(resourceManager)`, publishes it as `FlwPrograms.SOURCES`, then rebuilds lazy program compiler state.
- `ShaderSources(ResourceManager)` enumerates `flywheel/*`, opens UTF-8 resources and parses recursive includes into its cache; its 1.0.6 blob is `4599871ae3ae59a48abd3c2bbe9073033cdc259e`.
- `NoiseTextures.reload` remains GL/render-thread work and is deliberately untouched.

Minecraft 1.21.1 `ResourceManagerReloadListener` already implements `PreparableReloadListener`, but its default `reload` does no preparation: it waits for the stock preparation barrier and then executes `onResourceManagerReload` on the game executor. Therefore the current `ShaderSources` constructor is expected to occur after `allPreparations` and inside Flywheel's ordered apply/commit slot.

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

`flywheel_commit_*` is injected around the existing `onResourceManagerReload`; the constructor markers are injected into the one stock `ShaderSources(ResourceManager)` constructor. Both log `System.identityHashCode(ResourceManager)` plus a per-Flywheel reload generation. The constructor additionally records the dynamic stack callsite, expected to resolve to `dev.engine_room.flywheel.backend.compile.FlwPrograms#reload`.

`allPreparations` / `allDone` observe the existing `SimpleReloadInstance` futures only; no listener/task timings are summed. In probe mode, benchmark auto-exit is delayed from title opening to the first already-existing `Window.updateDisplay()` completion so `main_menu_presented` is real rather than inferred. `first_world_render` is armed at the same presentation boundary when `Minecraft.level != null`; standard title-only exact-pack smoke is expected not to observe it because the benchmark exits after the presented title.

## Candidate injectability decision

The preparation computation itself is GL-free, but Flywheel 1.0.6 exposes no supported prepared-state handoff. A BootOptim candidate would have to do at least one of the following:

1. redirect the third-party `NEW ShaderSources(ResourceManager)` bytecode and return a detached Flywheel instance, coupling BootOptim to Flywheel's concrete internal type/constructor descriptor; or
2. reproduce/overwrite `FlwPrograms.reload` sufficiently to publish a prepared instance, duplicating owner-controlled static reset/publication order; or
3. construct a second `ShaderSources` in commit, which defeats attribution and the intended saving.

All three violate the reopening safety contract from the prior audit unless a stable upstream prepared-state API exists. The probe therefore intentionally stops before a behavior-changing candidate. Runtime evidence can confirm the size/location/identity premise, but not make the unsupported handoff safe.

## Validation contract

Build must pass the static safety tests under `tools/laptop-bench/test_flywheel_shader_sources_probe.py` and `./gradlew build`. Hosted exact-pack smoke with the property enabled must show:

- Flywheel 1.0.6 activation;
- zero BootOptim/Mixin errors;
- one stock constructor per Flywheel commit generation;
- matching ResourceManager identity at commit and constructor;
- dynamic callsite `FlwPrograms#reload`;
- `allPreparations` before the Flywheel constructor/commit and `allDone` after it;
- `main_menu_presented` reached using an existing display update;
- normal resource selection / atlas contract.

This is a diagnostic smoke, not an optimization A/B. A 3x A/B is not justified unless a separate compatibility-safe candidate exists. No physical run is requested from this branch.

## Decision rule

If hosted smoke confirms the expected ordering/identity, the source-level hypothesis is **confirmed but the BootOptim runtime candidate remains NO-GO** because the only consumption handoffs are unsupported third-party ABI/bytecode interposition or duplicated owner logic. Reopen only if Flywheel exposes a stable `prepare(ResourceManager) -> prepared state` / owner-thread `commit(prepared)` API, or if a future exact version moves the CPU parse into a supported preparable listener itself.
