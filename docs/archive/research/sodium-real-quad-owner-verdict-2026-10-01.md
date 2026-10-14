# Sodium actual classifier owner verdict — 2026-10-01

Status: **ACTIVE BOUNDED VALIDATION**, base integration `19afd955` including the
user's two-control/two-candidate segment-first policy. #299/#300/#304 reviewed.
The candidate algorithm/guards are unchanged, including twelve ordered virtual
reads, NaN/sentinel behavior and exact class/version fallback.

Previous whole-bake clocks included other owners; standalone audit used a
synthetic coordinate corpus. This probe captures up to 4096 actual pinned-pack
eligible BakedQuad references plus the original Direction, sampling every 2048
eligible classifier invocations during the initial generation. Capture does
not copy vertices or call any extra getters. It is diagnostic startup overhead,
so no startup or full-reload performance conclusion is permitted from this run.
The real initial reload must finish before any replay, and capture then stops.

Replay calls the **actual transformed Sodium getQuadFlags method** via one
pre-adapted MethodHandle in both arms. Thus the current mixin guard, exact
version lookup (warmed), interface dispatch, cancellation and original stock
method are exercised; this is not a handwritten substitute for Sodium bytecode.
Capture injection has higher priority and a cheap disabled-capture guard in
both arms. No collection lock is taken during replay. This instrumentation is
never a production promotion.

Every selected real quad first passes original/candidate flag equality. Twenty
alternating unmeasured warmup blocks precede exactly C1/B1/B2/C2 observations.
Each has the same approximately eight million calls over the same reference
corpus; two CPU reads per complete block and elapsed wall separately. Checksums
must match, clocks must be valid and the actual candidate activation must be
present. References are cleared before automatic shutdown; no world is entered.
No framebuffer/GL/render work is timed as classifier CPU.

Both opposite-order contrasts must reduce classifier-block CPU for retention;
otherwise retire this candidate under the current policy. A retained segment
still requires an uninstrumented semantic/visual runtime gate before production.
The artificial repetition does not predict the real reload's seconds saved,
callback side effects of unknown mods or its cold JIT behavior. Source changes
are math only, so a whole-startup delta is not the primary gate.

`check_sodium_replay.py` rejects wrong process/order, missing/malformed clocks,
corpus changes, mismatch checksums, absent activation and extra resource reloads.
Hosted validation uses full console after process exit. No laptop or PC instance
was staged by this probe. No startup/full-reload performance result is claimed.

## Completed hosted actual-owner result

[Exact-pack run 36841073385](https://github.com/wachipayox/BootOptim/actions/runs/36841073385)
passed on source `b0160453606c0cbeb97c56dae9592e5972d7563e`, as did package
and minimal-startup CI. The initial generation made 6283708 eligible calls;
the replay used 3069 real references/original directions with stock/candidate
flags equal before timing. Each measured block made exactly 7997814 calls and
returned checksum 21903430. No resource fallback or additional generation.

| Block | CPU ms | Elapsed ms | Cumulative GC ms |
| --- | ---: | ---: | ---: |
| C1 | 422.178 | 422.169 | 4511 |
| B1 | 360.310 | 360.344 | 4511 |
| B2 | 359.052 | 359.053 | 4511 |
| C2 | 421.993 | 421.998 | 4511 |

Both opposite-order contrasts save **61.868 / 62.940 ms CPU** (14.65% / 14.92%)
for the same warmed actual-owner workload. GC did not occur during these blocks.
The replay is evidence of lower classifier cost, not a measurement of actual
reload wall saved, cold JIT cost or a promise to shorten reload by this amount.
Other model owners and startup capture cannot be added to these savings.

**Segment disposition: retain.** Prepare a clean production change with the
unchanged compatibility/read-order guards; neither capture nor replay nor trial
masks may be shipped. Complete uninstrumented startup/pack/visual validation.
This bounded promotion replaces the historical indefinite default-off posture
of #299/#300 under policy #307; it does not merge the diagnostic branch.

Artifacts: `C:/BootOptimBench/analysis-reload-20261001/sodium-owner-36841073385`.
The checker originally divided nanoseconds into milliseconds but left `_ns`
suffixes on contrast keys. Corrected to `cpu_ms`/`wall_ms`; raw observations
retain actual `_ns`. This changes units labeling only, not the numerical verdict.
