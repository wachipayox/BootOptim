# BootOptim research ledger

This directory is the durable memory for startup-performance research. Its purpose is to prevent the project from repeatedly rediscovering the same bottleneck, optimization idea, or negative result after profiling branches are closed or left unmerged.

Before starting a new optimization experiment, check this ledger and the linked PRs. A previously rejected direction may be reopened only when the new hypothesis changes a material premise (different critical path, different implementation, different version, or evidence that the old measurement no longer applies). "The phase is still slow" by itself is not enough to repeat an experiment that already showed poor wall-clock leverage.

Repository agents must also read the root [`AGENTS.md`](../../AGENTS.md) and inspect both open and closed PR history for the subsystem before changing code. A successful experiment can remain unmerged; the integration tree is the authority for what is actually present.

## Status vocabulary

- **PROFILED** — diagnostic evidence only; no optimization conclusion yet.
- **ACTIVE** — currently being investigated.
- **VALIDATED** — mechanism and real-pack benefit were validated strongly enough to remain a production candidate.
- **LIMITED** — optimization works locally/in a subphase, but wall-clock leverage is small or highly overlapped.
- **REJECTED** — do not repeat the same mechanism without a new premise.
- **SUPERSEDED** — a later experiment or profiler replaced the old interpretation.

## What every research entry must record

1. Target phase and why it was believed to be on the startup critical path.
2. Exact hypothesis and mechanism, not just the class or method name.
3. Measurement environment and the important before/after numbers.
4. Whether the measurement is CPU time, inclusive wall time, or actual critical-path wall time.
5. Compatibility / semantic risks introduced by the experiment.
6. Result and reason for promotion, limitation, or rejection.
7. Reopening criteria: what new evidence would make the same direction worth revisiting.
8. Links to the relevant profiling/experiment PRs.

## Research index

- [Laptop shader fallback and Voxy-save variance — 2026-09-05](laptop-shader-voxy-variance-2026-09-05.md) — **REJECTED** physical diagnostic: five deliberate shader-capability failures cost 155 ms wall/62.5 ms CPU, while Voxy saved once in 5.5 ms with no concurrency; neither is an actionable startup target.

- [Post-FancyMenu preload critical-tail audit — 2026-09-05](post-fancymenu-critical-tail-audit-2026-09-05.md) — **LIMITED / NO-GO**: current integration has coarse preload/reload/title endpoints but lacks the #47-style scheduler barrier and first-present boundary needed to attribute the variable physical tail; no safe runtime optimization is identified.
- [Fixed laptop FancyMenu wait-CPU evidence — 2026-09-05](laptop-fancymenu-wait-cpu-variance-2026-09-05.md) — full-pack physical diagnostic proving material current-thread CPU in stock FancyMenu waits on the 2C4T software-renderer laptop; candidate remains gated by semantic and hosted A/B tests.
- [Full-resource laptop variance](laptop-fullpack-variance-2026-09-05.md) — validated workload, control/candidate/control timing partitions, and the fixed-configuration causal diagnostic gate; no physical MCEF win established.

- [FancyMenu usable-menu / resource-preload boundary — 2026-09-05](fancymenu-usable-menu-resource-boundary-2026-09-05.md) — exact 3.9.0 bytecode proves panorama/slideshow/native-video consumers and title audio can tolerate not-ready resources, but `ResourcePreLoader.preLoadAll` exposes no safe continuation that preserves ordered start/wait/timeout/error semantics; BootOptim runtime defer rejected.

- [Laptop resource-selection audit](laptop-resource-selection-audit-2026-09-05.md) — retained laptop runs omitted external ZIPs; correct the workload contract before exact-pack performance claims.
- [VoxelShaper limited safe-domain closure — 2026-09-05](voxelshaper-safe-domain-2026-09-05.md) — **REJECTED #110**: `<=2` boxes is strict-safe but owns only 0.097% of measured stock fold CPU; the broader epsilon-stable domain covered about one quarter of stock fold CPU but failed strict equivalence with two natural and one exact-dyadic adversarial counterexample. Coverage is not TTMM savings; no A/B or laptop run was justified.
- [MoreCulling reload-local translucency reuse — 2026-09-05](moreculling-translucency-reuse-2026-09-05.md) — **LIMITED / REJECTED #119**: exact reload-local identity+bounds semantics passed smoke, but two hosted 3×3 campaigns contradicted each other (+15.005 s and −2.774 s TTMM); no stable end-to-end benefit and no laptop gate.

