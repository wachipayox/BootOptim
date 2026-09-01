# Voxel-shape startup source attribution

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE**

Branch: `agent/profile-voxel-shape-sources`

Base: current `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`.

## Question

The slow-laptop JFR campaign makes voxel-shape work large enough to investigate, but not large enough to optimize blindly.

Visible Java execution samples included approximately:

- `BitSetDiscreteVoxelShape.lambda$join$0`: `4.88%`
- Lithium `LithiumDoublePairList.forMergedIndexes`: `2.55%`
- `BitSetDiscreteVoxelShape.lambda$join$1`: `1.81%`
- `BitSetDiscreteVoxelShape.<init>`: `0.96%`

Those visible rows total about `10.20%` of sampled Java execution. **That is not 10.20% recoverable startup wall time.** The same campaign also recorded a very expensive C2 compilation of `WallBlock.makeShapes(...)` (~`7.51 s` compiler-thread time), which is aggregate compiler work rather than a directly subtractable wall-time interval.

The purpose of this PR is therefore attribution and ceiling-setting only:

1. Which block classes / namespaces generate joins and bitset shapes during startup?
2. Which call sites dominate?
3. Which immutable shape-table builders repeat the same exact parameter tuple?
4. Are result containers or shape identities reused?
5. In which startup phase does the work happen?
6. How do exact task-sum, sampled CPU and phase-wall ceilings differ?

Only after exact-pack data answers those questions should a lazy/static/canonical/precomputed source optimization be designed.

## Historical constraints

The pack already contains Lithium and ToadLib.

Lithium's shape-merging mixin replaces vanilla `IndirectMerger` creation in `Shapes.createIndexMerger(...)` with `LithiumDoublePairList`. The laptop JFR confirms `LithiumDoublePairList.forMergedIndexes(...)` is actually executing.

Exact-pack logs have also reported ToadLib `fastBitSets=true`. The exact implementation of that option for the installed build has not yet been established from source in this branch, so this PR makes no compatibility claim about what ToadLib transforms internally.

Consequences:

- **Do not implement another generic merger.**
- **Do not implement another generic bitset representation.**
- Do not infer a production optimization from a hot JFR row alone.
- Any future cache must prove immutable/context-independent semantics and lifetime/invalidation.

No prior dedicated BootOptim voxel-shape profiler PR was found when reviewing the current open/closed history; this lane originates from the laptop evidence documented in PR #68 and is kept separate from the resource-pipeline profiler in PR #69.

## Instrumentation

The profiler is enabled by:

```text
-Dboot_optim.profileVoxelShapes=true
```

The CI benchmark also enables it automatically because that launch already sets `boot_optim.benchmark.exitOnTitle=true`. Production behavior on this diagnostic branch still has only a cheap `enabled` branch at mixin entry when the property is absent.

### Generic join path

`Shapes.joinUnoptimized(...)` is observed without replacing the returned shape.

Per call, while enabled:

- exact invocation count;
- exact elapsed invocation task-sum via `System.nanoTime()`;
- exact maximum observed invocation duration.

Stack/caller attribution is **sampled**, default first call per thread plus 1-in-128 thereafter. Only sampled calls perform:

- `StackWalker`;
- caller class / method / line capture;
- BooleanOp implementation class and input shape classes;
- current-thread CPU-time sampling when the JVM exposes it;
- result identity comparison against the two input objects.

There is no per-call log line.

### BitSetDiscreteVoxelShape construction

Both constructors are counted exactly. Caller + dimensions are sampled, default first call per thread plus 1-in-256 thereafter.

This observes creation pressure without attempting to replace ToadLib or Minecraft storage.

### Source-specific immutable builders

Three comparatively rare builders receive exact source scopes:

- `WallBlock.makeShapes(...)`
- `CrossCollisionBlock.makeShapes(...)` — the shared fence / iron-bars path
- `Block.getShapeForEachState(...)`

For each exact source key the profiler aggregates:

- startup phase;
- block runtime class;
- exact float-bit parameter tuple (or state-count tuple);
- exact call count;
- task-sum and maximum scope duration;
- distinct owner identities, bounded to 128 identities per key;
- result-container identity reuse, bounded to 128 identities per key;
- number of returned shape slots;
- number of distinct shape **identities** within each result;
- duplicate slots by identity;
- registered block ids / namespaces resolved only when the main menu has already been marked.

Source-key aggregation is bounded to 512 keys. If a cap is hit the output says so instead of silently pretending coverage is complete.

A structural geometry hash is deliberately omitted. Producing a stable value-equivalence hash would require walking coordinates/AABBs or otherwise materializing shape geometry, which is exactly the kind of observer effect this first profiler should avoid. `System.identityHashCode` is not used as a uniqueness proof; exact source identity tracking uses object identity through bounded weak references.

