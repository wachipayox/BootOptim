# Post-FancyMenu preload critical-tail audit — 2026-09-05

Status: **LIMITED / NO SAFE RUNTIME OPTIMIZATION IDENTIFIED**

Base audited: `agent/integration-current` @ `04711ceed0ed74378d759f733af2cfb43a4e3046`.

Scope: the startup interval after FancyMenu `ResourcePreLoader.preLoadAll` returns, through the observed `Minecraft resource reload: FINISHED` log boundary, `TitleScreen` opening, and the first actually presented title frame. The question is whether current BootOptim/NeoForge observations can distinguish Java/render-thread CPU contention, future/barrier waiting, ordered apply/listener work, and residual I/O/native/GPU/page-cache effects without changing reload ordering or moving GL/OpenAL work.

This audit does **not** reopen #117's cooperative wait, #118's shader/Voxy probes, #110, #83, or the renderer deferral rejected by #95/#102. It also does not treat hosted Linux/llvmpipe as the slow Windows laptop.

## Established evidence

The fixed full-pack physical run `variance-fixed-021` reached the main-menu marker in **363.231 s**. FancyMenu's stock preload wait consumed **18.298 s inclusive wall / 18.234 s current-thread CPU**, with panorama waits at **16.360 / 16.282 s wall/CPU** and process CPU of **36.047 s** during the preload. That proves substantial CPU contention **inside** the preload on this 2C4T machine, but not an end-to-end saving.

The same run's post-preload snapshot was **4.047 s wall** from preload return to title but only **0.953 s process CPU**. This is process-wide CPU, not just Render-thread CPU. Therefore run 021's post-preload tail is specifically **not Java-process-CPU dominated**. It does not prove why the remaining wall elapsed, and it must not be generalized to the older runs without equivalent snapshots.

The physical fixed-selection cohort shows a real but non-causal variance surface:

| run | preload -> reload-finished | preload -> title |
| --- | ---: | ---: |
| 017 | 17.186 s | 37.435 s |
| 019 | 14.823 s | 27.729 s |
| 020 | 13.741 s | 19.419 s |
| 018 | 4.641 s | 8.210 s |
| 021 | 2.042 s | 4.047 s |

These are **critical-path elapsed wall observations** only because they lie serially between the named log/menu boundaries. They are not CPU attribution and they are not an A/B.

PR #118 rules out the repeated shader/Voxy symptoms as an explanation of that multi-second surface: the six shader capability probes owned only **155.418 ms inclusive wall / 62.500 ms current-thread CPU**, while the Voxy save owned **5.534 ms wall** with `max_concurrent=1`.

PR #117 then tested the one locally compelling CPU mechanism. Its hosted exact-pack 3x3 reduced FancyMenu panorama wall by about **170 ms** and collapsed caller CPU in the smoke, but the actual critical path regressed: **reload -> FancyMenu +1.059 s** and **TTMM +2.302 s**. That mechanism is rejected and there is no physical escalation.

PR #116 independently establishes that `ResourcePreLoader.preLoadAll` has no safe BootOptim two-phase handoff: resource initiation, ordered waits, timeout/error handling and thread-affine resource families are interleaved. Returning early, replaying later, or widening launch concurrency changes observable lifecycle semantics.

PR #85 is an important interpretation precedent for the residual bucket. A physical `LevelRenderer` sample measured **2.061 s wall / 171.875 ms Render-thread CPU**, with `PostChain.load(entity_outline)` at **1.458 s wall / 171.875 ms CPU**. That wall/CPU gap cannot be labelled disk, page cache or GPU driver without another boundary. The same rule applies here.

## What integration currently measures

Current production/integration code has useful but insufficient endpoints:

1. `FancyMenuPanoramaPreloadMixin` records `preLoadAll` entry/return and its inclusive wall.
2. `StartupProfiler` marks the main menu from `ScreenEvent.Opening` when a `TitleScreen` is opened.
3. `tools/laptop-bench/phase_variance.py` parses the existing `Minecraft resource reload: FINISHED` log line and explicitly describes its result as a **coarse serial wall partition**, not CPU or listener attribution.
4. The process-CPU/GC/memory snapshots used by #113/#115 are diagnostic-branch evidence, not a production reload-scheduler boundary.

