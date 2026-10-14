# Runner variance: calibrated within-JVM trials — 2026-10-01

Status: **TOOLING / PROPOSED NEXT EVIDENCE GATE**, not a Minecraft optimization.
Source baseline: integration `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
The offline analyzer and its tests are implemented. The expanded runtime plan
and structured exporter have **not** been installed in a client or validated in
hosted runtime. No laptop state is touched by this work.

## Confirmed problem and existing solutions

[The 2026-09-07 ledger](github-fresh-vm-variance-2026-09-07.md) already established
that fresh hosted VMs can encounter material I/O wait and memory/swap pressure.
`run_paired.py` already runs alternating control/candidate processes on the same
VM and retains `vmstat` telemetry. This removes host allocation as an arm
difference, but does not remove warm page-cache, JIT or heap-generation bias.
It is not a cold-start baseline. Reimplementing same-VM pairs is unnecessary.

[PR #304](https://github.com/wachipayox/BootOptim/pull/304) already improves on
that for reload-local candidates: one JVM, full stock resource generations,
mask changes only between completed reloads, opposite-order adjacent pairs,
whole-bake worker CPU, whole-reload future wall, GC and Ferrite capacity primers.
Its existing strict parser and resource-selection check remain authoritative
for that frozen campaign. Do not replace or mutate a running campaign.

[PR #305](https://github.com/wachipayox/BootOptim/pull/305) illustrates why more
fresh VMs alone are insufficient. Semantic run `36779506692` preserved all
dependency results, identities and iteration orders across 147,906 attempts.
Performance run `36784238631` had nominal menu median −909 ms and reload median
−1,502 ms, but the actual modified owner (`loadAllBlockStates`) had wall
**+143.897 ms** and worker CPU **+142.354 ms**. Both third arms became faster
generally. This is not proof of saved work and the candidate is not promoted.

## What changes in the next protocol

The next runtime campaign should reuse #304's lifecycle, clock ownership and
strict generation checks, with a **single feature** and an explicit exporter
rather than another per-model profiler. Add the following bounded plan per JVM:

1. Run the initial startup and a predeclared number of full control warmups.
   Record them separately; they are not startup samples or measured blocks.
2. An A/A calibration block: four control reloads, labeled as hypothetical
   control/candidate in `ABBA` order, without enabling the candidate.
3. Two genuine four-observation blocks: `ABBA`, then `BAAB`. A means control,
   B means candidate. On the second independently started JVM, reverse which
   genuine order runs first. Mode changes occur only after full future success
   and stable presentation; custom partial reloads are forbidden.
4. A second A/A calibration block in `BAAB` order. The calibration brackets
   genuine observations rather than assuming a noise floor measured only early
   also describes a later, fuller heap.
5. Retained-state candidates require a same-mode conditioning generation before
   **every** observation, including A/A. FerriteCore must preserve this primer.
   Unknown/carrying custom-loader state requires an invalidation proof first.
6. Reproduce this plan in a second independently started JVM. Each observation
   is compared within its own JVM; do not pair one JVM's candidate with another
   JVM's control. Give each JVM equal decision weight, not more weight merely
   because it yielded more records.

This is 16 measured/calibration reloads per JVM plus explicit warmups; retained
state adds 16 primers. On hosted hardware this is a targeted diagnostic for a
promising mechanism, not a mandatory all-candidate sweep on the HDD laptop.
The existing 21-reload laptop campaign remains frozen and useful as screening
evidence; do not relaunch it to adopt this method. Its winners proceed through
their already documented physical/uninstrumented gates.

## Why counterbalanced blocks help, and their limits

The contrast is the mean of B observations minus the mean of A observations
inside one block. Both arms have the same average ordinal position in `ABBA`
or `BAAB`, so a linear time trend cancels algebraically. Opposite block orders,
A/A before/after and another JVM expose residual warmup, heap-cycle and host
noise that two isolated differences can hide.

This does **not** cancel nonlinear JIT drift, variant-dependent cache carryover,
GC cycles, changing assets, network traffic or a phase-specific environment
disturbance. Capture JVM compilation time, GC, heap, `/proc` pressure and the
existing `vmstat` trace alongside generation boundaries when running. Do not
call `System.gc()`, force cache dropping, disable GC or subtract GC wall from
reload wall to make the result cleaner. Candidate allocation reductions and
their GC consequences are part of the mechanism.

Do not remove a slower candidate sample because its GC value is larger. A
resource fallback, wrong process/generation, unsupported CPU clock, shader
drift or predeclared environment-contract failure invalidates the campaign;
ordinary scheduler/GC noise remains in the distribution and its A/A bound.
If CPU/JIT drift remains large, more fresh-VM medians do not repair the premise:
inspect the source/compilation state or use an isolated faithful source-level
mechanism test first, retaining a later full-pack gate.

## Implemented offline gate

`scripts/exact-pack/analyze_balanced_trials.py` accepts schema 1 JSON. It neither
launches a client nor reads/writes the laptop. Required contract fields are:

```text
feature, source_sha, jar_sha256, pack_sha256, options_sha256,
jvm, jvm_args_sha256, owner_cpu_scope,
origin=reload_invocation, endpoint=future_completion,
minimum_cpu_ms, minimum_cpu_fraction,
wall_regression_ms, wall_regression_fraction,
requires_same_mode_primer
```

Each campaign has `id`, `host_id`, `pid`, `process_created`, the same effective
`contract`, `complete=true` and ordered `steps`. Every step records actual
`generation`, `kind`, effective `mode`, `success=true` and
`pack_selection_valid=true`. Kinds are `warmup`, `conditioning`, `measurement`
or `calibration`. All resource generations from the first recorded warmup
through completion must be contiguous, including primers. Startup generation
is validated separately by the existing startup/resource contract.

An observation additionally has `block` (string), `label` (control/candidate),
`owner_calls=1`, `environment_valid=true`, nonnegative finite `owner_cpu_ms`,
`reload_wall_ms`, and `gc_ms`. Calibration labels simulate the contrast but
**effective mode must remain control in all four observations**. The exporter
must verify clocks, pack state and environment from actual artifacts; these
booleans are assertions backed by that prior check, not evidence manufactured
by the analyzer. `owner_cpu_ms` covers one serial worker-owned method with two
CPU-clock reads. Parallel owners need a proven aggregation boundary first;
current-thread CPU cannot represent their total work.

Run:

```sh
python scripts/exact-pack/analyze_balanced_trials.py evidence.json --output decision.json
python -m unittest discover -s scripts/exact-pack -p 'test_*.py' -v
```

The gate rejects changed effective contracts, ambiguous processes, missing or
duplicated generations, failed futures/fallback, incomplete termination,
missing clocks, overlapping/reopened blocks, wrong mask and missing primers.
It labels fewer than two JVMs or missing either order/A/A calibration as
`insufficient_design`; absence of these fields in old data cannot be repaired
by fabricating new observations.

For each JVM it computes all genuine block contrasts and the largest absolute
A/A contrast. A CPU candidate supports a final uninstrumented gate only if
**every** genuine block in **both** JVMs saves more than the maximum of that
noise bound and the predeclared practical CPU threshold. Use the existing
screening thresholds of max(200 ms, 1% control CPU) unless a smaller effect has
an explicitly justified, predeclared retention policy. Full-reload wall uses
its own bound and max(1 s, 2% control wall). A repeatable wall regression vetoes
the candidate. CPU and full-reload wall directions are reported separately.

The output range is the observed block range, **not a confidence interval**.
This deliberately conservative small-sample screen is not a statistical
significance claim, cold-boot measurement or automatic production approval.
If CPU is stable but whole-reload wall remains noisy, report demonstrated CPU
removal with an unresolved critical-path effect. Never sum inclusive listeners.

## Static flags, cold boot and portability

Do not mutate a `static final Boolean.getBoolean` field by reflection to obtain
within-JVM treatment switches. An eligible candidate needs an explicit
diagnostic selector sampled at a full-generation boundary, with its caches
reset or same-mode primed. The selector's cost must affect both arms equally;
the final gate uses a fixed, uninstrumented build. PR #305 currently uses fixed
JVM flags; adapting it safely is separate work, not an ability this analyzer
adds automatically.

For candidates that cannot safely toggle, retain same-VM fresh-process paired
trials, preferably A/B/B/A launch order and explicit A/A controls with runner
telemetry. A warm-reload win still needs startup/first-world proof. Native,
GPU, storage/page-cache and Windows-specific changes still need physical
evidence; llvmpipe and `ActiveProcessorCount=4` do not reproduce the laptop.

## Candidate memory and disposition

Keep [#298 FerriteCore](https://github.com/wachipayox/BootOptim/pull/298): its
conditioned #304 hosted screening recorded CPU −679.951/−250.350 ms and reload
wall −410.454/−285.599 ms in opposite-order pairs. The within-block average is
CPU −465.150 ms, reload wall −348.026 ms. This is a promising **single** JVM
screen with primers, not a calibrated two-JVM result. Its physical campaign
and final uninstrumented gate remain pending/independent. Do not discard it
merely because this new stricter design did not exist for the old evidence.

Preserve #296 Decocraft V2, #299 Sodium flags, #301 generated-layer arithmetic
and #305 validated multipart union, with their current default-off flags and
their original evidence. #304's screening did not demonstrate a practical
coherent CPU benefit for the first three; #305's fresh-VM wall median is
inconclusive and its owner CPU went the wrong way. No candidate is silently
deleted, promoted, or relabeled as physically rejected. Reopen on a changed
mechanism, credible controlled CPU removal, or appropriate low-end evidence.

Local validation: ten offline tests exercise drift cancellation, sham-noise
rejection, wall/CPU disagreement, independent-JVM disagreement, retained-state
primers and invalid workload/process/clock evidence. This branch changes only
analysis tooling, its Build CI test invocation and this durable protocol.
No expensive exact-pack campaign is requested for documentation/tooling alone.

## Offline audit against existing artifacts

The analyzer was additionally invoked on the real #304 hosted
`candidate-trials-result.json` and all six #305 `result.json` files. It correctly
refused them as calibrated schema-1 evidence; neither old campaign contains
the required A/A blocks and independent within-JVM replication. Their existing
validity checks remain intact. The following #304 block contrasts were computed
from the existing two opposite-order pair deltas, not from new measurements:

| Candidate | One-block owner CPU B−A | One-block full-reload wall B−A |
| --- | ---: | ---: |
| Decocraft V2 | +210.478 ms | +1,428.256 ms |
| Sodium flags | +401.487 ms | +1,181.407 ms |
| Generated layer | −142.027 ms | +264.440 ms |
| Ferrite capacity | −465.150 ms | −348.026 ms |

These preserve the original screening interpretation. In particular, Ferrite
still deserves its pending physical/unenstrumented gate, while no old sample
is relabeled as a calibrated two-JVM proof. The CPU rows are the single
`bakeModels` owner measurements; no inclusive listener durations are added.
