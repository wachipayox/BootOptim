# PR #99 registry attribution closure — 2026-09-04

Status: **PROFILED / MOD-CONTAINER ATTRIBUTION CLOSED AT MOD-SIDE BOUNDARY**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

PR: https://github.com/wachipayox/BootOptim/pull/99

Scope: explain why the first per-registry/per-container hook did not appear, obtain per-registry attribution without changing registry/mod-bus order, and decide whether the exact pack supports the hypothesis that `minecraft:block` / Decocraft dominates registry initialization. This work is diagnostic only. No registry, event-priority, ModContainer, executor or lifecycle ordering was changed, and BootOptim never parallelized or skipped registration work.

## Preserved outer critical-path evidence

The first PR #99 exact-pack smoke measured:

- `registry_initialization = 9,215.363 ms` critical-path wall before resource reload;
- Render-thread caller CPU `13.356 ms` because the caller waits for the sync task;
- process CPU `17,910 ms`, which is JVM-wide inclusive CPU and **not** exclusive registry CPU or recoverable wall.

Subsequent exact-pack runs varied materially, with the already-established outer boundary around `6.906–10.329 s`. The important stable result is structural: `GameData.postRegisterEvents()` accounts for practically all of that variable window, while `postNewRegistryEvent`, `unfreezeData` and `freezeData` are small.

## Why HEAD `ea3dfdd...` emitted `registry_count=0`

Tested commit: https://github.com/wachipayox/BootOptim/commit/ea3dfddf25c8185752ebb29be52e37e4069ce20e

Exact-pack run: https://github.com/wachipayox/BootOptim/actions/runs/33927834759

Artifact: `exact-pack-result-smoke-1`, ID `9957525387`.

This was **not** a stale SHA, unpushed code, disabled JVM property, or wrong FML descriptor:

- the artifact records head SHA `ea3dfddf25c8185752ebb29be52e37e4069ce20e`;
- outer `BOOTOPTIM_FML_LIFECYCLE` markers prove `-Dboot_optim.profileFmlLifecycle=true` was active;
- the log explicitly names BootOptim's redirect handler and the same target instruction.

The exact failure is a Mixin redirect conflict. ModernFix `feature.registry_event_progress.GameDataMixin` already redirects the same `GameData.postRegisterEvents()` call to `ModLoader.postEventWrapContainerInModOrder(Event)`. Both redirects were priority `1000`, so Mixin retained ModernFix's redirect and skipped BootOptim's redirect. The log therefore emitted `registry_count=0` and no per-registry rows.

The source used by the exact 5.27.14-era branch confirms that ModernFix's redirect executes registration sequentially as:

1. `EventPriority.values()` order;
2. `ModList.get().forEachModInOrder(...)`;
3. set active ModContainer;
4. `mc.acceptEvent(phase, event)`;
5. clear active ModContainer.

That source is represented by ModernFix commit `f8f1b092bf64d9ec29222d502d2e67f4304dc221` on the 1.21.1 branch at the release-era date.

## Observer attempt `c3cc193...`: safe but unreachable

Commit: https://github.com/wachipayox/BootOptim/commit/c3cc19364b1f547978dcff8398b1fa5fb3b2a828

Build: https://github.com/wachipayox/BootOptim/actions/runs/33929508380 — success.

Startup: https://github.com/wachipayox/BootOptim/actions/runs/33929508386 — success.

Exact pack: https://github.com/wachipayox/BootOptim/actions/runs/33929508387 — success.

Artifact: ID `9958088559`.

This revision removed BootOptim's redirect entirely and tried only lower-priority (`900`) `@Inject` observers on methods merged by ModernFix. It reached title with zero BootOptim Mixin errors/conflicts, but still produced:

- `registry_post_register_events = 9,426.186 ms` wall;
- caller CPU `7,765.819 ms`;
- process CPU `20,790 ms` inclusive;
- `registry_count = 0`.

Mixin 0.8.7's injection priority rule explains this result: an injector is not permitted to inject into a method already merged by a higher-priority mixin. ModernFix is priority 1000; the observer was priority 900. The absence of an error is expected because every diagnostic injection is `require=0` / fail-open.

## Final safe per-registry observer `21d112...`

Commit: https://github.com/wachipayox/BootOptim/commit/21d112af70bb150606228b6b15854883bb5c63b7

Build #1375: https://github.com/wachipayox/BootOptim/actions/runs/33930066260 — **success**.

Startup #393: https://github.com/wachipayox/BootOptim/actions/runs/33930066345 — **success**.

Exact-pack #157: https://github.com/wachipayox/BootOptim/actions/runs/33930066449 — **success / title reached**.

Exact artifact: `exact-pack-result-smoke-1`, ID `9958281257`, head SHA `21d112af70bb150606228b6b15854883bb5c63b7`.