## Startup phases

The report separates:

- `pre_mod_entrypoint`
- `mod_loading`
- `model_reload`
- `post_model_reload`

`ModelManager.reload(...)` is marked at method entry; the existing returned `CompletableFuture` is observed with `whenComplete` without replacing the future or changing reload ordering.

The main-menu marker remains the time-to-menu boundary. Formatting, registry lookup and final profiler output happen **after** `BOOTOPTIM_STARTUP phase=main_menu`, so the aggregation dump is not counted in BootOptim's measured startup uptime.

## Metric semantics

The profiler intentionally reports several different quantities instead of collapsing them into one fake “saved time” number.

### Exact join task-sum

`join_task_sum_ms` is the sum of elapsed `joinUnoptimized(...)` invocation scopes. Calls on different threads can overlap. It is therefore **not wall time** and must not be subtracted from time-to-menu.

### Sampled CPU

`join_sample_cpu_ms` is the CPU time of the same sampled calls represented by `join_sample_task_sum_ms`, when thread CPU time is enabled. `join_sample_cpu_to_task_ratio` compares those two sampled quantities only.

There is deliberately **no total CPU extrapolation**. The sampler includes the first call on each thread plus periodic 1-in-N calls, so multiplying sampled CPU or sampled site counts by N is not an unbiased estimator and can amplify a rare long call. Startup CI exposed exactly that failure mode in the first implementation: a naive extrapolation produced ~`4.99 s` of estimated CPU from samples while exact join task-sum was only ~`270.9 ms`. That field was removed rather than relabeled.

Similarly, call-site rows report sampled counts only; they do not multiply sample counts into an estimated total. Exact repetition claims come from phase totals and source-specific tuple counters.

### Phase wall

`wall_ms` is the measured startup phase duration. `critical_wall_ceiling_ms` is only:

```text
min(join task-sum, phase wall)
```

It is a deliberately loose ceiling. It does not know whether every join was on the critical dependency chain. The output labels this `critical_wall=ceiling_only`.

A future optimization is interesting only if exact source attribution shows concentrated repeated immutable work **and** a stripped A/B candidate improves actual time-to-main-menu. The JFR's ~10.2% sampled share is not used as a wall-time promise.

## Low-overhead safeguards

- no per-call logging;
- no stack walk on every join or constructor;
- no generic shape retention;
- no structural hash/AABB materialization;
- bounded sampled-site map (`384`);
- bounded exact source-key map (`512`);
- bounded source identity tracking (`128` per key);
- source-specific identity work only on rare builders;
- dump/registry resolution occurs after the main-menu marker;
- profiler is diagnostic-only and the PR must remain draft / unmerged.

The first exact-pack run, if authorized by CI, is for attribution rather than a new performance baseline. As PR #69 demonstrated, fine-grained profiling can perturb slow resource workers; any proposed optimization must later be measured with the profiler removed or stripped down.

## CI gate

Build CI must pass `./gradlew build` and packaged bootstrap validation.

Startup CI must reach the semantic main-menu marker with no BootOptim mixin failure and prove non-empty output for all of:

- `event=summary`
- a phase row with non-zero exact join count
- a sampled `operation=join` call site
- a sampled `operation=bitset_ctor` call site
- at least one exact source row from wall / cross-collision / per-state table builders
- the interpretation marker that labels exact vs sampled metrics

The first Startup CI execution passed this attribution gate and demonstrated the shape of the output on the small vanilla benchmark. It observed `90,282` exact joins, `83,887` exact bitset constructions, zero site/source overflows, and a strongly concentrated vanilla `WallBlock.makeShapes` source. Those values are **CI evidence only**, not exact-pack evidence and not a production optimization result.

Exact-pack instructions remain withheld until the corrected metric semantics above also pass Build + Startup CI on the final diagnostic head.

## Decision rules after exact-pack evidence

Do not design a production patch until the exact-pack report supports one of these source-level patterns.

### Lazy static / delayed construction

Candidate only if a meaningful source family constructs expensive immutable tables before they are needed for title-screen semantics and source inspection proves delayed initialization is safe.

### Canonicalization / reuse

Candidate only when the same exact parameter tuple is rebuilt across owners and the resulting geometry is semantically immutable/context-independent. Reference identity must not be observable by callers that matter.

### Precomputed immutable result

Candidate when a small, closed tuple space dominates and source semantics are version-stable enough to validate exact equivalence.

### Source-specific optimization

Preferred over a generic cache if one block/mod class dominates task-sum and has a narrow invariant that can be proven.

### Rejection / low ceiling

Reject this lane if joins are broadly distributed, source tuples are mostly unique, work is off the critical path, or task-sum is large while measured A/B wall improvement remains negligible.

No generic voxel-shape cache is justified by this diagnostic alone.