- [Hosted exact-pack startup CI](exact-pack-ci.md) — pinned exact software-pack fixture, Linux/Xvfb/llvmpipe surrogate boundaries, deterministic MCEF setup, PR-body A/B protocol, and real-hardware gate rules.
- [Client model / ModelManager pipeline](model-pipeline.md) — historical #13/#14/#35/#36/#37/#47 evidence, the validated #55 blockstate matcher, and rejected shallow approaches.
- [Deep ModelManager follow-up — 2026-08-31](model-pipeline-deep-2026-08-31.md) — 97.60% recursive bake-cache hit rate, exclusive bake-cost attribution, 10.86M blockstate variant tests, and the indexed-matching architectural hypothesis.
- [Post-promotion ModelManager residuals](modelmanager-post56-residuals.md) — PR #57 exact-pack gate/cost distribution after production promotions, rejected generated-item span-topology cache, and the short-scope material-resolution hypothesis.
- [Physical resource-reload / ModelManager boundary audit — 2026-09-06](physical-resource-reload-boundary-audit-2026-09-06.md) — **ACTIVE DIAGNOSTIC**: #69 physical evidence points to atlas sprite/resource preparation rather than final stitch packing, but active-layout-006 lacks comparable low-overhead boundary attribution; one hosted-gated physical run is defined.
- [Decocraft 3D item / item-sprite elision — 2026-09-02](decocraft-3d-item-sprite-elision-2026-09-02.md) — rejected #79 experiment: 3,192 verified model/sprite removals, laptop end-to-end tie, hosted 3×3 regression, atlas-environment boundary, and reopening criteria.
- [FancyMenu rolling panorama window — 2026-09-02](fancymenu-panorama-window2-2026-09-02.md) — rejected #83 follow-up: window=2 successfully launched 18 next panoramas but moved the hosted panorama median only ~127 ms and left reload→FancyMenu effectively unchanged; production six-face overlap remains retained.
- [MCEF initial resource-reload overlap — 2026-09-02](mcef-initial-reload-overlap-2026-09-02.md) — rejected #78 experiment: native CEF/resource-preparation overlap made CEF ~2.9x slower and regressed laptop post-entrypoint TTMM by ~10.2 s; first-consumer/lazy initialization remains a materially different premise.
- [MCEF gameplay-boundary audit — 2026-09-05](mcef-gameplay-boundary-audit-2026-09-05.md) — source-validates serial pre-gameplay preparation points before local `MinecraftServer.spin` and remote connector startup, identifies PR #90 callback-reentry/30 s wait risks, and defines hosted + Windows gates before claiming first-use WebDisplays is hitch-free.
- [LevelRenderer resource-reload split — 2026-09-03](levelrenderer-reload-split-2026-09-03.md) — #85 attribution: current slow-laptop listener wall is ~2.061 s, dominated by ~1.458 s `entity_outline` PostChain load; the old ~6.507 s trace is not a valid current savings ceiling, so defer work is secondary to MCEF.
- [Indexed blockstate variant matching](blockstate-indexed-matching.md) — PR #55 exact-pack validation of the indexed replacement for the 1.21.1 O(variants × possible states) candidate scan, including 110,053 stock-equivalent variants with zero mismatches.
- [Production optimizations](production-optimizations.md) — startup optimizations that crossed the evidence bar and are intended to live in integration.
- [Mixin / ModLauncher transformation pipeline](mixin-pipeline.md) — #41/#42/#43/#46/#48 evidence, including the rejected generic side-load cache, confirmed-but-irrelevant ClassInfo negative-cache bug, and rejected external ASM writer-tail target.

## Project rule

A large count reduction is not sufficient evidence of startup improvement. BootOptim optimizes time-to-main-menu, so experiments must ultimately be judged by their contribution to the real critical path. CPU work that is cheap per call or hidden under another concurrent gate can be worth documenting without being worth shipping.
