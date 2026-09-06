# MCEF first-consumer owner/reentry hardening — 2026-09-06

Status: **CANDIDATE / DETERMINISTIC HARNESS ADDED; HOSTED VALIDATION REQUIRED**

PR #90 is integrated, so its gameplay-safety follow-up can now be evaluated on the authoritative tree. PR #143 removed the old fixed 30-second lifecycle timeout, recorded the initializer thread, and added an `INITIALIZING` state. Agent 33 audited that candidate against integration `b0aa2472d58e3afc56a380e026c99ffe87000f22` and found that the safety contract still was not directly testable because state, Minecraft thread handoff, MCEF reflection, and completion were all static in one class.

The audit also found two concrete gaps in #143 itself:

1. same-owner reentry while `INITIALIZING` bypassed unconditionally because the condition accepted `state == INITIALIZING` even when `MCEF.isInitialized()` was still false. The #129 contract requires this pre-publication reentry to fail deterministically rather than wait, recurse, or proceed into a stock getter that requires readiness;
2. an `Error` thrown by the real `MCEF.initialize()` body is wrapped by reflection in `InvocationTargetException`. #143 tested whether the wrapper itself was an `Error`, so an underlying `Error` could be logged and converted to `false` instead of being rethrown after state/completion were made consistent.

## Extracted state core

`McefInitializationStateMachine` is a package-private Java-only coordinator used directly by `McefFirstConsumerDefer`. It has no Minecraft, MCEF, CEF, GL, or test-framework dependency.

States:

```text
ARMED
  -> DEFERRED
  -> FORCING_BY_CONSUMER
  -> INITIALIZING(owner)
     -> COMPLETE
     -> FAILED

ARMED/DEFERRED/FORCING_BY_CONSUMER -> ABORTED
INITIALIZING -> ABORTED              // forbidden
```

The state core owns only claim/owner/completion semantics. Minecraft client-thread handoff and the real reflective `MCEF.initialize()` remain in `McefFirstConsumerDefer`; no native work is moved to a worker.

### Consumer decisions

- `DEFERRED`: exactly one consumer wins `INITIALIZE`.
- `FORCING_BY_CONSUMER`: other consumers return `WAIT`.
- `INITIALIZING` + non-owner: `WAIT`.
- `INITIALIZING` + same owner + MCEF published readiness: `BYPASS`, allowing synchronous `scheduleForInit` callbacks such as WebDisplays to use stock getters.
- `INITIALIZING` + same owner + readiness still false: deterministic `IllegalStateException`; it never waits on itself and never recursively invokes the initializer.
- terminal or non-deferred states: `BYPASS` to preserve stock/fail-open behavior.

Only the thread recorded by `beginInitialization` may publish the attempt terminal result. A false initializer result becomes `FAILED`. A thrown exception or `Error` becomes `FAILED(cause)` and completes all waiters exceptionally before an underlying `Error` is rethrown on the initializer thread. Reflection failure unwrapping is part of the Java-only core so the harness can verify the `InvocationTargetException -> Error` case without loading MCEF.

### In-flight automatic trigger race

The real BootOptim-owned initializer still bypasses the suppression hook through the existing `FORCE_INITIALIZE` thread-local. Once a first consumer has claimed the attempt (`FORCING_BY_CONSUMER` or `INITIALIZING`), a delayed stock `CefInitMixin` trigger is suppressed instead of being allowed to start a second unowned initializer. This is required by the #129 single-owner contract. After `FAILED`/`ABORTED`, later stock behavior is no longer suppressed, preserving fail-open fallback.

## Deterministic harness

`src/mcefHarness/java/dev/wachipayox/bootoptim/compat/client/McefInitializationStateMachineHarness.java` uses only Java threads, `CountDownLatch`, and explicit checks. `build.gradle` defines a dedicated `mcefHarness` source set, registers `mcefCoordinatorHarness`, and makes `check` depend on it, so normal `build` executes the harness without adding JUnit, creating normal Gradle test classes, or changing production packaging.

