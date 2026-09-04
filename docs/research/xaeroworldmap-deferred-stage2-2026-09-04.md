# Xaero World Map deferred Stage 2/2 — 2026-09-04

Status: **ACTIVE DIAGNOSTIC / TWO ATTRIBUTION BOUNDARIES REJECTED / NO DEFER OR PRODUCTION CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Why this lane exists

A hosted exact-pack run (`33917611497`) exposed a mod-specific startup span outside the existing BootOptim fronts:

- exact artifact: Xaero's World Map `1.41.0` with XaeroLib `1.1.15`;
- Stage 2/2 runs on `Render thread`;
- NeoForge reported `Mod 'xaeroworldmap' took 1.026 s to run a deferred task.`

FancyModLoader's stock `DeferredWorkQueue` sets the queued task owner's `ModContainer` active, executes `ti.task.run()`, clears the owner in `finally`, then logs a warning only when the individual task exceeded one second.

The stock 1.026 s is therefore inclusive wall for one owner-tagged synchronous deferred runnable in that run. It is **not** a recoverable-TTMM claim.

## Ownership boundary

Xaero World Map is not a repository controlled by the authenticated `wachipayox` account. Do not propose a direct Xaero source edit as if the project owns it. Any BootOptim compatibility must be exact-version gated, fail-open, and semantics-preserving.

## Rejected diagnostic #1 — Stage marker to slow-warning text

The first PR #100 implementation started a 5 ms sampler from Xaero's `Stage 2/2` log and attempted to stop it by parsing NeoForge's slow-task warning.

Exact-pack run `33924496490` reached title with zero BootOptim Mixin errors but completed:

```text
BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=timeout wall_ms=5003 samples=866
```

No slow-task warning appeared in that instrumented run, so the sampler continued into unrelated later rendering/network work. Hundreds of samples were already in `glBlitFramebuffer`, with additional `Net.poll` samples. Those 5.003 s and their aggregate stacks are **discarded for attribution**.

Why the boundary is rejected:

- the warning is thresholded at one second and can legitimately disappear when the same task runs slightly faster;
- warning/log formatting is not the runnable completion boundary;
- a timeout necessarily mixes later work.

Do not reuse marker-to-warning parsing.

## Rejected diagnostic #2 — normal client mixin on FML owner setter

The next implementation removed all warning parsing and attempted to observe the existing `ModLoadingContext.setActiveContainer(owner)` / `setActiveContainer(null)` calls with a non-required client mixin. This would have been a suitable semantic boundary if transformable.

Exact-pack run `33926828559` reached title successfully with:

- main menu: `92.958 s`;
- mod entrypoint: `31.239 s`;
- reload -> FancyMenu finish: `43.687 s`;
- BootOptim Mixin errors: `0`.

However the profiler reported only:

```text
BOOTOPTIM_XAERO_DEFERRED_PROFILE status=installed boundary=fml_active_container ...
BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=title_without_observation ... samples=0
```

Xaero still logged Stage 2/2 on the Render thread, so this is not absence of the task. The ordinary mod/client mixin does not provide a usable interception point for this already-loader-side FML class in the exact launch.

That mixin has been removed. Do not interpret this smoke as Xaero attribution.

## Diagnostic #3 — early ModLauncher transform of DeferredWorkQueue

Property remains:

```text
-Dboot_optim.profileXaeroDeferredTask=true
```

BootOptim already has an `ITransformationService` in ModLauncher's SERVICE layer. The third diagnostic registers a property-gated transformer there, before the regular BootOptim mod constructor.

### Structural target

The transformer targets only:

```text
net/neoforged/fml/DeferredWorkQueue
```

It does not depend on the compiler-generated lambda method name. Instead it scans the class tree and requires exactly one existing:

```text
INVOKEINTERFACE java/lang/Runnable.run ()V
```

Then, in that same method, it locates the existing `ModLoadingContext.setActiveContainer(null)` calls belonging to the compiled `finally` paths.

If the expected structure is not present, it prints `BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=unmatched ...` and returns the stock class unchanged.

### Boundary instrumentation

The transformer adds four public synthetic volatile diagnostic fields to `DeferredWorkQueue`:

