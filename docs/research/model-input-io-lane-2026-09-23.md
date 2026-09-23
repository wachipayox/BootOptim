# ModelManager input-open I/O lane — 2026-09-23

Status: **OPT-IN EXPERIMENT; NOT PRODUCTION**. Base is
`agent/integration-current` at `b3f0c5f`. The premise comes from the valid
[physical three-generation diagnostic](https://github.com/wachipayox/BootOptim/blob/codex/resource-loading-audit-20260922/docs/research/resource-deep-physical-result-2026-09-23.md):
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

Gates: build and startup CI, hosted exact-pack smoke with the property on,
then hosted 3×3 same-branch A/B with matching JVM origin and endpoint. A
hosted null/small/regression result cannot settle the HDD premise by itself.
Any physical comparison requires a separate user signal, identical pack and
JVM conditions, effective-property verification, and the restored-laptop
transaction. A real retained optimization would need coherent physical
critical-path movement and semantic/visual checks; it would also need to
address the separate late-bake GC amplification instead of claiming to solve
it through I/O scheduling.
