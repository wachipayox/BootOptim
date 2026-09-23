# ModelManager input-open I/O lane — 2026-09-23

Status: **REJECTED BEFORE A/B; DO NOT PROMOTE**. Base is
`agent/integration-current` at `b3f0c5f`. The premise comes from the valid
[physical three-generation diagnostic](resource-deep-physical-result-2026-09-23.md):
44,708 models and 11,484 blockstates are reopened in every generation; model
`Resource.openAsReader` task-sum grows 11.770 → 77.899 → 168.506 s while
model parse task-sum remains 8.739 → 5.839 → 14.293 s. JFR independently
finds 6.3 → 68.1 → 164.6 s of model-input `FileRead` durations in the
corresponding `block_models` windows. These overlapping sums demonstrate an
I/O-pressure mechanism, not a potential critical-path savings amount.

The earlier global resource-reload executor cap ([PR #151](https://github.com/wachipayox/BootOptim/pull/151))
regressed the clean laptop reload by about 9.6 s. This experiment changes a
material premise: it leaves the reload executor, task creation, all other
listeners, parsing, futures, barrier and ordered apply alone. It bounds only
the exact `Resource.openAsReader` calls inside Minecraft 1.21.1
`ModelManager`'s block-model and blockstate input lambdas to two concurrent
opens, allowing their parse work and texture/CIT/other listeners to run
normally. The original operation is invoked exactly once; no resource
content, reader, cache, task order, error handling or GL ownership is
changed. This may reduce random-read contention on the old HDD, but can
regress a fast/warm system or starve custom pack suppliers, so it is disabled
unless `-Dboot_optim.experimentModelInputIoLane=true` is set.

The fair semaphore is reload-scoped. A same-thread nested open bypasses the
permit to avoid self-deadlock. A wait exceeding 15 seconds or interruption
trips that reload to the original unbounded open behavior; interruption status
is restored. One summary after the ModelManager future reports attempted,
bounded and fallback opens, plus inclusive permit-wait and open task-sums.
This is diagnostic evidence that the mechanism actually engaged, not a
time-to-menu estimate. Unknown/custom supplier callbacks still need runtime
validation. The target is a narrow bytecode callsite; if upstream lambda
shape changes, the guarded experiment must be disabled rather than guessed.

Local build and standard startup CI passed. Hosted exact-pack
[smoke #35876917490](https://github.com/wachipayox/BootOptim/actions/runs/35876917490)
reached the menu with zero BootOptim/Mixin errors and exact pack/atlas
behavior. Its runtime marker showed `attempted=56311 bounded=56311
fallback=0 wait_ms_sum=21`: the implementation covered every expected model
and blockstate resource open, but all 56,311 opens together waited only 21 ms
for a permit. The smoke's single menu time is not performance evidence.

Reviewing the already-collected physical task-sum against each future's wall
revealed the flaw in the premise. Model-task sum / `block_models` wall is
21.252/26.427 = 0.804, 84.079/84.138 = 0.999, and 183.319/191.037 =
0.960 across the three generations. Blockstate-task sum / `block_states`
wall is 0.524, 0.899 and 0.939. These are **average active tasks over the
phase**; they do not prove a strict peak of one, but they show a two-permit
cap cannot materially constrain the sustained measured model-input path.
The hosted 21 ms wait confirms negligible contention in the surrogate.

The hosted 3×3 A/B was requested but cancelled before completion once this
arithmetic exposed the weak transfer function. There is no completed A/B or
physical candidate measurement and no speedup claim. The mechanism remains
default-off on this branch only. A later design must reduce/reorganize actual
resource opens or alter the underlying pack I/O path with preserved semantics;
repeating an executor/permit cap would require new evidence of sustained
concurrent-open contention. The separate late-bake GC amplification is also
outside this candidate.
