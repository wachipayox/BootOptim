# `minecraft:block` RegisterEvent CPU attribution — 2026-09-05

Status: **PROFILED** (diagnostic only; no production optimization implemented)

PR: [#105](https://github.com/wachipayox/BootOptim/pull/105)

## Question

PR #99 established that ordered registry dispatch is dominated by `minecraft:block` in the hosted exact pack, but its maintainable `ModContainer.acceptEvent` hook did not execute. That closed exact **wall ownership by ModContainer** for that instrumentation path; it did not answer which algorithms consume CPU while the already-validated Block registry event is running.

This entry answers only that CPU/localization question. It does not infer exact per-mod wall, recoverable TTMM, or ownership from stack samples.

## Prior evidence checked before adding a profiler

- PR #68's laptop JFR already showed voxel-shape work globally (`BitSetDiscreteVoxelShape.lambda$join$0`, `LithiumDoublePairList.forMergedIndexes`, etc.), but the retained evidence spans startup rather than the `minecraft:block` event window. It was therefore suggestive, not sufficient attribution.
- PR #73's voxel-shape profiler was source-specific, but its first exact-pack output was lost to the historical stdout-transport failure. Later ordinary CI proved the diagnostic transport, not Block-window ownership in the exact pack.
- PR #18/#19's `Blocks.<clinit>` work is an earlier pre-entrypoint bootstrap phase. Its ~2.9 s hotspot and rejected broad class prewarm are not evidence for this later registry window.
- PR #100's late FML mixins/SERVICE transformer did not execute at the required lifecycle point. This experiment does not retry that route and does not infer work from logging silence.

## Diagnostic design

PR #105 reuses only the priority-1100 BEFORE/AFTER observation boundary at the exact `GameData.postRegisterEvents -> ModLoader.postEventWrapContainerInModOrder(RegisterEvent)` callsite that executed in #99. It does **not** redirect, wrap, invoke, replace, reorder, or parallelize event dispatch; ModernFix's sequential dispatch remains authoritative.

With `-Dboot_optim.profileRegistryBlockCpu=true`, and only for `Registries.BLOCK`:

- capture the actual dispatching thread at the real BEFORE callback;
- sample that thread every 15 ms from a daemon observer;
- delay the first observation by one interval so the BEFORE callback can return;
- discard a capture if it crosses the real AFTER timestamp;
- cap storage at 768 samples, 384 unique leaf methods, 256 unique stacks, and 24 frames per stack;
- never log per sample;
- measure exact target-thread CPU delta via `ThreadMXBean` only if thread CPU time is already available;
- log sample counts separately from the interval CPU-progress weight.

`cpu_progress_weight_ms` is intentionally weak: it is the target-thread CPU delta since the previous sample assigned to the current sample endpoint. It is useful for distinguishing a frequently sampled wait from active CPU, but it is **not exact self CPU**, wall time, or a savings estimate.

## Hosted exact-pack smoke

Fixture: `exact-pack-2026-09-02-v1` (`bootoptim-exact-pack.zip`, 160 mod jars).

Measured diagnostic code head: `13442e4b19ecc8878657b3d746a8fedfd00449c0`.

| Gate | Result |
|---|---|
| Build #1392, run `33959792366` | PASS |
| Startup Benchmark #395, run `33959792345` | PASS, diagnostic property off |
| Exact Pack Startup Benchmark #161, run `33959792332` | PASS |
| Exact job | `101289628587`, reached title |
| BootOptim mixin errors | `0` |
| Exact artifact | `exact-pack-result-smoke-1`, ID `9967603803` |
| Artifact digest | `sha256:91ae2e047bb07083b9d9c9ea337a136e295affe29ba41d08f13b64c70d7a68a7` |

The exact artifact is CI evidence, not a physical-laptop run. No external/local ZIP logs are treated as exact-pack evidence here.

### Window summary

Exact emitted marker:

```text
BOOTOPTIM_REGISTRY_BLOCK_CPU status=complete registry=minecraft:block window_wall_ms=9149.204 target_cpu_ms=7167.886 samples=565 expected_samples=609 coverage_pct=92.78 runnable_samples=472 cpu_progress_samples=485 sampled_cpu_progress_ms=7160.205 interval_ms=15 max_samples=768 sample_cap_hit=false unique_methods=73 unique_stacks=206 dropped_unique_keys=0 thread=modloading-sync-worker sampler_error=none
```

So this smoke observed:

- `9149.204 ms` inclusive Block-event wall;
- `7167.886 ms` exact CPU delta on the executing registry thread;
- 565 samples over 609 nominal 15 ms slots (`92.78%` coverage);
- 472 samples where the target thread was RUNNABLE;
- 485 sample intervals with positive CPU progress;
- no sample cap, no unique-key drops, no sampler error.

Do **not** compare the 9.149 s wall directly against #99's 6.500 s Block event as if this were an A/B. They are different diagnostic runs; hosted noise and observer perturbation are both possible. This experiment is localization only.

## Method ranking

The highest-ranked leaf methods were:

| rank | leaf | samples | sample share | interval CPU-progress weight |
|---:|---|---:|---:|---:|
| 1 | `LithiumDoublePairList.forMergedIndexes:91` | 256 | 45.31% | 3910.787 ms |
| 2 | `Unsafe.park` | 93 | 16.46% | **10.203 ms** |
| 3 | `IdenticalMerger.forMergedIndexes:16` | 37 | 6.55% | 567.019 ms |
| 4 | `LongOpenHashSet.contains:481` | 23 | 4.07% | 347.379 ms |
| 5 | `BitSetDiscreteVoxelShape.join:115` | 17 | 3.01% | 261.258 ms |
| 6 | `BitSetDiscreteVoxelShape.forAllBoxes:169` | 10 | 1.77% | 151.100 ms |
| 7 | `DiscreteCubeMerger.forMergedIndexes:23` | 9 | 1.59% | 136.817 ms |
| 8 | `BitSet.expandTo:357` | 7 | 1.24% | 108.828 ms |
| 9 | `LithiumDoublePairList.merge:77` | 6 | 1.06% | 91.899 ms |
| 10 | `Shapes.joinUnoptimized:149` | 5 | 0.88% | 77.712 ms |

Raw sample share and interval weight are not interchangeable. In particular, `Unsafe.park` is the second most frequent leaf because the registry thread spends observable wall waiting, yet it accumulated only ~10 ms of target-thread CPU progress across those intervals.

## Principal mechanisms

### 1. Repeated voxel-shape coordinate merging is the dominant sampled CPU mechanism

The dominant full stacks repeatedly contain:

```text
LithiumDoublePairList.forMergedIndexes
 <- BitSetDiscreteVoxelShape.lambda$join$1
 <- ... merger callbacks ...
 <- BitSetDiscreteVoxelShape.join
 <- Shapes.joinUnoptimized
 <- VoxelShape.optimize
 <- Shapes.join
 <- Shapes.or
 <- VoxelShaper.lambda$rotatedCopy$0
 <- ... VoxelShape.forAllBoxes ...
 <- VoxelShaper.rotatedCopy
 <- VoxelShaper.rotate
 <- VoxelShaper.forDirectionsWithRotation
 <- VoxelShaper.forDirectional / forHorizontal
```

Exact Lithium runtime version in the pack is `0.15.3+mc1.21.1`. Upstream tag [`mc1.21.1-0.15.3`](https://github.com/CaffeineMC/lithium/tree/mc1.21.1-0.15.3), commit `09d115dc18acc978b281107e9d02e5d043a0c20f`, shows `LithiumDoublePairList.forMergedIndexes` as a serial loop over `pairs.size() - 1`, invoking the merge predicate for each interval:

[`common/src/main/java/net/caffeinemc/mods/lithium/common/shapes/pairs/LithiumDoublePairList.java`](https://github.com/CaffeineMC/lithium/blob/mc1.21.1-0.15.3/common/src/main/java/net/caffeinemc/mods/lithium/common/shapes/pairs/LithiumDoublePairList.java)

Lithium is already replacing vanilla's more expensive indirect merger; this evidence is **not** a reason to implement another generic merger. The question is why the registration path asks the merger to rebuild/optimize so many shapes.

### 2. Ponder/Catnip `VoxelShaper.rotatedCopy` creates those joins one box at a time

Create 6.0.10 is tag [`mc1.21.1-6.0.10`](https://github.com/Creators-of-Create/Create/tree/mc1.21.1-6.0.10), commit `ac0c444d9828da3453ae8cc65338e8de063286fb`, and its exact `gradle.properties` pins Ponder `1.0.82`.

The public Ponder `mc1.21.1` `VoxelShaper.java` has not changed since commit `c3e5a41380203e1dd1e2431c494ec491a51965a5` on 2025-11-08, before the exact runtime version. That source matches the runtime stack line `VoxelShaper.lambda$rotatedCopy$0:111`:

[`common/src/main/java/net/createmod/catnip/math/VoxelShaper.java`](https://github.com/Creators-of-Create/Ponder/blob/c3e5a41380203e1dd1e2431c494ec491a51965a5/common/src/main/java/net/createmod/catnip/math/VoxelShaper.java)

Mechanism:

1. `forDirectionsWithRotation` creates each requested orientation.
2. `rotate` calls `rotatedCopy` for non-identity orientations.
3. `rotatedCopy` enumerates every source box with `shape.forAllBoxes`.
4. Each transformed box is immediately folded into the current result with `Shapes.or(result, rotatedBox)`.
5. That union triggers `Shapes.join` / optimization machinery repeatedly, reaching the Lithium pair merger and voxel-grid joins sampled above.

This is a concrete source-level generator of the dominant inner-loop work. The current 24-frame stack cap intentionally prevents pretending to know the exact downstream block/mod owner for every sample; ownership is not needed for the CPU question.

### 3. Voxel occupancy/hash-set work is a secondary sampled cost inside the same joins

A smaller but non-trivial branch reaches:

```text
LongOpenHashSet.contains
 <- com.mr_toad.palladium.common.util.FastBitSet.get
 <- BitSetDiscreteVoxelShape.isFull
 <- ... BitSetDiscreteVoxelShape.join ...
 <- Shapes.or
 <- VoxelShaper.rotatedCopy
```

and another reaches `BitSet.expandTo -> BitSet.set -> Palladium FastBitSet.set -> BitSetDiscreteVoxelShape.lambda$join$0`.

The exact pack reports Palladium `fastBitSets=true`. This shows that some of the same dense voxel-join work passes through Palladium's bitset replacement and fastutil hash-set operations. It does **not** yet establish that Palladium is slower than stock, nor that toggling it would improve TTMM. That requires a separate controlled mechanism test; no config change is made in #105.

## Wait mechanism: Decocraft is visible, but it is not the dominant CPU result

All 93 `Unsafe.park` leaf samples in the top wait stack run through:

```text
Unsafe.park
 <- LockSupport.park
 <- CompletableFuture$Signaller.block
 <- ForkJoinPool.managedBlock
 <- CompletableFuture.waitingGet
 <- CompletableFuture.join
 <- com.razz.decocraft.common.ModuleBlocks.loadModelsInBatches:200
 <- ModuleBlocks.loadBlocksFromResources:108
 <- ModuleBlocks.registerDynamicBlocks:96
 <- ModuleBlocks.initialize:51
 <- Decocraft.lambda$new$0:67
 <- ... EventBus.post / ModContainer.acceptEvent ...
```

Because those 93 samples carry only `10.203 ms` of target-thread CPU progress, they are classified as **WAIT**, not CPU. They do not make Decocraft the owner of the ~7.17 s registry-thread CPU and do not recover the missing per-ModContainer wall attribution from #99.

## Secondary corroboration: `WallBlock.makeShapes`

One shorter sampled stack reaches:

```text
... Shapes.or
 <- WallBlock.makeShapes:110
 <- WallBlock.<init>:65
 <- com.tterrag.registrate.builders.BlockBuilder.createEntry:426
 <- ... AbstractRegistrate.onRegister ...
 <- EventBus.post
```

That confirms wall-shape construction as one concrete registration-time instance of the same join mechanism. It is not evidence that wall blocks account for the whole Block window.

## Perturbation and interpretation limits

- Sampling occurs from another daemon thread at 15 ms intervals; `Thread.getStackTrace()` can perturb the target. No performance delta is inferred from this run.
- Stack samples are statistical observations, not a profiler's exact self-CPU accounting.
- The exact target-thread CPU delta (`7167.886 ms`) is an aggregate for the whole Block window, not a sum assignable exactly to ranked methods.
- `cpu_progress_weight_ms` is only an interval association and can be shifted to the endpoint method.
- The top stacks are capped at 24 frames and intentionally do not claim exact mod ownership beyond visible frames.
- Sample percentages are **not** converted into TTMM savings or critical-path savings.

## Falsifiable next experiment

Do **not** cache, defer, parallelize, or modify the event bus yet.

The strongest source-level hypothesis is algorithmic:

> For Ponder/Catnip directional shape construction during Block registration, transform the complete set of source boxes for one orientation first, then construct an equivalent union with fewer intermediate `Shapes.or -> join -> optimize` passes than the current one-box-at-a-time fold.

A candidate is worth implementing only if it can preserve stock semantics without global/shared caches or changed registration timing.

Required correctness gate:

1. For every affected source shape and generated orientation in the exact pack, candidate and stock results must be voxel-shape equivalent (not merely visually similar).
2. Registry/event order and callbacks remain untouched.
3. No persistent/shared mutable shape state leaks across blocks, reloads, worlds, or gameplay.
4. No OpenGL/render work is moved or changed.

Required mechanism gate before TTMM A/B:

1. The same bounded Block-window sampler still has non-zero coverage and no cap/error.
2. `LithiumDoublePairList.forMergedIndexes` / `BitSetDiscreteVoxelShape.join` sample incidence and aggregate target-thread CPU decline materially in the same window.
3. Wait behavior is reported separately rather than counted as CPU improvement.

Only after those gates would a normal exact-pack performance A/B be justified. A failure to reduce these measured shape-join signatures would falsify this mechanism even if a local microbenchmark looked faster.

## Reopening / closure criteria

This CPU question is **not closed** by #99's failed per-ModContainer wall hook. #105 provides positive Block-window CPU attribution.

Do not reopen generic voxel-merger replacement, broad class prewarming, FML private-loader transforms, registry parallelism, or event-bus reordering without a materially new premise.

The next investigation should remain on source-local shape-construction work unless new exact-pack evidence shows a different CPU mechanism dominates the Block window.