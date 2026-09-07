# Exact-pack A/B artifact collision — 2026-09-07

## Finding

The exact-pack workflow generated one artifact name per variant:
`exact-pack-result-candidate` and `exact-pack-result-control`. With three A/B
repetitions, artifact v4 accepted the uploads but the aggregate download
merged same-named artifacts into one directory. The summary then reported
`Runs: 1` and computed its median from only the surviving `result.json`, even
though all six benchmark jobs completed successfully.

This was observed in run `34158346876` (PR #171). Directly downloading the six
artifact IDs recovered all repetitions; the published summary did not. The
workflow result was therefore not a reliable aggregate until the artifact
names were made unique.

## Fix

Artifact names now include the matrix repetition (`candidate-1`, `candidate-2`,
`candidate-3`, and equivalent control/paired names). The existing aggregate
script already recursively reads every `result.json`, so no change to its
median logic is needed once the files remain distinct.

This is harness correctness only; it does not alter Minecraft runtime or
performance behavior. Any A/B whose summary predates this fix must be
reconstructed from individual artifact IDs or rerun after the fix.

