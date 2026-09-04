# Xaero World Map deferred Stage 2/2 — 2026-09-04

Status: **ACTIVE DIAGNOSTIC / NO DEFER OR PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Why this lane exists

A current hosted exact-pack run (`33917611497`) exposes a mod-specific startup span that was not in the existing BootOptim research fronts:

- exact artifact: Xaero's World Map `1.41.0` with XaeroLib `1.1.15`;
- `20:45:57.056` — `Loading Xaero's World Map - Stage 1/2` on `Worker-ResourceReload-1`;
- `20:45:59.460` — `Loading Xaero's World Map - Stage 2/2` on `Render thread`;
- `20:45:59.467` — `New world map region cache hash code: ...`;
- `20:46:00.415` — player-tracker registration and optional-mod checks complete;
- `20:46:00.486` — NeoForge reports `Mod 'xaeroworldmap' took 1.026 s to run a deferred task.`

The `1.026 s` value is stronger evidence than a generic log gap. FancyModLoader's `DeferredWorkQueue` times each owner-tagged `task.run()` individually and executes startup deferred work synchronously through the supplied executor. The warning is emitted only after that individual task completes.

This still does **not** prove a recoverable 1.026 s TTMM saving. It proves an inclusive, synchronous main-launch-thread task. A defer or rewrite is not justified until the internal work and dependencies are identified and an end-to-end gate shows critical-path leverage.

## Ownership / source boundary

The exact Xaero World Map artifact is not a repository controlled by the authenticated `wachipayox` account. Public GitHub search exposes integrations, stubs and logs but not an exact user-controlled source tree for this artifact.

Therefore:

- do not propose a direct edit to Xaero as if the project owns it;
- a BootOptim compatibility must remain version-gated, fail-open and semantics-preserving;
- source-level conclusions must come from observed runtime stacks / bytecode inspection rather than invented source ownership.

## Diagnostic

Property:

```text
-Dboot_optim.profileXaeroDeferredTask=true
```

`XaeroDeferredTaskProfiler` installs a temporary Log4j Core filter which always returns `NEUTRAL`; it never suppresses or rewrites messages.

When the exact Xaero Stage 2/2 marker is emitted, the diagnostic:

1. records the caller thread (expected `Render thread`);
2. starts one daemon sampler at a fixed 5 ms interval;
3. samples the original thread's Java stack without wrapping or replacing Xaero's deferred runnable;
4. aggregates the top non-logging leaf frame and the first `xaero.*` frame from each sample;
5. stops when NeoForge emits the individual `xaeroworldmap` deferred-task warning, or fails open after a 5 s timeout;
6. removes its Log4j filter and prints one `BOOTOPTIM_XAERO_DEFERRED_PROFILE` completion marker.

If the marker is never observed, the filter is removed at the first title screen and reports `title_without_observation`.

This profiler deliberately does not:

- skip or delay Stage 2/2;
- change the deferred task or its `CompletableFuture` identity;
- reorder NeoForge work;
- touch Xaero files, configs, caches, map data or resource results;
- change system/JVM configuration;
- persist anything across launches.

Sampling can perturb the measured task slightly, so its wall value is diagnostic only. The stock NeoForge `1.026 s` timing remains the cleaner pre-instrumentation inclusive-wall reference.

## Exact-pack request

A single hosted smoke is sufficient to answer the first attribution question:

```text
[exact-pack-ci]
exact-pack-mode: smoke
exact-pack-candidate-jvm-arg: -Dboot_optim.profileXaeroDeferredTask=true
```

Required mechanism checks:

- title reached;
- zero BootOptim Mixin failures;
- profiler reports `status=sampling` on `Render thread`;
- profiler completes from `deferred_task_warning`, not timeout;
- nonzero samples and useful `top_xaero` frames.

This is **not** an A/B optimization gate. No production behavior differs.

## Decision tree after attribution

### A. CPU-heavy deterministic initialization

If samples stay inside one or a few pure Xaero setup methods, inspect whether BootOptim can safely memoize immutable inputs or avoid repeated work while retaining stock first calculation. Any compatibility must be exact-version gated and fail open.

### B. Config / disk / cache scan

If stacks show config parsing or region-cache filesystem work, separate CPU from blocking wall first. A cache or lazy-load experiment requires robust invalidation and a real Windows laptop gate; hosted Linux cannot validate disk/page-cache savings.

### C. Optional-mod / registry scanning

If the task repeatedly reflects/scans registries or optional-mod integration state, look for a narrow once-per-launch cache whose first lookup stays stock. Do not cache mutable world/session state.

### D. Work only needed after joining a world

A defer is considered only if source/stack evidence proves the work has no title-screen consumers and the first-world gate remains acceptable. Required follow-up A/B must measure both:

- TTMM / startup critical path; and
- first singleplayer/multiplayer world readiness / first map use.

Moving a one-second stall from TTMM to the first playable frame is not a success.

## Risks

- `Thread.getStackTrace()` sampling adds small diagnostic overhead and safepoint pressure; do not compare its raw task duration as an optimization result.
- Logger text can drift between Xaero/FML versions; failure mode is no observation, not broader matching.
- A future async logging configuration may change where global filters execute. The diagnostic records the observed source thread and must reject attribution if it is not the expected Render thread.
- Closed/external Xaero implementation details can change without source visibility, so any later compatibility needs artifact/version guards.

## Reopening / promotion criteria

Do not implement a defer or cache from the 1.026 s warning alone. Reopen implementation only after exact-pack stack attribution identifies a narrow mechanism and its dependencies, then choose the smallest semantics-preserving candidate and gate it against TTMM plus first-world/first-use latency.
