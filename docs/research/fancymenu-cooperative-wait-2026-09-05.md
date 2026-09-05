# FancyMenu cooperative preload wait experiment — 2026-09-05

Status: **ACTIVE EXPERIMENT / DEFAULT OFF / DO NOT PROMOTE WITHOUT PHYSICAL VISUAL GATE**

Base: `agent/integration-current` @ `d29a6bad6358c7ff78dadbc5e85bd753c0ad2a54`.

Precedent diagnostic: PR #109 @ `30e67ce3a10a5a3e51f59fad37044da3b6794025`. This experiment does not modify, merge, or depend on the diagnostic branch.

## Why this premise is different

Production BootOptim already starts the six existing asynchronous PNG suppliers for each current cubic panorama. PR #83 rejected increasing the panorama look-ahead window because the hosted target interval barely moved. This experiment does **not** add decoders, workers, resources, panorama look-ahead, deferred work, or GL movement.

PR #109 instead measured the CPU consumed by FancyMenu 3.9.0's stock empty-body waits. The exact-pack hosted smoke `33975186210` reported:

- `132` covered waits, `0` nested waits;
- preload: `4451.087 ms` wall / `3114.279 ms` current-thread CPU;
- panorama: `3506.817 ms` wall / `2277.126 ms` CPU;
- slideshow: `810.446 ms` wall / `799.237 ms` CPU;
- ordinary: `8.957 ms` wall / `8.978 ms` CPU;
- main menu reached, exact resource selection valid, and `0` BootOptim Mixin failures.

This is CPU attribution, not an A/B speedup claim.

The fixed full-pack laptop run `variance-fixed-021` is the hardware motivation: TTMM `363.231 s`; preload waits `18.298 s` wall / `18.234 s` current-thread CPU; panorama `16.360 / 16.282 s`, slideshow `1.784 / 1.766 s`, ordinary `0.034 / 0.031 s`; process CPU `36.047 s`; zero GC. On the 2C/4T machine the waiting caller therefore competes materially with the already-existing decode threads.

## Exact stock contract

For the pinned FancyMenu 3.9.0 binary, `Resource.waitForLoadingCompletedOrFailed(long)` is equivalent to:

```java
long start = System.currentTimeMillis();
while (!isLoadingCompleted()
        && !isLoadingFailed()
        && start + timeoutMs > System.currentTimeMillis()) {
    // empty
}
```

`ResourcePreLoader` performs failure/timeout handling after the call returns. The wait itself neither inspects nor clears Java interruption.

The exact `ResourcePreLoader` bytecode contains five wait instructions:

- one ordinary `Resource` wait in `preLoadAll`;
- two `ITexture` waits in `preLoadSlideshow`;
- two `ITexture` waits in `preLoadCubicPanorama`.

The experiment wraps only those instructions.

## Ownership and stock control

Property, deliberately default `false`:

```text
-Dboot_optim.experimentFancyMenuCooperativeWait=true
```

A separate Mixin config uses `FancyMenuExperimentMixinPlugin`. When the property is false the plugin declines `FancyMenuCooperativeWaitMixin`; no experimental wrapper is injected into `ResourcePreLoader`. That is stronger than a wrapper that merely branches to stock at runtime: the control path keeps the experiment out of FancyMenu bytecode entirely.

When enabled, the experiment owns only the first startup `preLoadAll` invocation and its entering thread. Nested/re-entrant or later preload calls execute the chained original operation.

The five calls use MixinExtras `@WrapOperation`. If BootOptim cannot resolve the expected public `Resource` completion/failure predicates before replacing a wait, it invokes the chained original FancyMenu operation exactly once. FancyMenu remains optional and there is no compile-time dependency on its classes.

## Cooperative state machine

The candidate takes the same first `System.currentTimeMillis()` sample and computes the same Java `start + timeoutMs` deadline as stock. Every loop is ordered strictly:

1. `isLoadingCompleted()`;
2. `isLoadingFailed()`;
3. `deadline > System.currentTimeMillis()`;
4. only then choose whether to park or continue a bounded stock-like spin.

Normal waits use `LockSupport.parkNanos` with a maximum `100,000 ns` quantum. The final `<= 1 ms` before the stock wall-clock deadline is spun rather than parked to limit scheduler overshoot. A spurious park return simply restarts the stock predicate order.

The state publication audited in #109 remains unchanged: the relevant exact-pack image/audio/video/text implementations publish completion/failure through `volatile` state or atomics.

### Interruption and fallback contract

Stock ignores interruption. The candidate therefore never calls `Thread.interrupted()` and never clears/restores the bit.

