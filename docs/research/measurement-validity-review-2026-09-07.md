# Startup measurement validity / decision audit — 2026-09-07

Status: **METHODOLOGY REVIEW / TOOLING ONLY**

Base authority: `agent/integration-current` @ `2bccf5f4fa221c78e286d052beb78636fa4c317b`.

This review changes no Minecraft/NeoForge/BootOptim runtime behavior. It audits
how completed startup results are admitted to comparison and adds an offline
sidecar checker. It intentionally does **not** duplicate PR #148's profiler,
read logs while a launch is timed, add JFR, sum task/listener durations, or
infer disk/GPU time from `wall - process CPU`.

## Executive finding

BootOptim's recent documentation contains most of the right invariants, but
the decision logic is still spread across PR prose and ad-hoc interpretation.
The hosted aggregator emits medians and paired deltas without a first-class
`valid / inconclusive / invalid` gate. PR #148's diagnostic parser has a useful
binary `valid` field for its own marker stream, but that is not a general
comparability schema and does not encode `inconclusive` for observer effect,
cache state or host pressure.

That leaves three recurrent inference hazards:

1. a structurally complete result can enter a median even when it is not
   comparable to its peer (different endpoint/origin/cache/instrumentation);
2. a clean-looking median can hide mixed-sign per-pair effects or a pressured
   runner; and
3. a valid *phase-attribution* diagnostic can be misused as a valid
   *production absolute-time* sample.

The fix is not another profiler. It is an offline admission gate with explicit
purpose and state.

## Clock and endpoint audit

### Process versus BootOptim origin

`main_menu=350330 ms` from JVM/process uptime and `post_mod_entrypoint=...`
from the BootOptim entrypoint are different estimands. They may both be useful,
but a candidate/control comparison must use the same origin.

For process-origin results, JVM uptime/monotonic time is authoritative. A wall
clock is only an identity cross-check. Java's `RuntimeMXBean.getStartTime()` is
an approximate JVM start time, so the existing PR #148 `5 s` wall/start/uptime
consistency tolerance is appropriate as a corruption/stale-process screen, not
as measurement resolution.

### `main_menu` versus `main_menu_presented`

These are not interchangeable endpoints. In the second low-noise physical
sample, opening is `355582 ms` and first presentation is `361195 ms`, a
`5613 ms` tail. The earlier corrected harness reported a roughly `5944 ms`
opening-to-presentation tail. Mixing opening with presented can therefore
manufacture a multi-second "effect" larger than many BootOptim candidates.

Routine exact-pack CI can continue using its historical `main_menu` endpoint
for software rejection. A physical product claim about a visually usable menu
should prefer `main_menu_presented` when that marker is available. The two
populations stay separate; do not retroactively subtract a typical presentation
tail from opening-only runs.

## Run identity / stale JVM

A run is **INVALID** before timing interpretation when any of these are true:

- the observed Java PID is not the launched PID;
- an earlier Java process survived into the run or process age is stale;
- the effective Prism JVM arguments were not verified after editing
  `instance.cfg`;
- the physical instance has other than exactly one BootOptim JAR;
- required endpoint/reload markers are missing or duplicated;
- selected resource-pack state is known wrong;
- monotonic/uptime ordering is impossible.

The stale-JVM case previously documented in #147 added an unobserved
`842.733 s` prefix. It is not an outlier to winsorize or retain in a range; it
belongs outside the sample population.

## Warm/cold/cache state

Use explicit labels instead of the overloaded word "cold":

- `fresh_vm`: a newly allocated hosted VM. This does **not** assert an empty OS
  page cache after fixture download/extraction/Gradle preparation.
- `cold_verified`: only when the relevant cache state was actually verified.
- `warm_verified`: explicitly warm state.
- `paired_shared`: same-VM paired diagnostic; kernel/filesystem/Gradle caches
  are intentionally shared while mutable reports/MCEF cache are reset.
- `session_uncontrolled`: normal physical same-session state with no reboot or
  cache purge.