The resulting sequence is therefore:

```text
preLoadAll RETURN
  -> [unknown scheduler/listener/native/render work]
  -> log: Minecraft resource reload: FINISHED
  -> [unknown screen construction/render/native-present work]
  -> ScreenEvent.Opening(TitleScreen)
```

This is enough to prove that a tail exists, but not to assign it.

`ScreenEvent.Opening(TitleScreen)` is also not a sufficient proxy for a **visually usable** frame. #95/#102 demonstrated the concrete counterexample: title/menu lifecycle could advance while the visible surface was black/frozen because required renderer state had been deferred. Any future critical-tail profiler must retain `title_open` but add a first-frame boundary.

## Missing minimum scheduler boundary

BootOptim has already built the correct semantic primitive historically in diagnostic PR #47. Its `SimpleReloadInstance` tracer delegated the stock `StateFactory.create(...)`, original preparation barrier, futures and executors while recording:

- listener reaches the stock preparation barrier;
- global `allPreparations` future opens;
- the listener's original barrier future completes, meaning its ordered apply turn is available;
- the listener's returned future completes;
- aggregate queue/runtime on the original executors.

That distinction produced `global_wait_ms`, `order_wait_ms` and `post_turn_ms` instead of summing overlapping listener timings. It passed Build/Startup validation and obtained full listener coverage in its validation run. Historical exact-pack analysis then used those ordered post-turn intervals to identify real serial reload work.

The important conclusion for this audit is: **the project already knows the right barrier semantics, but that tracer is not present in current integration and the current post-FancyMenu variance tooling does not reproduce those boundaries.**

Reintroducing a broad permanent profiler is not justified. If this lane is reopened, the minimum useful diagnostic is a first-startup-reload-only, opt-in subset of #47's semantics plus first-frame presentation.

## Minimal diagnostic for reopening

Property, diagnostic-only:

```text
-Dboot_optim.profilePostFancyMenuTail=true
```

No sampling thread, no per-resource logging, no file-read wrappers, no JFR in the first pass, no executor replacement beyond the stock-delegating observation already proven by #47, and no runtime behavior when the property is absent.

Capture only these monotonic boundaries and aggregate them once after first title presentation:

1. `preload_return` — existing FancyMenu marker.
2. `fancymenu_listener_turn_ready` and `fancymenu_listener_future_complete` — using the stock listener/barrier identity discovered in that run, without assuming the historical listener index remains constant.
3. `reload_all_preparations_complete` and `reload_all_done` — stock `SimpleReloadInstance` futures.
4. `title_open` — current semantic marker.
5. `title_render_return` — first completed `TitleScreen` render path.
6. `title_present_return` — first window/display present returning after that title render.

At boundaries 1, 3, 4 and 6 record only cumulative process CPU and current Render-thread CPU when available. Reuse the same `ThreadMXBean`/OS process-CPU conventions as existing diagnostics; do not infer exclusive CPU from deltas that include other threads.

The first-frame pair matters for category (d): a large `title_render_return -> title_present_return` wall interval with little Java CPU is qualitatively different from a long reload listener future.

Expected overhead is low-cardinality: a handful of `nanoTime`/CPU-counter reads plus listener lifecycle timestamps stored in memory and one aggregate log line. The #47 implementation previously proved that stock barrier/future/executor delegation is possible without cancelling or reordering listeners. Nevertheless, this remains diagnostic-only because even completion callbacks and timer reads perturb a constrained 2C4T machine.

## Classification rule

The diagnostic is useful only if interpretation is mechanical:

| observed critical-path shape | classification supported | what it does **not** prove |
| --- | --- | --- |
| high Render-thread CPU and high process CPU across the same serial interval | (a) Java/render-thread CPU contention | exact hot method or recoverable TTMM |
| large `turn_ready`/global barrier wait with low owner CPU | (b) future/barrier/order wait | why the producer future is slow |
| large ordered `turn_ready -> listener_future_complete` interval | (c) apply/listener work on critical path | CPU vs native/I/O inside that listener unless CPU counters agree |
| large residual wall with low Java process CPU and no scheduler gap; especially render->present | (d) external I/O/page-cache/native/GPU/descheduling bucket | which member of that bucket is responsible |