Run directly with:

```text
./gradlew mcefCoordinatorHarness
```

or through the normal gate:

```text
./gradlew test build
```

Covered cases:

1. **same-thread callback reentry after publication** — owner is `INITIALIZING`, readiness is true, guard returns `BYPASS`, outer owner completes `COMPLETE`;
2. **same-thread reentry before publication** — guard throws deterministically while global state remains `INITIALIZING`; no wait, recursion, forged terminal state, or second initializer occurs;
3. **concurrent second consumer** — second thread receives `WAIT`, remains blocked until the owner publishes success, then observes the same `COMPLETE` result;
4. **initializer `Error`** — two waiters are released with the same failure cause, owner state becomes terminal `FAILED`, owner identity is cleared, and a synthetic `InvocationTargetException(Error)` unwraps to the underlying `Error` for rethrow;
5. **long initialization** — a waiter is deliberately held while the owner remains blocked; elapsed time does not change global state from `INITIALIZING` or clear/change the owner, and release of the owner later produces `COMPLETE`.

The long-wait case intentionally does not sleep for 30 seconds. Production now contains no elapsed-time branch at all: the harness proves structurally that a waiter has no authority to publish a timeout terminal state, then keeps the attempt blocked long enough to assert that only owner completion changes it. Reintroducing any lifecycle timeout would require changing the state core and should add a specific regression case.

## Real-consumer semantics audit

### MCEF / WebDisplays

MCEF `2.1.6-1.21.1` publishes `app` and `client` before synchronously running `scheduleForInit` listeners. Exact WebDisplaysFork `2.5.0-1.21.1` registers such a listener and calls `MCEF.getApp()` / `MCEF.getClient()` inside it. The post-publication owner `BYPASS` exactly matches that ordering: callbacks can finish on the owner thread, while a pre-publication guarded getter remains an invariant failure instead of a self-wait.

### FancyMenu

FancyMenu `3.9.0-wedit` high-level video/browser guards still call the same `beforeConsumer` entry point. The extracted state core does not change FancyMenu's bridge flag, retry behavior, rendering thread, or browser construction path. Existing PR #90 title/video smoke remains the relevant runtime regression evidence; this harness proves only coordinator semantics.

### Future world / connect entry

No local-world or remote-connect hook is added here. The #112/#129 ordering analysis remains the boundary if a future policy chooses pre-gameplay preparation. Such a hook must call this same coordinator on the Minecraft client thread and must not create a second ownership path. A title-screen state harness cannot prove Windows native/GL behavior, world loading UX, or first in-world WebDisplays rendering.

## Validation boundary and promotion criterion

Security/correctness demonstrated by this harness:

- owner callback reentry after MCEF publication cannot self-wait;
- owner reentry before publication cannot wait or recurse;
- concurrent consumers serialize behind one owner;
- false/failure terminal state is owner-published;
- reflective `Error` cause is not silently downgraded before waiter release;
- waiter elapsed time cannot publish `ABORTED` while native initialization is still running.

Still required before promoting #143-derived production code:

1. `./gradlew test build` green with `mcefCoordinatorHarness` executed by `check`;
2. hosted Build / Startup / exact-pack smoke green on the harness branch, primarily as mixin/pack regression validation rather than a new performance measurement;
3. review the smoke logs for the existing MCEF/FancyMenu markers and zero new BootOptim/Mixin failures;
4. do **not** add world/connect hooks from this branch. A later gameplay-boundary candidate still needs its own hosted functional coverage where possible and a focused Windows/native WebDisplays/world/connect gate before claiming hitch-free gameplay.

A laptop startup A/B is not requested: this change is a coordinator safety gate and does not need another TTMM effect-size campaign.

Relevant history: #90 production first-consumer defer, #112 gameplay-boundary source audit, #129 owner-aware state contract, #143 initial owner/reentry hardening.
