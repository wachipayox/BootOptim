# Xaero World Map deferred Stage 2/2 — 2026-09-04

Status: **ACTIVE DIAGNOSTIC / FIRST SMOKE INVALID FOR ATTRIBUTION / NO DEFER OR PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Why this lane exists

A hosted exact-pack run (`33917611497`) exposed a mod-specific startup span that was not in the existing BootOptim research fronts:

- exact artifact: Xaero's World Map `1.41.0` with XaeroLib `1.1.15`;
- `20:45:57.056` — `Loading Xaero's World Map - Stage 1/2` on `Worker-ResourceReload-1`;
- `20:45:59.460` — `Loading Xaero's World Map - Stage 2/2` on `Render thread`;
- `20:45:59.467` — `New world map region cache hash code: ...`;
- `20:46:00.415` — player-tracker registration and optional-mod checks complete;
- `20:46:00.486` — NeoForge reports `Mod 'xaeroworldmap' took 1.026 s to run a deferred task.`

FancyModLoader's `DeferredWorkQueue` runs each queued task synchronously through its supplied executor, sets that task owner's `ModContainer` active, executes `ti.task.run()`, clears the active container in `finally`, and only then emits the slow-task warning when the elapsed wall crosses one second.

The stock `1.026 s` warning is therefore an inclusive wall measurement for one owner-tagged deferred runnable in that run. It is **not** a 1.026 s recoverable-TTMM claim.

## Ownership / source boundary

The exact Xaero World Map artifact is not a repository controlled by the authenticated `wachipayox` account. Therefore:

- do not propose a direct Xaero edit as if this project owns it;
- any BootOptim compatibility must be exact-version gated, fail-open, and semantics-preserving;
- source-level conclusions must come from runtime stacks / inspected bytecode or public source that actually matches the runtime shape.

## First diagnostic smoke — rejected attribution boundary

PR #100 initially used the Stage 2/2 log marker to start a 5 ms Render-thread sampler and attempted to stop it by parsing NeoForge's slow-task warning text.

Exact-pack run `33924496490` reached title with zero BootOptim Mixin errors, but the profiler completed as:

```text
BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=timeout wall_ms=5003 samples=866
```

That result is **invalid for Xaero deferred-task attribution**.

The sampler began at `22:15:35.128` when Stage 2/2 was logged. No `xaeroworldmap` slow-task warning appeared in this instrumented run, so the profiler ran to its five-second safety timeout. By then the sampled Render thread had moved into unrelated later work. Representative contaminated counts included hundreds of `glBlitFramebuffer` samples and dozens of `Net.poll` samples.

Consequences:

- the 5.003 s window must not be described as Xaero task wall;
- its aggregate `top_leaf` / `top_xaero` rows must not be used to choose an optimization mechanism;
- absence of a warning is expected whenever the individual FML task falls below the one-second warning threshold, so warning text is intrinsically unsuitable as a precise end boundary;
- log formatting / async logging behavior is an unnecessary dependency for this attribution.

This failed diagnostic is retained here so the same marker-to-warning boundary is not repeated.

## Corrected diagnostic boundary

Property remains:

```text
-Dboot_optim.profileXaeroDeferredTask=true
```

The corrected diagnostic no longer installs a Log4j filter and no longer parses either Xaero or FML log text.

### Stock FML boundary used

`DeferredWorkQueue` performs this stock sequence for each queued task:

1. `ModLoadingContext.get().setActiveContainer(ti.owner)`;
2. attaches exception handling to the task future;
3. `ti.task.run()`;
4. in `finally`, `ModLoadingContext.get().setActiveContainer(null)`.

PR #100 now observes the existing `ModLoadingContext.setActiveContainer(...)` calls. A client diagnostic mixin accepts a boundary only when:

- entering: the supplied container has mod id `xaeroworldmap`; and
- leaving: the Xaero sampler is active on the current thread; and
- in both cases the **direct caller class** is exactly `net.neoforged.fml.DeferredWorkQueue`.

The direct-caller guard prevents unrelated FML/event-bus owner transitions from being mistaken for this task. It also prevents a nested owner change inside Xaero code from ending the sample unless that change itself is directly made by `DeferredWorkQueue`.

The start boundary is immediately before FML assigns the active Xaero container. The end boundary is immediately before FML clears it in the `finally` reached after the runnable returns or throws. The measured interval therefore encloses the stock future-exception-hook setup plus the runnable, and excludes all later rendering/network work and the optional slow-task warning itself.