Only after category (d) remains both material and named should a bounded JFR/native/file-read probe be considered. A wall-minus-CPU subtraction is not a disk or GPU measurement.

## Why no runtime optimization is implemented

There is no safe optimization target with a positive current gate:

- cooperative FancyMenu waiting is already rejected by hosted TTMM/reload wall (#117);
- wider panorama overlap is already rejected (#83);
- returning from or replaying `preLoadAll` changes lifecycle/thread semantics (#116);
- cancelling/defering renderer listeners can produce a black/frozen menu (#95/#102);
- shader/Voxy symptoms are too small (#118);
- moving MCEF/native work into resource preparation previously increased contention and regressed end-to-end wall (#78);
- `LevelRenderer` wall is hardware/native-sensitive and currently has only a limited ~1–2 s ceiling (#85), with no evidence that it owns the variable post-preload tail.

A generic change to listener order, executor parallelism, GL/OpenAL thread affinity, or future completion would therefore be speculation, not fail-open optimization.

## Hosted gate for any future diagnostic branch

Before any physical diagnostic, a branch implementing only the minimal boundaries above must pass:

1. Build and normal Startup with the property absent.
2. Hosted exact-pack smoke with `-Dboot_optim.profilePostFancyMenuTail=true`.
3. Exact resource selection/order, one effective reload, `8192x8192x2` block atlas, title reached, and zero BootOptim Mixin failures.
4. Full startup-reload listener coverage for the observed scheduler boundary; no hard-coded listener index or class-name-only assumption.
5. Monotonic ordering: preload return <= matching listener future completion <= reload all-done <= title open <= first title render return <= first title present return.
6. No duplicate first-title/present marker and no missing process/Render-thread CPU counters unless explicitly reported as unavailable.

Hosted smoke is an **instrumentation/semantic gate only**. The #113 hosted run already had only about **119.5 ms** post-preload->title wall on llvmpipe, so a tiny hosted residual cannot be used to claim the Windows tail has vanished or to identify its hardware cause.

Do not request a physical A/B from this audit. A single future physical **diagnostic** would be justified only after the hosted probe is valid and a still-current fixed-selection Windows run independently shows a material tail again; it should answer one classification question, not test an optimization. A production candidate would then require its own hosted exact-pack 3x3 and TTMM/reload critical-path win before any physical performance gate.

## Decision

**LIMITED / NO-GO for runtime changes.**

Confirmed cause: the current tooling has an observability gap between `preLoadAll` return and the first actually presented title frame. Run 021 additionally proves that its own 4.047 s post-preload tail was not Java-process-CPU dominated (0.953 s process CPU), but no source of the variable 017–020 tails is confirmed.

Unproven hypotheses remain: future/barrier delay, a specific ordered apply listener, physical resource I/O/page-cache delay, native media/audio work, Render-thread descheduling, and GPU/driver presentation work. The stable shader/Voxy symptoms are quantitatively rejected as the explanation.

Do not optimize the unnamed residual. Reopen this lane only with the minimum scheduler + first-present diagnostic above, or with a new source-level candidate whose safety does not depend on guessing what the residual contains.

## Evidence

- #47 — `SimpleReloadInstance` barrier/turn critical-path profiler; correct non-overlapping scheduler semantics.
- #75 — historical ordered post-turn apply-tail attribution.
- #78 — rejected MCEF/resource-preparation overlap due contention/end-to-end regression.
- #83 — rejected inter-panorama rolling window.
- #85 — `LevelRenderer` wall/current-thread CPU split and hardware-sensitive residual.
- #95 / #102 — renderer defer rejected after black/frozen physical menu.
- #109 — FancyMenu stock wait CPU diagnostic.
- #111 — fixed-selection physical variance and `phase_variance.py`.
- #113 / merged evidence #115 — process CPU/GC/memory snapshots and run 021.
- #116 — exact FancyMenu 3.9.0 usable-menu/preload lifecycle no-go.
- #117 — rejected cooperative wait after hosted exact-pack 3x3 regression.
- #118 — shader/Voxy physical probe rejection.
- `docs/research/exact-pack-ci.md` — hosted-vs-hardware gate contract.