| Case | Candidate behavior | Observable intent |
| --- | --- | --- |
| Pre-interrupted thread | Check completion → failure → deadline, observe interrupt only afterward, then bounded-spin to the same stock deadline | Interrupt bit remains set throughout; no immediate “interrupted” return |
| Interrupted during `parkNanos` | Park returns; next loop again checks completion → failure → deadline first, then switches permanently to bounded spin | Interrupt bit remains set; no repeated immediate parks |
| Completion | Return only after completion predicate is observed true | Same return meaning as stock |
| Failure | Return only after completion is false and failure is true | Same predicate priority as stock |
| Timeout | Return when the same `start + timeoutMs > currentTimeMillis()` test becomes false | No timeout is converted into success |
| Final <=1 ms | Spin instead of parking | Limits scheduler overshoot relative to stock |
| Virtual thread | Bounded-spin to the same deadline | Avoids relying on a different parking implementation |
| `parkNanos` runtime/linkage failure | Bounded-spin to the same deadline | Candidate remains bounded and does not restart the timeout |
| FancyMenu access mismatch | Invoke chained original operation exactly once | Explicit fail-open to exact stock implementation |

`Thread.sleep` is intentionally not used. Its checked interruption path clears interruption before throwing and would require a different restoration contract.

`LockSupport.parkNanos` can consume a pre-existing LockSupport permit. The experiment minimizes exposure with short parks and immediately falls back to spin when interruption is set, but this remains a compatibility difference to inspect before any production promotion. If hosted/laptop evidence is only small, that semantic cost weighs against shipping the optimization.

## Instrumentation in the candidate

The enabled experiment emits one low-cardinality marker after the owned startup preload:

```text
BOOTOPTIM_FANCYMENU_COOPERATIVE_WAIT status=... cpu=... wait_calls=... cooperative_calls=...
```

It records preload wall/current-thread CPU and ordinary/slideshow/panorama wait wall/current-thread CPU, plus park count, final-deadline spins, interruption/virtual/park/timer fallbacks, access failures and chained-stock fallbacks. There is no per-poll/per-image logging.

Control runs intentionally do **not** inject this marker, because preserving the property-false stock bytecode is the stronger invariant. Stock wait CPU evidence therefore comes from #109 and the fixed physical diagnostic; paired hosted A/B is primarily a wall/TTMM gate.

## Work explicitly unchanged

- no new decoder threads or worker pool;
- no six-supplier concurrency increase;
- no panorama rolling window;
- no resource count/order change;
- no callback duplication;
- no texture upload, TextureManager, OpenGL, or render-thread work moved to workers;
- no renderer-listener defer (#95/#102);
- no MCEF, MoreCulling, VoxelShaper, or snapshot-diagnostic changes.

The first later consumer of the decoded texture remains FancyMenu's normal `ITexture#getResourceLocation()` path, which performs lazy Minecraft texture registration/upload when the menu actually renders. This experiment only changes how the caller waits for the existing decode completion state.

## Deterministic gate

The pure state machine has JUnit coverage for:

- completion before failure/deadline;
- failure before deadline;
- completion after a park;
- failure after a park;
- wall-clock timeout;
- short deadline with no final park;
- pre-interrupted caller with bit preserved;
- interruption during a park with bit preserved;
- park failure fallback;
- virtual-thread fallback;
- Java overflow of `start + timeoutMs`, preserving stock signed-long deadline behavior.

No CEF or GL is required for these tests.

## Runtime gate and decision rule

1. Build and normal Startup CI must pass.
2. Run one hosted exact-pack smoke with the property enabled. It is semantic/coverage evidence only, not a performance result.
3. A valid smoke must reach the title, preserve exact resource-pack selection/order, show zero BootOptim Mixin failures, and report `132` wait attempts with zero access/stock/park/timer/interrupt/virtual fallback in the fixture.
4. Only then request same-branch hosted 3x3 candidate/control:
   - candidate: `-Dboot_optim.experimentFancyMenuCooperativeWait=true`;
   - control: `-Dboot_optim.experimentFancyMenuCooperativeWait=false`.
5. Report TTMM, reload→FancyMenu, production panorama/preload wall, candidate wait CPU/wall, pack-selection state and Mixin state. Do not infer paired control wait CPU from an uninstrumented control; cite #109 separately.
6. If hosted is tied/negative, reject without a laptop run.
7. If hosted is positive but small, classify hardware-sensitive and prepare one reproducible JAR for the fixed `variance-fixed` laptop test only. Do not request repeated laptop batches.
8. Production promotion would still require a physical semantic/visual menu check, especially because `LockSupport` parking is a scheduling/permit behavior change.

## Related

- #38 — FancyMenu 3.9.0 preload source audit.
- #39 / #54 — retained six-face panorama supplier overlap.
- #83 — rejected two-panorama look-ahead.
- #95 / #102 — renderer defer line; not reused here.
- #109 — stock wait current-thread CPU diagnostic.