- Xaero start nanos;
- Xaero end nanos;
- executing thread id;
- boundary state.

It inserts a balanced, zero-argument static marker call immediately before the existing `Runnable.run()` instruction and immediately before each existing owner-clear call in the same task method. The original Runnable reference/arguments remain on the operand stack and the existing invocation is left intact.

The start helper checks FML's already-active `ModContainer` and records a boundary only when its id is exactly `xaeroworldmap`. The end helper records `System.nanoTime()` only while the Xaero boundary is active on the same thread.

Both helper methods live on `DeferredWorkQueue` itself. No injected FML bytecode calls a BootOptim game-module class, avoiding a cross-module callback at the runnable boundary.

This does **not**:

- wrap, replace, redirect, or invoke the Runnable a second time;
- wrap or replace the executor;
- reorder the deferred queue;
- alter FML owner state;
- alter the task future;
- parse any log message;
- skip/cache/defer Xaero work.

The early transformer is registered only while `boot_optim.profileXaeroDeferredTask=true`; production/property-off launches receive no transformer from this diagnostic.

### Sampler

The regular client profiler reflectively verifies that the four transformed fields exist. A daemon watcher reads only those fields. When boundary state becomes active it resolves the recorded thread id, samples that thread every 5 ms, and stops from the exact transformed end timestamp.

Valid completion reason:

```text
after_runnable_owner_clear
```

Invalid results include:

- transformed fields missing;
- transform structure unmatched;
- `title_without_observation`;
- `boundary_stuck_timeout`;
- zero/invalid timestamps.

The timestamp is captured by injected bytecode at the stock boundary. Polling latency can miss a few early stack samples but cannot extend the reported wall past the runnable into later rendering.

## Exact-pack gate

```text
[exact-pack-ci]
exact-pack-mode: smoke
exact-pack-smoke-jvm-arg: -Dboot_optim.profileXaeroDeferredTask=true
```

Accept attribution only if all are true:

1. Build/package succeeds and title is reached.
2. Zero BootOptim Mixin failures.
3. Console contains `BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=applied` with one Runnable run and at least one owner-clear path.
4. Profiler reports sampling `owner=xaeroworldmap` on `Render thread`.
5. Completion reason is exactly `after_runnable_owner_clear`.
6. Wall is bounded to the individual deferred task rather than a safety timeout.
7. Samples are nonzero and identify work inside that bounded interval.

If the early transformer does not apply to the FML class, instrumentation must be fixed again. Do not fall back to warning parsing.

This remains a **diagnostic smoke**, not an optimization A/B.

## Decision gate after valid attribution

No optimization mechanism is selected from either rejected smoke.

Only a valid owner-bounded run can justify source/bytecode follow-up. Interpret the bounded stacks by mechanism:

- deterministic launch-stable CPU work: inspect narrowly scoped memoization with stock first calculation;
- filesystem/config/cache work: separate CPU from blocking wall and require real Windows hardware for storage claims;
- optional-mod/registry scans: cache only launch-stable state, never mutable world/session state;
- remote/version checking: first prove it dominates the bounded task and establish every startup/later consumer before changing scheduling.

### Deferral rule

No defer is proposed by PR #100.

A future defer is considered only if bounded evidence proves the work has no title-screen consumer and a separate gate measures both:

- TTMM / startup critical path; and
- first singleplayer/multiplayer world readiness **plus first Xaero map use**.

Moving a startup stall into world entry, first playable frame, or first map opening is not a success.

## Risks

- Stack sampling adds diagnostic overhead; do not use the instrumented smoke as a performance A/B.
- The early transformer targets FML internals and must be structurally self-validating/fail-open.
- External Xaero implementation details may change; any later compatibility needs exact artifact/version guards.

## Reopening / promotion criteria

Do not implement a cache, network scheduling change, or defer from the historical 1.026 s warning, the rejected 5 s sample, or the no-observation mixin smoke. First obtain an exact-pack run ending at `after_runnable_owner_clear`, then identify a concrete mechanism inside that bounded runnable. Any defer additionally requires first-world and first-map-use gating.
