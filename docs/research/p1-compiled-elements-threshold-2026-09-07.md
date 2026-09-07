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
  callback guards, stock `FaceBakery`, sprite getter and model-builder order.

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