This revision still does not redirect or call the bus. A priority-1100 `@Inject` inserts timer callbacks immediately before/after the original stock callsite; ModernFix's priority-1000 redirect subsequently remains responsible for dispatch. MixinExtras captures the already-existing `RegisterEvent` local. Therefore registry order, event-priority order and ModContainer order remain those of ModernFix/FML.

### Gate results

- Build: **PASS**.
- Startup: **PASS**.
- exact pack reaches main menu: **PASS**.
- BootOptim Mixin errors: **0 / PASS**.
- `registry_count > 0`: **125 / PASS**.
- register-event sum/residual coherence: **PASS**.
- ranking by registry: **PASS**.
- ranking by ModContainer: **FAIL** (`mod_count=0`).

Measured exact-pack registry accounting:

- `registry_initialization = 7,540.807 ms` critical-path wall;
  - Render caller CPU `6.601 ms`;
  - process CPU `17,050 ms` inclusive.
- `registry_post_register_events = 7,348.233 ms` wall;
  - sync-worker caller CPU `5,955.269 ms`;
  - process CPU `16,630 ms` inclusive.
- profiler aggregate `post_register_events = 7,348.323 ms`;
- sum of 125 measured `RegisterEvent` windows = **7,305.271 ms**;
- residual outside those windows = **43.052 ms** (~0.59%).

The subphase accounting is therefore internally coherent: nearly the entire `postRegisterEvents()` wall is inside ordered per-registry event dispatch.

### Registry ranking

`minecraft:block` is conclusively the dominant registry in this run:

- aggregate wall: **6,499.569 ms**;
- share of all measured RegisterEvent wall: **88.97%**;
- lifecycle sample wall: **6,499.274 ms**;
- caller CPU on `modloading-sync-worker`: **5,109.057 ms**;
- process CPU during the Block event: **15,080 ms inclusive**;
- classes loaded delta: `1,314`;
- JVM-wide JIT compilation delta: `5,174 ms`.

Second place is `minecraft:item` at **320.210 ms** / **4.38%**. No other registry is remotely comparable to Block in this smoke.

This confirms the **registry-level** hypothesis: `minecraft:block` is the principal source of the critical pre-reload registry wall in the measured exact-pack run.

## Why Decocraft is not resolved

The final artifact's dominant-mod summary is:

```text
registry=minecraft:block attributed_mod_wall_ms=0.000
unattributed_or_bus_overhead_ms=6499.569 mod_count=0
```

The priority-1100 observer around the original GameData callsite works, but the separate observer intended to inject around ModernFix's synthetic lambda `mc.acceptEvent(...)` does not execute in the exact artifact. Because it is fail-open (`require=0`), this produces no Mixin error.

The exact stable sub-cause of that synthetic-method miss is not exposed by Mixin's fail-open log output. What is empirically established is that normal mod-side injection can observe the pre-redirect GameData callsite, but this attempted observer cannot obtain per-ModContainer timings inside ModernFix's merged synthetic dispatch body.

Obtaining ownership from here would require crossing the boundary set for this investigation: intercepting/wrapping the existing `acceptEvent` dispatch, transforming already-loaded FML `ModContainer`/`ModLoader` earlier from bootstrap, or otherwise replacing/decorating dispatch. Those mechanisms are intentionally not attempted merely to get attribution.

Decocraft's own log inside the measured Block event reports:

- `RegisterEvent<Block>` at `23:38:25.417`;
- 635-model load completed in **981 ms**;
- Decocraft block registration completed in **1,027 ms**;
- `RegisterEvent<Item>` begins at `23:38:30.305`.

That proves Decocraft is a material participant, but its self-reported ~1.027 s does **not** explain the measured 6.499 s Block event and is not a substitute for per-container attribution. The remaining wall can include other ModContainers plus classloading/Mixin/JIT triggered by handlers.

Therefore the requested decision is:

- `minecraft:block` dominant registry: **CONFIRMED**.
- Decocraft dominant ModContainer within Block: **UNRESOLVED — neither confirmed nor excluded**.
- per-ModContainer attribution via the permitted normal mod-side observer: **CLOSED at this boundary**.

Do not cite the visible Block→Item gap as Decocraft ownership and do not infer the unexplained Block wall belongs to Decocraft.

## Closure / reopening rule

No optimization follows from PR #99. No cache, registry parallelization, event-bus parallelization, reorder, or gameplay deferral is proposed here.

This exact ModContainer-attribution lane should reopen only if one of the following changes the premise:

- NeoForge/ModernFix exposes a supported timing/listener hook around individual ModContainer registry handlers;
- an upstream diagnostic build can report per-container registry-event duration without changing dispatch semantics;
- a future pack/version removes ModernFix's registry-event redirect and exposes a stable observable stock call boundary.

Do not escalate to a bootstrap transformer or an event-bus wrapper solely to obtain this measurement. Gather/entrypoint attribution remains a separate candidate and was deliberately not resumed before closing this registry priority.
