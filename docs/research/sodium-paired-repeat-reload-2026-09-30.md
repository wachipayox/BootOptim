# Sodium classifier: paired repeated reload diagnostic

Integration origin: `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
Candidate source: [PR #299](https://github.com/wachipayox/BootOptim/pull/299).
This branch is a diagnostic, not a production promotion.

## Established evidence

[Enabled exact-pack smoke 36701325510](https://github.com/wachipayox/BootOptim/actions/runs/36701325510)
reached the title at 69.041 seconds from the defined startup marker, with
the Sodium activation marker for `0.8.12-beta.1+mc1.21.1`, the enabled pack
contract intact and zero BootOptim Mixin failures. This is a runtime gate,
not a performance comparison. The independent exact-binary checker passed
2,410,296 cases. Three CPU microbenchmarks were mixed; no saving is established.

## Comparison contract

Reuse the validated [PR #297 harness](https://github.com/wachipayox/BootOptim/pull/297)
without changing resource-reload scheduling. Each process reaches the startup
endpoint, waits for the stock overlay to clear and performs two stock menu
reloads. Candidate and control run on the same hosted VM, with alternating
process order across three fresh VMs. Both enable the same diagnostic counters.
The only behavior difference is `boot_optim.sodiumAxisQuadFlags`.

`boot_optim.profileSodiumQuadBake=true` adds two monotonic clock reads per
`ModelBakery.bakeModels` invocation and a thread-local eligible-quad counter.
The counter records exact BakedQuad instances with the optional coordinate
bridge and a non-null face, on the bake invocation's thread only. It is not
an all-thread count and cannot be interpreted as exclusive classifier time.
There are no per-quad clocks, logs, stack traces or JFR recording. Both variants
pay the same counting overhead. Normal interactive sessions do not automate
reloads and all instrumentation defaults off.

Markers declare `origin=bakeModels_enter endpoint=bakeModels_return`, generation
ordinal, whole bake wall time and eligible calls. Generation 1 is the initial
reload; ordinals 2 and 3 match repeat requests 1 and 2. Failed reloads, missing
markers, unmatched call counts or ambiguous generations invalidate a comparison.
An exceptional bake has no return marker; the next entry replaces its thread
context and the failed run is rejected, rather than inventing a duration.

Judge paired same-ordinal request-to-future completion first, bake wall second,
and startup independently. Inclusive listener durations must not be summed.
The hosted menu reload is a software surrogate, not physical in-world F3+T.
Even a positive hosted result needs representative physical visual validation.

## Other bounded investigations

The UnionFS subagent left a reproducible [path-conversion audit](unionfs-path-normalization-triage-2026-09-30.md):
74,912 cases with no differences. Its implementation boundary is MC-BOOTSTRAP;
do not add an intrusive loader patch for the unquantified small saving.
The generated-item subagent found a possible per-layer constant hoist, but
left no code or validated result before its usage limit. Pixel-row memoization
would change callbacks to `SpriteContents.isTransparent`; it is not approved
by the existing exact-class guard alone. Both investigations remain bounded;
neither constitutes a shipped optimization.

## Status

Local `gradlew.bat build --no-daemon` passed, including the packaged bootstrap.
All 11 repeated-reload parser checks passed using
`python test_repeat_reload_summary.py` from `scripts/exact-pack`.
Hosted paired evidence is recorded below. Do not merge the diagnostic mechanism
or claim a reload win from the smoke or from fewer arithmetic operations.

## Completed comparison: run 36718631775

[Actions](https://github.com/wachipayox/BootOptim/actions/runs/36718631775)
passed build, client startup and all three paired exact-pack VMs at diagnostic
commit `b691584d`. All six processes completed initial plus two repeated
reloads. Exactly three bake markers appear per process, each with 6,280,704
eligible calls; candidate activation appears once in each candidate and never
in controls. Reload origins/endpoints and ordinal checks passed. The fixture
contract passed, every block atlas remains 8192x8192x2 and there are zero
BootOptim Mixin failures. This proves runtime compatibility in the hosted
software pack, not visual in-world equivalence.

Candidate minus control; negative means shorter. Times in milliseconds:

| VM pair | Process order | Repeat 1 bake | Repeat 2 bake | Repeat 1 complete reload | Repeat 2 complete reload |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | control then candidate | +19.289 | -41.778 | -3068.073 | -338.508 |
| 2 | candidate then control | -401.246 | -325.418 | +154.722 | +18.958 |
| 3 | control then candidate | -253.274 | -208.293 | -1180.107 | -364.118 |
| Paired median | | -253.274 | -208.293 | -1180.107 | -338.508 |

Unpaired repeat medians (control/candidate): repeat 1 complete reload
31.979/30.987 seconds; repeat 2 28.548/28.209 seconds. The whole-reload deltas
are positive in the reverse-order pair and negative in both candidate-second
pairs, so an order/warm-run effect remains a confound. In pair 1, first bake
actually regresses 19 ms while the complete reload improves 3.068 seconds;
that entire improvement cannot be attributed to classifier arithmetic.
The bake improvements in five of six repeat intervals are encouraging but
small, with observed variability. They are whole bake wall time, not exclusive
classifier CPU. Initial bake paired median delta is -68.452 ms; startup paired
median is -103 ms, with individual deltas +1892/-1683/-103 ms. No coherent
startup win is established.

Disposition: keep PR #299 default off and unpromoted; it is a small promising
candidate, **not an established end-to-end reload win** and not a solution to
the physical 17–19 second F3+T delay. Do not close this CPU-sensitive front
merely from its small hosted value. Retain the flag for the eventual physical
low-end comparison once the user authorizes that hardware; do not request or
perform laptop runs now. Diagnostic PR #300 stays separate from production.

Downloaded artifacts and checked interval/count JSON are under
`C:\BootOptimBench\analysis-reload-20260930\sodium-paired-36718631775`.
Reproduce by pairing `paired-results/{control,candidate}-N/result.json` and
extracting all `BOOTOPTIM_SODIUM_QUAD_BAKE` markers from their `latest.log`;
reject missing/duplicate ordinals, mismatched candidate booleans or counts,
invalid repeat origins and nonzero BootOptim errors before computing deltas.