### What is deliberately not changed

The diagnostic does **not**:

- wrap or replace the `Runnable`;
- redirect `Runnable.run()`;
- replace or wrap the executor;
- reorder any deferred tasks;
- alter FML owner state or `CompletableFuture` identity;
- skip/cache/defer Xaero work;
- parse warning text to determine completion;
- touch Xaero config, cache, map data, resources, registries, files, or networking;
- persist data between launches.

All injections remain `require=0` / non-required. If the FML class is not transformable at this point, the expected failure mode is no observation and a `title_without_observation` diagnostic, not a startup dependency.

## Stack sampler

When the FML owner boundary enters the Xaero deferred task, `XaeroDeferredTaskProfiler`:

1. records the exact executing thread;
2. starts one daemon sampler at 5 ms;
3. samples only that original thread;
4. aggregates a top non-logging leaf frame and first `xaero.*` frame;
5. stops sampling when the FML owner-clear boundary is observed;
6. retains a 5 s timeout only as a fail-open **invalid diagnostic result**.

Valid completion reason:

```text
after_runnable_owner_clear
```

`timeout`, `sampler_interrupted`, and `title_without_observation` are diagnostic failures and must not be interpreted as Xaero attribution.

Sampling itself adds small safepoint / stack-walk pressure, so the clean uninstrumented 1.026 s warning remains the better baseline for stock inclusive wall. The corrected smoke is primarily for source-level stack attribution and boundary validation.

## Corrected exact-pack smoke request

```text
[exact-pack-ci]
exact-pack-mode: smoke
exact-pack-smoke-jvm-arg: -Dboot_optim.profileXaeroDeferredTask=true
```

Required mechanism checks before discussing an optimization:

1. build/package succeeds and title is reached;
2. zero BootOptim Mixin failures;
3. profiler reports `status=sampling` with `owner=xaeroworldmap` on `Render thread`;
4. completion reason is exactly `after_runnable_owner_clear`;
5. wall is bounded to the individual deferred runnable rather than the 5 s timeout;
6. samples are nonzero and `top_xaero` / `top_leaf` identify work inside that bounded interval.

If the owner boundary is not observed, fix instrumentation first. Do not fall back to parsing the slow-task warning.

This remains a diagnostic smoke, **not an A/B optimization gate**.

## Decision gate after valid attribution

No mechanism is selected from the rejected first smoke. Only a valid owner-bounded smoke may justify one of these next steps.

### CPU-heavy deterministic setup

If the bounded samples consistently identify pure deterministic setup, inspect the exact inputs and lifetime. A future compatibility may cache only immutable/launch-stable results with stock first calculation and explicit fail-open/version guards.

### Config / filesystem / region-cache work

Separate blocking wall from CPU before proposing a cache. Any physical-storage claim requires a Windows laptop gate; hosted Linux/llvmpipe cannot establish disk/page-cache savings.

### Optional-mod / registry reflection

If the bounded interval repeatedly scans launch-stable integration state, investigate a narrow once-per-launch memo only after confirming the data is not world/session mutable.

### Network/version checking

If a valid bounded run proves remote/version-check work dominates, first establish whether the result has any startup consumer. Moving or making that work non-blocking is only acceptable if failure semantics and later consumers remain stock-equivalent; the rejected 5 s run cannot establish this premise.

### Work only needed after joining/using a map

A defer is **not** proposed from this diagnostic. It may be considered only after source/stack evidence proves there is no title-screen consumer and a separate behavior/performance gate measures both:

- TTMM / startup critical path; and
- first singleplayer/multiplayer world readiness **and first Xaero map use**.

Moving a startup stall into world entry, first playable frame, or first map opening is not a success.

## Risks

- `Thread.getStackTrace()` sampling adds diagnostic overhead; do not use the instrumented wall as a performance A/B.
- The owner-boundary mixin targets FML internals. `require=0` makes method/transform drift fail open, and the smoke must prove observation before its samples are accepted.
- External Xaero implementation details can change without source visibility, so any later compatibility needs exact artifact/version guards.

## Reopening / promotion criteria

Do not implement a defer, cache, or network scheduling change from the historical 1.026 s warning or rejected 5 s sample. First obtain a corrected exact-pack run ending at `after_runnable_owner_clear`, identify a narrow mechanism inside that bounded runnable, then test the smallest semantics-preserving candidate. Any defer additionally requires first-world and first-map-use gating.
