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

Every startup comparison must also record exactly where its clock starts and stops. The control and candidate must use the same process/BootOptim origin marker and the same main-menu endpoint; launcher/Gradle preparation, fixture staging, stale JVM prefixes, and post-menu work must remain outside that field or be reported separately. Missing or duplicated boundary markers make the result invalid/inconclusive rather than evidence of a win.
5. Compatibility / semantic risks introduced by the experiment.
6. Result and reason for promotion, limitation, or rejection.
7. Reopening criteria: what new evidence would make the same direction worth revisiting.
8. Links to the relevant profiling/experiment PRs.

## Research index

- [Boot pipeline program — objective and methodology](boot-pipeline-program-2026-09-08.md) — active architectural program: structured boot trace/sidecar, incremental mod-scaling matrix, prepare/commit redesign, invasive NeoForge/Minecraft changes with stock fallback, and menu plus in-world validation.
- [Hosted exact-pack startup CI](exact-pack-ci.md) — pinned exact software-pack fixture, Linux/Xvfb/llvmpipe surrogate boundaries, deterministic MCEF setup, PR-body A/B protocol, and real-hardware gate rules.
- [FancyMenu final fork validation on the physical laptop — 2026-09-06](fancymenu-final-fork-validation-2026-09-06.md) — corrected option-enabled pair confirms the retained active-layout filter still saves 7.642 s on the laptop; the earlier +1.047 s all-source pair was invalid for that claim, while generic full-list prelaunch remains no-go for this pack.
- [Production optimization retention audit — 2026-09-03](production-retention-audit-2026-09-03.md) — verifies the six pre-MCEF production mechanisms are still physically present and unchanged, records exact mixin/helper blobs and latest runtime markers, fixes two catalog omissions, and records the two independent current-head MCEF 3×3 confirmations.
- [Client model / ModelManager pipeline](model-pipeline.md) — historical #13/#14/#35/#36/#37/#47 evidence, the validated #55 blockstate matcher, and rejected shallow approaches.
- [Deep ModelManager follow-up — 2026-08-31](model-pipeline-deep-2026-08-31.md) — 97.60% recursive bake-cache hit rate, exclusive bake-cost attribution, 10.86M blockstate variant tests, and the indexed-matching architectural hypothesis.
- [Post-promotion ModelManager residuals](modelmanager-post56-residuals.md) — PR #57 exact-pack gate/cost distribution after production promotions, rejected generated-item span-topology cache, and the short-scope material-resolution hypothesis.
- [Decocraft 3D item / item-sprite elision — 2026-09-02](decocraft-3d-item-sprite-elision-2026-09-02.md) — rejected #79 experiment: 3,192 verified model/sprite removals, laptop end-to-end tie, hosted 3×3 regression, atlas-environment boundary, and reopening criteria.
- [FancyMenu rolling panorama window — 2026-09-02](fancymenu-panorama-window2-2026-09-02.md) — rejected #83 follow-up: window=2 successfully launched 18 next panoramas but moved the hosted panorama median only ~127 ms and left reload→FancyMenu effectively unchanged; production six-face overlap remains retained.
- [FancyMenu initial-layout resource preload — 2026-09-05](fancymenu-initial-layout-preload-2026-09-05.md) — retained in the user-owned `3.9.0-wedit` fork after the physical control-002/candidate-006 pair; it stages the title-screen random selection and skips 21 of 22 unselected panorama/slideshow sources during the first reload.
- [FancyMenu initial-selection coherence — 2026-09-07](fancymenu-initial-selection-coherence-2026-09-07.md) — confirmed why the staged title-screen random pick could still be discarded at live construction (`isNewMenu` reset), and records the fail-open direct-fork fix pending PC visual validation.
- [FancyMenu cooperative wait in the user fork — 2026-09-06](fancymenu-cooperative-wait-fork-2026-09-06.md) — opt-in hybrid spin/park candidate after the physical busy-spin evidence; physical pair and CPU probe confirm CPU reduction but no time-to-menu win, so it remains no-go by default and is deliberately distinct from rejected BootOptim #117.
- [FancyMenu global panorama prelaunch — 2026-09-06](fancymenu-global-panorama-prelaunch-2026-09-06.md) — hosted 3x3 A/B confirms a ~2.67 s full-list win by overlapping all panorama producers, but the current final pack keeps one selected source and already overlaps its six faces; no additional production gain is expected.
- [MCEF initial resource-reload overlap — 2026-09-02](mcef-initial-reload-overlap-2026-09-02.md) — rejected #78 experiment: native CEF/resource-preparation overlap made CEF ~2.9x slower and regressed laptop post-entrypoint TTMM by ~10.2 s; first-consumer/lazy initialization remains a materially different premise.
- [MCEF first-consumer defer — 2026-09-03](mcef-first-consumer-defer-2026-09-03.md) — validated #88/#89 premise and production #90 confirmation: keep the real MCEF lifecycle but defer CEF until an actual consumer; the original hosted 3×3 improved ~1.994 s and two independent 3×3 campaigns on production head `d45df21b` independently improved ~1.051 s and ~2.202 s.
- [P2 WebDisplays/MCEF physical run — native OpenAL crash — 2026-09-06](p2-webdisplay-openal-native-crash-2026-09-06.md) — the interactive world-entry run ended in a Windows `javaw.exe` BEX64 crash inside LWJGL `OpenAL.dll` (`0xc0000409`), with no Minecraft crash report; the same laptop had many identical prior crashes, so the run is not attributable to BootOptim.
- [P0.2 physical-laptop variance harness — 2026-09-06](p0.2-variance-harness-2026-09-06.md) — corrected harness run reaches the menu in 350.330 s and attributes the critical path to the CPU/GC-heavy resource/model pipeline, especially ModelManager; one run is insufficient to identify the run-to-run variance distribution.
- [Laptop resource-reload executor experiment — 2026-09-07](resource-reload-pool-laptop-2026-09-07.md) — the two-worker HDD policy reached the real reload executor, but the clean paired candidate was 4.134 s slower end-to-end and 9.606 s slower through the reload; detector/cache findings are retained, the pool cap is rejected.
- [LevelRenderer resource-reload split — 2026-09-03](levelrenderer-reload-split-2026-09-03.md) — #85 attribution: current slow-laptop listener wall is ~2.061 s, dominated by ~1.458 s `entity_outline` PostChain load; the old ~6.507 s trace is not a valid current savings ceiling, so defer work is secondary to MCEF.
- [Indexed blockstate variant matching](blockstate-indexed-matching.md) — PR #55 exact-pack validation of the indexed replacement for the 1.21.1 O(variants × possible states) candidate scan, including 110,053 stock-equivalent variants with zero mismatches.
- [Production optimizations](production-optimizations.md) — startup optimizations that crossed the evidence bar and are intended to live in integration.
- [Mixin / ModLauncher transformation pipeline](mixin-pipeline.md) — #41/#42/#43/#46/#48 evidence, including the rejected generic side-load cache, confirmed-but-irrelevant ClassInfo negative-cache bug, and rejected external ASM writer-tail target.
- [Five-front replay triage — 2026-09-08](five-front-replay-triage-2026-09-08.md) — #186/#188 pre-sizing rejected after 4,096-model allocation attribution and noisy exact-pack A/B; #187 ZIP reread cache has no real-pass hits; #185 wrapper discovery is too small; #184 exposes an 8.9 s post-FancyMenu serial listener queue whose callsites are the next gate.

## Project rule

A large count reduction is not sufficient evidence of startup improvement. BootOptim optimizes time-to-main-menu, so experiments must ultimately be judged by their contribution to the real critical path. CPU work that is cheap per call or hidden under another concurrent gate can be worth documenting without being worth shipping.
