# Strict Elements plan: allocation/threshold follow-up (2026-09-07)

Status: **EXPERIMENT / HOSTED GATE PENDING**

## Motivation

The first direct material plan (PR #170) was semantically clean in the
exact-pack smoke but regressed the hosted A/B: candidate median TTMM was
`92,288 ms` versus `90,191 ms` control (`+2,097 ms`), and the
reload-to-FancyMenu median moved by `+2,213 ms`. That result closes the
unbounded “compile every ordinary ElementsModel” implementation, not the
underlying possibility of a cheaper source-level fast path.

The implementation allocated multiple temporary `ArrayList`s, boxed shade
flags, copied unused `BlockElement` references, and retried compilation for
every ineligible model because a null plan was not distinguishable from an
uncompiled model. Those costs are especially harmful on a pack with many
small element models.

## Changed premise

This follow-up remains opt-in (`-Dboot_optim.compiledElementsMaterialPlan=true`)
and does not alter the stock path by default. It changes only the plan
construction strategy:

- count and validate faces before allocating the plan;
- refuse plans below a configurable face threshold, default `8`, via
  `-Dboot_optim.compiledElementsMaterialPlan.minFaces=N`;
- allocate exact-size primitive/reference arrays in one pass instead of
  temporary lists and boxing;
- remove an unused per-face `BlockElement` array;
- memoize a negative (ineligible/too-small) result so it is not recompiled on
  repeated bakes of the same `BlockModel`;
- retain the previous strict metadata, custom-geometry, root-transform and
  callback guards, and call the stock `BlockModel.bakeFace` route rather than
  bypassing it with a private `FaceBakery` invocation. Sprite lookup and
  model-builder order remain unchanged.

The candidate still does not persist models, move GL work, bypass NeoForge
callbacks, or change the default runtime. It must be treated as a new
experiment, not as evidence that PR #170’s regression was measurement noise.

## Decision gate

Run the hosted exact-pack A/B first with the default threshold and inspect the
candidate marker, atlas dimensions, Mixin errors and phase boundaries. A
candidate is not eligible for the laptop unless it beats control coherently in
critical-path wall time or demonstrates a large, independently measured
hardware-sensitive subphase reduction. Mixed signs or a small phase-only gain
remain inconclusive. If the thresholded plan still regresses, close this
implementation and keep the structural material-plan idea only as a future
source-level redesign.

Build validation: `./gradlew build` passed on the clean integration-derived
worktree before the hosted run.

## Hosted exact-pack result (run `34158346876`)

All six benchmark jobs completed and reported zero BootOptim Mixin errors. The
published aggregate artifact incorrectly said `Runs: 1` because the workflow
reused one artifact name per variant; the six individual artifacts were
recovered by artifact ID. The collision is fixed separately in PR #176 and is
documented in `docs/research/exact-pack-artifact-collision-2026-09-07.md`.

Recovered values (milliseconds):

| variant | repetition 1 | repetition 2 | repetition 3 | median |
| --- | ---: | ---: | ---: | ---: |
| candidate main-menu | 89,702 | 93,174 | 92,115 | 92,115 |
| control main-menu | 92,920 | 82,879 | 58,789 | 82,879 |
| candidate mod entrypoint | 30,110 | 30,579 | 31,291 | 30,579 |
| control mod entrypoint | 31,812 | 27,609 | 20,077 | 27,609 |
| candidate reload→FancyMenu | 42,293 | 43,979 | 42,059 | 42,293 |
| control reload→FancyMenu | 41,889 | 37,842 | 26,273 | 37,842 |
| candidate panorama | 4,456.9 | 4,633.8 | 4,132.2 | 4,456.9 |
| control panorama | 4,144.7 | 3,795.2 | 2,573.4 | 3,795.2 |

Candidate minus control medians were **+9,236 ms main-menu** (+11.1%),
**+2,970 ms mod entrypoint**, **+4,451 ms reload→FancyMenu**, and **+662 ms
panorama**. Candidate did not produce a coherent win in any critical-path
field; it was slower in every median. The control distribution is noisy, but
the candidate is not merely a small phase-only change and is not eligible for
the laptop gate.

Decision: reject this implementation and close the experiment PR. Keep the
allocation-light/thresholded material-plan concept as a future redesign lead,
not as a promotion or claim that the underlying bottleneck is solved. Do not
repeat the same direct plan premise without a different scheduling or ownership
model and a fresh semantic/performance hypothesis.
