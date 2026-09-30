# Manual reload: Decocraft corner V2 revalidation

Status: **ACTIVE EXPERIMENT — not promoted**

## Current authority and target

Integration was refreshed to `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`
on 2026-09-29 and again on 2026-09-30. PR #296 rebases the exact V2
implementation from #224 onto that tree; runtime head is `77f33e8`.
It changes no resource selection, callback order, model publication, worker
scheduling or GL ownership. The switch remains default-off.

Physical fast-PC RRLS diagnostic F3+T request-to-future-completion walls were
19.389 / 17.647 / 17.663 seconds. ModelBakery was constructed at
9.687 / 8.080 / 8.199 seconds and bake ended at 14.941 / 13.263 / 13.321
seconds from ModelManager's own origin. These are overlapping milestones,
not durations to sum or an A/B.

The first manual-reload JFR has 117 resource-worker bake-window samples with
Decocraft frames; 40 samples have `BlockbenchBakery.applyElementRotation`
as leaf. Other hot leaves include FerriteCore quad deduplication and Sodium
quad metadata. Stack-presence sample counts are not exclusive milliseconds.
V2 therefore targets a real residual, but cannot eliminate the whole delay.

## Historical gates

- #224's V1 failed final geometry equivalence. V2 preserves the receiver-side
  effects of `applyElementRotation` and skips only repeated position math.
  Verify-only mode previously found 2,527,029 exact raw-XYZ matches and zero
  mismatches. One later hosted pair matched final aggregate output and had a
  roughly 990-ms reload-to-FancyMenu improvement; it was not repeated proof.
- #224's laptop pair is inconclusive, with a corrected -2.325-second
  post-entrypoint direction. The earlier +12.160-second process-to-menu
  difference was dominated by pre-feature time. Do not call either a win
  or a causal regression.
- #265 already performed #261's no-scheduling BakePlan census: zero proven
  safe roots and zero safe exclusive CPU under its strict effect proof.
  Do not describe that census as an outstanding prerequisite or repeat it.
- #221/#257's mutable CIT lifecycle remains NO-GO for a broad cache.
  #295's count-driven multipart collector was not promoted.

## First rebased hosted comparison

[Exact-pack paired run 36502110846](https://github.com/wachipayox/BootOptim/actions/runs/36502110846)
ran three pairs with alternating order. Build, ordinary startup and pinned
Decocraft bytecode matcher passed. All six processes reached the menu with
the expected packs, 8192x8192x2 block atlas and zero BootOptim Mixin errors.

Candidate-minus-control within-pair menu deltas were +1.917 / +3.767 /
+2.296 seconds; reload-to-FancyMenu deltas were +0.645 / +2.306 / +1.574
seconds. The common measurement origin is the benchmark startup marker and
endpoint is main menu; reload-to-FancyMenu is a separate interval.

This run does **not isolate the uninstrumented optimization**. Both sides
enabled `profileDecocraftCornerRotationReuse`, but only candidate executes
roughly fifteen million extra LongAdder increments across redirects,
prepares, reuse candidates, reused outputs and skipped stages. The candidate
does activate all expected 2,527,029 reuses with zero fallback. The negative
timing is retained, not ignored; its diagnostic overhead is a material
different premise for a low-telemetry repeat, not grounds to claim a win.

The sprite-local-q1e6 output fingerprint is also not stable in this run:
all three candidates and control 3 match the historical aggregate, while
controls 1 and 2 differ. All have 3,527 models / 964,046 quads and identical
metadata. Source normalizes final float atlas UVs using sprite origin/span;
rounding sensitivity or actual stock nondeterminism is a hypothesis, not
established cause. Aggregate equality cannot authorize promotion until its
drift is understood or an independent same-execution raw output comparison
passes. No visual-equivalence claim is made from CI completion.

## Next gate

Repeat the same paired comparison with detailed corner profiling disabled
on both sides. Fixed candidate mode/version completion markers remain.
Use a separate verify-only smoke if runtime proof of the raw corner key is
needed; it is not a timed comparison. Any coherent hosted reduction still
requires paired physical in-world F3+T evidence before a manual-reload claim.

Artifacts are preserved under
`C:/BootOptimBench/rrls-menu-ready-stage-20260924/decocraft-v2-rebase-20260929/hosted/`.
The user's instance and laptop were not staged or launched in this experiment.

PR: [#296](https://github.com/wachipayox/BootOptim/pull/296).

## 2026-09-30: low-telemetry repeat

[Paired run 36686757130](https://github.com/wachipayox/BootOptim/actions/runs/36686757130)
completed three pairs with corner counters/fingerprints disabled. Within-pair
candidate-minus-control menu deltas were -1.272 / -1.491 / +2.022 seconds;
reload-to-FancyMenu deltas were -1.512 / -0.854 / +0.662 seconds. Panorama
differences were -0.401 / +0.294 / +0.406 seconds. All runtime contracts
passed and each candidate's fixed marker reports the supported V2 mode.
These results are promising in two pairs (including candidate-first) but
mixed. Do not subtract panorama wall to manufacture an independent bake
speedup or claim that physical F3+T became faster. Counts remain zero by
design in this benchmark. Artifacts are under the adjacent `low-telemetry/`
directory. No promotion or physical installation follows from this result.

An independent semantic diagnostic now reports final raw non-UV vertex data
plus metadata, and exact logical U/V inputs to the original sprite mapping
calls. It invokes each original mapping once in place. This separates raw
geometry/logical UV from the old inverse-atlas/q1e6 fingerprint, which can
be sensitive to atlas placement and float rounding. New hooks are disabled
with detailed profiling, and the matcher checks the pinned single U/V
callsite. A profiled control/candidate pair is semantic-only; its timing is
not an economic comparison.

[Semantic paired run 36688442846](https://github.com/wachipayox/BootOptim/actions/runs/36688442846)
passes build, startup, pinned matcher and effective-pack contracts. Both
sides produce 3,527 models / 964,046 quads, identical metadata and identical
final raw non-UV fingerprints (`67384ae4e5ad362c`, `11cf5abe1c6e146a`). All
7,712,368 logical UV calls match the input aggregate (`247788fdb90bc096` sum;
the XOR cancels to zero on both). Candidate executes 2,527,029 V2 reuses with
zero shape/alias fallbacks. The old inverse-atlas/q1e6 fingerprints differ
even though these independent raw geometry and pre-atlas UV aggregates
match. This confines the observed fingerprint drift to post-atlas UVs / the
inverse normalization; it is not evidence of changed position math.

Aggregate hashing is a semantic guard, not a pixel-perfect proof of every
model's ordered output. The mapping invokes the original vanilla method
once; atlas placement can vary between runs. Prior exact corner verify-only
results remain relevant, and representative physical in-world visual checks
are still required before promotion. Detailed instrumentation in this pair
is intentionally excluded from performance conclusions. Artifacts are
under the adjacent `semantic/` directory. The low-telemetry mixed timing is
still the current economic result; no measured physical F3+T reduction yet.

## Repeated-reload economic gate

The branch now includes #297's validated default-off hosted reload harness
and strict separate-generation report. Its independent smoke passed two
stock reloads in one JVM; the optional AnalogAudio welcome is declined
without a config change. This allows V2's economic gate to measure actual
repeated reloads, instead of extrapolating first-startup improvements to
F3+T. The next paired run has three alternating same-VM process pairs,
two repeats per process, detailed corner telemetry disabled on both sides,
and only V2 substitution true/false differing. The stage/end completion
marker remains. No FerriteCore capacity candidate is enabled in this V2
comparison. Physical in-world visual and timing gates remain outstanding.
