# FancyMenu preload wait CPU attribution — 2026-09-05

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE AS PRODUCTION**

Base: `agent/integration-current` @ `792d06ec008c5ebae3681dd94f7aeee2c8e5f2a2`.

## Premise

Production already overlaps the six existing local PNG decoders inside each cubic panorama. PR #83 tested a different mechanism (a two-panorama rolling window) and rejected it after the hosted exact-pack target interval barely moved. This investigation does **not** increase concurrency, widen that window, defer resources, remove preload work, or move texture upload.

The new question is narrower: FancyMenu's preload waits use an empty-body polling loop. If the waiting caller consumes material CPU while existing decoder threads need the same limited CPUs, a cooperative wait might reduce contention without launching any additional decoder. The physical smoke017 `preLoadAll` wall (~39.875 s) motivates the question but is not CPU evidence and is not a performance baseline.

## Exact source and exact-pack binary

PR #38 downloaded FancyMenu's official Modrinth source artifact for version ID `ERSQlY78`:

- FancyMenu `3.9.0-1.21.1-neoforge`;
- source artifact: `sources_fancymenu_neoforge_3.9.0_MC_1.21.1.jar`;
- public version page: <https://modrinth.com/mod/fancymenu/version/3.9.0-1.21.1-neoforge>.

PR #89 later archived the **actual FancyMenu JAR extracted from the pinned exact-pack fixture**. Reinspection for this front found:

- exact fixture JAR SHA-256: `8e1c68f2c91aed02057209252bbe221bf3b019c4e82fb20fe35809bac2c08db8`;
- embedded NeoForge mod metadata declares `fancymenu` version `3.9.0`;
- `javap` on the exact fixture binary shows the same `Resource.waitForLoadingCompletedOrFailed(long)` loop as the official 3.9.0 source used by #38.

Historical logs sometimes call the installed build `3.9.0-wedit`. That label is **not** treated as proof of a separate public source repository or as permission to edit/fork FancyMenu. The pinned binary is authoritative for this diagnostic. Any direct modification of a foreign/custom JAR remains out of scope unless ownership/source/permission is established separately.

### Stock wait contract

`de.keksuccino.fancymenu.util.resource.Resource` is an interface. Its default method is effectively:

```java
long start = System.currentTimeMillis();
while (!isLoadingCompleted()
        && !isLoadingFailed()
        && start + timeoutMs > System.currentTimeMillis()) {
    // empty body
}
```

Consequences of the exact implementation:

- completion is checked before failure, and failure before timeout, on every iteration;
- timeout uses wall-clock `System.currentTimeMillis()`, not CPU time or `nanoTime()`;
- the method itself does not throw on timeout or failure;
- it does not inspect, clear, restore, or otherwise react to Java interruption;
- a second/re-entrant invocation has no retained wait state: it starts a fresh timeout from a new `currentTimeMillis()` sample;
- `Thread.sleep(...)` is therefore not automatically equivalent: interruption changes its control flow and interrupt status unless handled explicitly;
- `LockSupport.parkNanos(...)` avoids a checked interruption exception, but an already-interrupted thread returns immediately from park and scheduling/timeout overshoot still need an explicit equivalence contract before use.

`ResourcePreLoader` performs the error/timeout decision **after** the wait returns. It reports loading failure when `isLoadingFailed()` is true; otherwise, if completion is still false after the bounded wait, it reports a timeout. This investigation does not change that order or those checks.

## Resource families and state publication

`ResourceHandlers` in the exact fixture binary registers four ordinary resource families: image, audio, video and text. Slideshow and cubic-panorama paths wait on `ITexture` resources directly.

Concrete exact-binary resource implementations inspected for completion/error publication include:

- images: `PngTexture`, `JpegTexture`, `GifTexture`, `ApngTexture`, `FmaTexture`, `AfmaTexture`;
- audio: `OggAudio`, `WavAudio`;
- video: `Mp4Video`;
- text: `PlainText`.

`PngTexture`, `JpegTexture`, `OggAudio`, `WavAudio`, `Mp4Video` and `PlainText` publish the relevant completion/failure/closed state with `volatile` fields. `GifTexture`, `ApngTexture`, `FmaTexture` and `AfmaTexture` use `AtomicBoolean` for the corresponding state (with additional volatile playback/decode state). This is materially stronger than assuming visibility from the `Resource` interface alone, which specifies no Java-memory-model guarantee by itself.