- `unknown`: insufficient evidence; comparison is `INCONCLUSIVE`.

PR #154's alternating same-VM order is the right anti-confounding design for a
warm-second effect. It does not make the second process cold. NIST's paired
experiment guidance is directly applicable: pairing reduces extraneous
variation when the paired units are made as alike as possible, and order should
not be permanently assigned to one treatment.

## CPU / GC / heap / available-memory interpretation

The two comparable low-noise ModelManager samples show substantial phase
spread while several whole-process counters are much steadier:

| Metric | Sample A | Sample B | Range | Range / midpoint |
| --- | ---: | ---: | ---: | ---: |
| ModelManager final future | 150078.409 ms | 130955.212 ms | 19123.197 ms | 13.61% |
| process CPU by opening | ~1053438 ms | ~990031 ms | ~63407 ms | 6.21% |
| GC accumulated elapsed | ~15421 ms | ~15311 ms | ~110 ms | 0.72% |

The production/opening figures `350330` and `360117 ms` have a two-sample
midpoint `355223.5 ms` and range `9787 ms` (`2.76%`). Two values are not a
variance distribution, so this range is an operational noise observation, not
a confidence interval.

Important semantics:

- Java `getProcessCpuTime()` is CPU time used by the JVM process across its
  threads. `process_cpu_delta / wall_delta` is useful as average concurrent CPU
  equivalents, but `wall - processCPU` has no resource ownership meaning and
  must never be called disk, GPU, wait or descheduling time.
- `GarbageCollectorMXBean.getCollectionTime()` is approximate accumulated GC
  **elapsed** time. It is not GC CPU and is not a safe TTMM savings ceiling.
- heap used is JVM heap occupancy, not process working set.
- `OperatingSystemMXBean.getFreeMemorySize()` is a free-memory snapshot. The
  observed `~775 MiB` is a pressure warning, not a hard-fault/page-cache
  counter.
- the second diagnostic's process working set growing beyond `6 GiB` is an
  observer-effect warning. Its phase ordering can remain valid for attribution,
  but its absolute wall must not be compared directly with production as if the
  observer were free.

The near-constant `~15.3-15.4 s` GC elapsed time while ModelManager moves by
~19.1 s is evidence against "GC time alone caused the ModelManager spread".
It does not exclude allocation/memory-bandwidth/page-cache coupling.

## Futures and listeners

`resource_reload`, `ModelManager.reload`, block/model/atlas futures and listener
rows are inclusive dependency intervals. Many overlap by construction.
`loadModels` contains `bakeModels`; listener `global_wait` can be time waiting
for the global preparation barrier. Therefore:

- never sum listener/future durations as a savings estimate;
- never sum `loadModels + bakeModels`;
- never convert a task-sum into critical-path wall;
- prefer monotonic barrier/turn/completion timestamps and the latest gate on the
  dependency graph;
- subtract two timestamps only when they define a real serial interval on the
  same clock. Even then, name the interval; do not silently call it exclusive
  CPU.

The second sample has `allPreparations=123797.845 ms` and ModelManager final
future `130955.212 ms`. Their `7157.367 ms` difference is only a temporal gap
between those boundaries. It is not automatically "apply cost" or removable
ModelManager work.

## Hosted variance / pressure audit

PR #154's first three same-VM candidate-minus-control menu deltas were
`-1308`, `+4732`, `-2786 ms`: mixed signs, median `-1308 ms`, range `7518 ms`.
The next three were `+1750`, `-1666`, `+1191 ms`: mixed signs again, median
`+1191 ms`, range `3416 ms`. The median sign reversed between campaigns.

The second campaign simultaneously observed roughly `40-97%` I/O-wait,
blocked processes and one swap-out episode; stolen CPU was zero. This is a
measurement-contamination diagnosis, not proof that a particular candidate
caused storage behavior.

The offline checker uses deliberately conservative **screening**, not causal,
thresholds:

- any swap-out sample -> `INCONCLUSIVE` for causal A/B;
- hosted `vmstat` I/O-wait p95 >= `20%` -> `INCONCLUSIVE`;
- blocked process(es) plus I/O-wait p95 >= `10%` -> `INCONCLUSIVE`;
- stolen CPU max >= `5%` -> `INCONCLUSIVE`.

The `20%` I/O-wait threshold is not a universal Linux performance limit. It is
a laptop-spend guard chosen well below the project's observed 40-97% corrupted
campaign. Linux `vmstat` defines `b` as processes blocked waiting for I/O,
`si/so` as swap traffic, `wa` as CPU time waiting for I/O, and `st` as stolen
VM CPU time. Those counters identify pressure dimensions; they do not assign a
specific Java phase to disk without aligned evidence.

## Three-state validity contract

`VALID` means the completed result is sufficiently identified and internally
consistent for its declared purpose. It does **not** mean the optimization is
validated.

`INCONCLUSIVE` means the timings may be real but an unresolved confounder or
missing state prevents the requested inference. Examples: unknown cache state,
known observer perturbation for an absolute performance claim, or material host
pressure.

`INVALID` means the run belongs outside aggregation: stale/wrong PID, duplicate
mandatory markers, endpoint/origin mismatch, wrong pack/config/JVM identity, or
physical Prism/JAR contract failure.

### Minimal normalized sidecar

```json
{
  "schema_version": 1,
  "run_id": "physical-control-001",
  "purpose": "candidate_control",
  "variant": "control",
  "origin": "physical_laptop",
  "endpoint": "main_menu",
  "clock": {
    "origin": "process",
    "process_start_verified": true,
    "jvm_start_epoch_ms": 0,
    "first_probe_wall_epoch_ms": 0,
    "first_probe_uptime_ms": 1000
  },
  "identity": {
    "java_pid": 4242,
    "observed_java_pid": 4242,
    "stale_java_detected": false,
    "surviving_previous_java": false,
    "bootoptim_jar_count": 1,
    "effective_jvm_args_verified": true,
    "instance_cfg_verified": true
  },
  "state": {
    "pack_fingerprint": "...",
    "config_fingerprint": "...",
    "jvm_fingerprint": "...",
    "resource_selection_valid": true,
    "cache_state": "session_uncontrolled"
  },
  "markers": {
    "main_menu": 1,
    "main_menu_presented": 0,
    "initial_resource_reload": 1
  },
  "metrics": {
    "main_menu_ms": 350330,
    "main_menu_presented_ms": null,
    "process_cpu_ms": 1053438,
    "gc_time_ms": 15421,
    "heap_used_mib": 3700,
    "available_memory_mib": 900
  },
  "instrumentation": {
    "profile": "production",
    "observer_perturbation_known": false
  }
}
```

The schema is intentionally a sidecar first. Existing exact-pack and PR #148
outputs can be normalized offline without changing timed runtime code.

Tool:

```text
python tools/laptop-bench/check_measurement_result.py single run.json
python tools/laptop-bench/check_measurement_result.py compare control.json candidate.json
```

The compare command hard-rejects known origin/endpoint/clock/pack/config/JVM or
instrumentation mismatches. Same-VM comparisons additionally require a shared
pair ID, explicit order and `paired_shared` cache label.

## Decision formulas

For descriptive runs of one variant:

- median: `m = median(x_i)`;
- range: `R = max(x_i) - min(x_i)`;
- relative range: `R_rel = R / m`.

With only `n=2-3`, report all raw values beside `m` and `R`; do not present a
small-sample CI that the design cannot support.

For same-VM pairs define `d_i = candidate_i - control_i` (negative is faster):

- `d_med = median(d_i)`;
- `R_d = max(d_i) - min(d_i)`;
- sign consistency = `max(count(d_i < 0), count(d_i > 0)) / count(d_i != 0)`.

A hosted result is a **credible lead worth spending the laptop on** only when,
for at least three valid alternating pairs:

1. primary `d_i` is `3/3` the same sign;
2. the relevant critical-path phase moves the same direction in every pair;
3. `abs(d_med) > R_d`;
4. `abs(d_med) > max(1000 ms, 0.01 * median(control))`;
5. the mechanism marker is present in every candidate and semantic/resource
   gates match; and
6. no pair is pressure-screened `INCONCLUSIVE`.

These are **operational laptop-spend thresholds**, not a statistical
significance test. They intentionally demand more than "candidate median is
lower" because three hosted pairs are too small for a precise distribution and
recent runner noise is multi-second. A clearly larger mechanism can pass; a
micro-effect stays hosted/inconclusive.

PR #154 fails immediately on sign consistency and host pressure, which is the
correct decision despite favorable medians in individual campaigns.

## Minimum physical-run policy

### Variance attribution already underway

There are two comparable low-noise ModelManager attribution samples. Collect at
most **one more** valid sample to reach `n=3`, using the same diagnostic and
settings. That closes the planned phase-attribution set. Do not reboot, purge
caches, add a profiler, or compare its absolute wall to production.

### Candidate promotion

Do not spend physical launches until hosted semantic gates pass and, for a
small/medium effect, the three-pair lead rule above passes.

Then use two same-session physical pairs with alternating order:

```text
pair 1: control -> candidate
pair 2: candidate -> control
```

That is **four launches maximum for the first physical decision**, with an
early stop after pair 1 if the run is invalid, the mechanism did not execute,
semantics changed, or the relevant phase moves against the hypothesis. No
reboot/cache purge is required; preserve natural session state and record it as
such.

Two physical pairs are enough to confirm a large coherent direction, not to
claim a high-precision effect size. If the two deltas disagree in sign or the
candidate magnitude is close to the paired spread/noise floor, classify
`INCONCLUSIVE`; do not automatically buy more laptop runs. A third pair is
justified only for a materially important candidate that is near the decision
boundary.

## When hosted cannot close a front

Hosted exact-pack is excellent for semantic rejection and software-pack
mechanism direction. It cannot by itself close a target-laptop claim when the
mechanism materially depends on:

- physical storage, ZIP/page-cache or filesystem behavior;
- Windows scheduling, Defender/antivirus, power/thermal state or Task/Prism
  launch semantics;
- native CEF/OpenAL or other Windows-native libraries;
- GPU/driver/software-render/upload/present timing;
- memory pressure whose performance effect is mediated through the target OS
  page cache; or
- a small Java/model effect whose physical/hosted phase scaling differs
  materially.

A negative hosted result can still reject a portable Java mechanism when its
own direct marker/phase fails coherently. But a tiny hosted delta cannot prove
that a laptop-only storage mechanism is absent, and a large hosted win cannot
prove Windows-native equivalence.

## Tooling changes proposed

This branch adds only:

- `tools/laptop-bench/check_measurement_result.py` — offline three-state run
  and comparison gate;
- `tools/laptop-bench/test_measurement_result.py` — unit tests for stale PID,
  duplicate endpoints, observer effect, host pressure, endpoint/pack mismatch
  and same-VM pair identity; and
- this methodology note.

No runtime optimization, profiler or benchmark polling is added.

## Public interpretation references

- Oracle Java SE 25 `RuntimeMXBean`: JVM uptime and approximate start time.
- Oracle Java SE 25 `OperatingSystemMXBean`: process CPU time and free-memory
  snapshot semantics.
- Oracle Java SE 25 `GarbageCollectorMXBean`: approximate accumulated GC
  elapsed time.
- Oracle Java SE 25 `ThreadMXBean`: thread CPU time precision/accuracy and
  enablement caveats.
- Linux `vmstat(8)`: meanings of runnable/blocked, swap, I/O-wait and stolen
  CPU fields.
- NIST Experimental Statistics, paired observations: pairing reduces
  extraneous variation and improves sensitivity when pair members are made as
  alike as possible.
