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

- [Client model / ModelManager pipeline](model-pipeline.md) — historical #13/#14/#35/#36/#37/#47 evidence, the validated #55 blockstate matcher, and rejected shallow approaches.
- [Deep ModelManager follow-up — 2026-08-31](model-pipeline-deep-2026-08-31.md) — 97.60% recursive bake-cache hit rate, exclusive bake-cost attribution, 10.86M blockstate variant tests, and the indexed-matching architectural hypothesis.
- [Post-promotion ModelManager residuals](modelmanager-post56-residuals.md) — PR #57 exact-pack gate/cost distribution after production promotions, rejected generated-item span-topology cache, and the short-scope material-resolution hypothesis.
- [Decocraft 3D item / item-sprite elision — 2026-09-02](decocraft-3d-item-sprite-elision-2026-09-02.md) — exact 3.0.11 static audit, guarded 2D-item-to-3D model remap, pre-supplier atlas elision, resource-pack fail-open rules, and exact-pack validation criteria.
- [Indexed blockstate variant matching](blockstate-indexed-matching.md) — PR #55 exact-pack validation of the indexed replacement for the 1.21.1 O(variants × possible states) candidate scan, including 110,053 stock-equivalent variants with zero mismatches.
- [Production optimizations](production-optimizations.md) — startup optimizations that crossed the evidence bar and are intended to live in integration.
- [Mixin / ModLauncher transformation pipeline](mixin-pipeline.md) — #41/#42/#43/#46/#48 evidence, including the rejected generic side-load cache, confirmed-but-irrelevant ClassInfo negative-cache bug, and rejected external ASM writer-tail target.

## Project rule

A large count reduction is not sufficient evidence of startup improvement. BootOptim optimizes time-to-main-menu, so experiments must ultimately be judged by their contribution to the real critical path. CPU work that is cheap per call or hidden under another concurrent gate can be worth documenting without being worth shipping.
