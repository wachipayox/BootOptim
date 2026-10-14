# Multipart dependency union with full current-sequence validation

Status: default-off experiment; local build, semantic tests and hosted exact-pack equivalence pass. First hosted A/B is inconclusive and does not validate promotion or a laptop run. Base: integration `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.

## Evidence and distinct premise

[Diagnostic #303](https://github.com/wachipayox/BootOptim/pull/303) measured dependency enumeration inside `loadAllBlockStates`: 1,132.376 ms inclusive child wall, of which MultiPart owns 1,066.965 ms across 147,906 calls and 961 object identities. These are attribution timings, not a savings prediction or a sum to add to parallel reload listeners. The earlier whole discovery measurement was 1,969.485 ms in a 4,881.438 ms loadAll invocation. Different diagnostic runs must not be subtracted as an A/B.

[Closed #295](https://github.com/wachipayox/BootOptim/pull/295) replaced the multipart method with direct loops and bypassed inner `MultiVariant.getDependencies` callbacks. Its process-order-confounded measurements did not establish a CPU or critical-path win. This experiment retains the original stream, selectors, inner dependency queries, model-location calls and delivered sequence. It changes only the union collector.

On an exact MultiPart object, a first sequential query builds a fresh stock-style HashSet and records delivered references plus first unique insertion order. Each later query still consumes every current element and checks its reference, order and total length. Only a fully matching sequence permits rebuilding a fresh set from the unique insertion order, avoiding repeated duplicate hash insertions. A changed, shortened, extended or reordered sequence reconstructs the already validated prefix and continues collecting current values. No returned set, model result or callback result is shared across calls.

## Invariants and limits

- Scope is one original `BlockStateModelLoader.loadAllBlockStates` call, released in finally, including exceptions; nested scopes restore the parent.
- Plans are bounded to 4,096 owners and 4,096 flattened elements per owner. This is a bounded diagnostic experiment, not an unbounded generation cache.
- Null and ResourceLocation subclasses remove the plan and use ordinary current HashSet insertion. Top-level subclasses use the original collector. Selector/MultiVariant callbacks are always executed, including custom subclasses.
- Parallel streams receive the exact original collector and combiner before execution. Nothing is moved to another thread or touches GL.
- Keys must retain vanilla immutable ResourceLocation hash/equals behavior. Exact runtime class excludes custom subclasses but cannot establish purity of arbitrary third-party transformations of hashCode; this is a residual compatibility assumption requiring pack validation.
- Every caller receives an independent HashSet with the original first representative of each equality class. First unique insertion order is retained so stock HashSet iteration, including collision/tree bins, is preserved.

Flags: `boot_optim.multipartValidatedUnion` enables the experiment; `boot_optim.verifyMultipartUnion` also enables it and checks every result against a reference collector fed the same delivered sequence, without replaying callbacks; `boot_optim.profileMultipartUnion` adds two current-thread CPU/wall reads around the complete loadAll call. All default false. Verification deliberately allocates a reference sequence/set and MUST be false in timed comparisons.

Fixed `BOOTOPTIM_MULTIPART_UNION` summary records successes, hits, fallbacks, retained plans, mismatches and optional whole-load CPU/wall. The hosted verification gate requires exactly one successful active summary, nonzero attempts/hits and zero mismatches. A missing injection must not silently count as successful validation.

## Validation and decision gate

`scripts/test_validated_dependency_union.py` compiles the real helper and tests verification on and off: callbacks, mutable/reordered/shortened/extended sequences, equal keys with different identities, random permutations, collision tree bins, empty sequences, independent result ownership, unsafe keys, large inputs, parallel fallback, original exceptions and nested/failed scope cleanup. The build workflow runs it. Local packaged Gradle build passed.

Next: exact-pack verification smoke, then verification-off same-branch A/B with whole-load CPU markers in both modes, matching startup origin/endpoints, pack-selection contract and reload critical-path wall. High hit counts alone are insufficient. Do not claim this removes the measured 1.067 seconds: inner dependency-set construction and stream traversal remain. No laptop mutation or new manual run is warranted before hosted evidence.

## Hosted semantic result reviewed 2026-10-01

Source `d98c1f30989024666097f401a31794368d68632d`, [exact-pack run 36779506692](https://github.com/wachipayox/BootOptim/actions/runs/36779506692): PASS, matching resource selection, one reload, zero BootOptim Mixin errors. Active verification reports 147,906 attempts, 146,945 hits, 961 misses/plans, zero unsafe/oversized/parallel fallbacks and zero identity/order mismatches. Build and minimal startup also passed this source.

Whole loadAll wall 4,695.858 ms / current-thread CPU 4,167.002 ms and menu marker 88,753 ms are verification-run observations only: reference collection adds work and there is no matching control. These do not establish savings. A three-repetition fresh-VM A/B was dispatched on the same source with verification false and whole-load profiling true in both modes. Physical campaign remains untouched. Downloaded semantic artifacts: `C:/BootOptimBench/analysis-reload-20261001/multipart-semantic-36779506692`.

## First timed A/B reviewed 2026-10-01: inconclusive / no promotion

[Run 36784238631](https://github.com/wachipayox/BootOptim/actions/runs/36784238631), same source, six fresh hosted VMs: PASS, verify=false/profile=true both modes, one reload each, enabled pack order retained, zero BootOptim Mixin failures. No hardware mutation occurred.

| Variant/run | Menu ms | Reload to FancyMenu ms | Whole loadAll wall ms | Whole loadAll current-thread CPU ms |
| --- | ---: | ---: | ---: | ---: |
| control 1 | 90120 | 40562 | 4430.520 | 3988.002 |
| candidate 1 | 85727 | 39060 | 4501.405 | 3952.480 |
| control 2 | 86636 | 40636 | 4357.508 | 3810.126 |
| candidate 2 | 91138 | 42964 | 4918.841 | 4286.803 |
| control 3 | 65478 | 30511 | 3208.392 | 2854.346 |
| candidate 3 | 59992 | 26562 | 2580.715 | 2303.651 |

Candidate-minus-control medians: menu **-909 ms / -1.05%**, reload to FancyMenu **-1502 ms / -3.70%**, whole loadAll wall **+143.897 ms / +3.30%**, CPU **+142.354 ms / +3.74%**. Each candidate reports 146945 hits/147906 attempts, 961 plans, no unsafe/oversized/parallel fallbacks. Verification=false mismatches=0 is only a fixed field, not a second semantic check.

These rows are independent VMs, not within-JVM paired causal estimates. Both third runs show large broad speed changes (including panorama); the positive aggregate menu median cannot establish that the collector saves CPU. We must not claim a validated 0.9/1.5-second win. No automatic laptop gate or promotion. Candidate stays isolated/default-off. Reopening requires a matched in-process or otherwise controlled CPU comparison proving real work reduction, or a revised mechanism reducing the inner dependency-set/stream construction left intact here; do not repeat identical fresh-VM runs merely until a favorable median appears.

Artifacts: `C:/BootOptimBench/analysis-reload-20261001/multipart-ab-36784238631`. FerriteCore's separately positive conditioned CPU evidence remains retained; this inconclusive result does not supersede its pending physical/final gate.