For local PNG specifically, #38 established that `PngTexture.of(...)` starts asynchronous `NativeImage.read(...)`; completion/failure are published by the worker, while `getResourceLocation()` performs Minecraft texture registration/upload lazily later. The wait does not perform GL work and this front must not move any GL operation off the render thread.

## Exact wait call sites

`javap` of the fixture JAR's `ResourcePreLoader` gives a low-cardinality boundary without replacing `Resource`:

- `preLoadAll`: one bytecode `INVOKEINTERFACE Resource.waitForLoadingCompletedOrFailed(J)V` site for ordinary resources;
- `preLoadSlideshow`: two `INVOKEINTERFACE ITexture.waitForLoadingCompletedOrFailed(J)V` sites (image and overlay);
- `preLoadCubicPanorama`: two equivalent `ITexture` sites (face image and overlay).

The diagnostic injects immediately **before and after those original INVOKE instructions**. It does not redirect the call and therefore the exact stock wait, timeout and post-wait error handling still execute once in their original order.

## Diagnostic

Property (default off):

```text
-Dboot_optim.fancymenuWaitCpuDiagnostic=true
```

Scope is only the first startup `ResourcePreLoader.preLoadAll` invocation and only the thread that entered it. There is no per-resource or per-iteration logging. One final marker aggregates:

- total wait call count;
- preload inclusive wall and current-thread CPU;
- ordinary wait calls / wall / current-thread CPU;
- slideshow wait calls / wall / current-thread CPU;
- panorama wait calls / wall / current-thread CPU;
- nested/unbalanced-boundary detection.

CPU uses the standard `ThreadMXBean.getCurrentThreadCpuTime()`. The diagnostic enables thread CPU timing only when the JVM reports support and only when this opt-in diagnostic is active.

Marker:

```text
BOOTOPTIM_FANCYMENU_WAIT_CPU status=... wait_calls=... nested_waits=... preload_wall_ms=... preload_cpu_ms=... ordinary_calls=... ordinary_wall_ms=... ordinary_cpu_ms=... slideshow_calls=... slideshow_wall_ms=... slideshow_cpu_ms=... panorama_calls=... panorama_wall_ms=... panorama_cpu_ms=...
```

A measurement is usable only when `status=ok`, total coverage is non-zero, panorama coverage is non-zero for the exact fixture, the main-menu marker is reached, resource-pack selection/order passes for **every** effective reload, there is no `Caught error loading resourcepacks`, and there are no BootOptim Mixin failures. `zero_coverage`, `boundary_invalid` or `cpu_unavailable` are diagnostic failures, not performance results.

The wrapper itself adds a small amount of timing/counter overhead. The family timers begin after the pre-hook enters and finish before aggregation, but the inclusive `preLoadAll` CPU/wall naturally contains diagnostic overhead. This smoke is for attribution, not an A/B timing claim.

## Gate and decision rule

First gate: one hosted exact-pack smoke on the pinned `exact-pack-2026-09-02-v1` fixture with the diagnostic property enabled. PR #103's integrated resource contract must validate selected pack order and every effective reload; reaching title after a fallback is invalid.

Only if the hosted smoke shows material current-thread CPU inside the stock waits **and** the exact state/interruption/timeout contract supports a conservative implementation should a separate candidate be written. That candidate must be property off/on, bounded, preserve the stock predicate/error/timeout ordering, preserve interrupt status/behavior, leave callbacks and GL on their original thread, and receive explicit completed/error/timeout/interruption equivalence tests before a hosted 3x3.

The eventual 3x3, if justified, must use the lightweight candidate without this CPU diagnostic and report CPU evidence from the proof smoke separately from preload wall, reload-to-FancyMenu and TTMM. No laptop run is justified before a useful hosted result.

If CPU is small/absent, the boundary cannot be measured reliably, or semantic equivalence cannot be demonstrated, close this front without a cooperative-wait patch. Do not infer seconds saved from the number of waits or from inclusive wall.

## Related evidence

- PR #38 — exact 3.9.0 resource-preload source audit.
- PR #39 — six-face panorama overlap experiment.
- PR #54 — production promotion of the retained six-face overlap.
- PR #75 — resource-reload apply-tail source attribution; wall intervals are not self CPU.
- PR #83 / `fancymenu-panorama-window2-2026-09-02.md` — rejected two-panorama rolling window.
- PR #89 — exact fixture FancyMenu JAR extraction/audit artifact used here for binary verification.
- PR #103 — exact resource-pack selection/fallback gate now integrated.
