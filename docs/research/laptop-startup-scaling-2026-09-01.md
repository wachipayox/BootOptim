# Laptop startup scaling profile — 2026-09-01

Status: **PROFILED / ROADMAP INPUT**

This document preserves the first whole-startup JFR profile of the exact reference pack on a materially slower 4-thread / 6 GiB laptop, compares it with the same BootOptim campaign build on the fast reference PC, and records the architectural priorities implied by the scaling behavior.

The purpose is not to claim that every sampled hotspot is directly removable. It is to answer a more useful question for BootOptim: **which kinds of eager startup work become disproportionately expensive on weak hardware, and therefore deserve redesign rather than another fast-PC micro-optimization?**

## Run identity

Laptop campaign run:

- BootOptim wrapper: `bootoptim-startup-scaling-campaign-pr65-f78e88d-v0.1.5.jar`
- Java: `25.0.4+7-LTS-189`
- processors reported by BootOptim: `4`
- maximum heap: `6144 MiB`
- mod entrypoint: `112,594 ms`
- main menu: `337,244 ms`
- JFR start: `2026-09-01T02:00:56Z`
- JFR duration reported by JMC export: `321 s`
- result: main menu reached
- JFR setup overhead reported by BootOptim: `1,457.985 ms`
- JFR close happened after the main-menu marker and therefore is **not** part of the `337,244 ms` startup figure

Comparable fast-PC campaign run using the same `f78e88d` campaign build:

- Java: `25.0.1+8-LTS`
- processors: `16`
- maximum heap: `16384 MiB`
- mod entrypoint: `28,541 ms`
- main menu: `68,920 ms`

Whole-run scaling:

- pre-mod-entrypoint: about `3.95x`
- main-menu wall time: about `4.89x`

The laptop is therefore not merely a uniformly slower version of the fast machine. Several resource-loading phases scale far worse than the total startup ratio.

## Model/resource pipeline scaling

The same campaign instrumentation exposes the following comparable phases. These are futures/nested phases and **must not be added together**.

| Phase | Fast PC | Laptop | Laptop / fast |
| --- | ---: | ---: | ---: |
| block states | `755.310 ms` | `15,420.822 ms` | **20.42x** |
| atlas stitch | `2,254.148 ms` | `35,280.970 ms` | **15.65x** |
| block models | `3,843.710 ms` | `35,962.690 ms` | **9.36x** |
| ModelBakery construction | `4,494.334 ms` | `17,053.364 ms` | **3.79x** |
| `bakeModels` | `5,608.382 ms` | `19,057.224 ms` | **3.40x** |
| all resource preparations | `14,890.963 ms` | `75,140.983 ms` | **5.05x** |
| reload all done | `24,087.988 ms` | `144,438.563 ms` | **6.00x** |
| FancyMenu panorama preload | `2,570.533 ms` | `14,077.637 ms` | **5.48x** |

On the laptop, `ModelManager` remains the real preparation gate:

- preparation gate: `75,126.080 ms`
- critical order wait: `54,931.036 ms`
- critical post-turn: `22,678.691 ms`

Notable apply/post-turn work also becomes visible:

- Veil `FramebufferManager`: about `3,698 ms`
- `EntityRenderDispatcher`: about `1,601 ms`
- `FontManager`: about `229 ms`

The most important conclusion is the **shape of the scaling**. The actual model bake scales around 3.4x, close to a plausible CPU-frequency/core-pressure slowdown. The resource-facing phases scale from roughly 9x to 20x. That is the strongest current evidence that the weak-hardware bottleneck is disproportionately driven by **enumeration, filesystem/JAR traversal, reads, decompression/decoding, JSON parsing, dependency discovery and eager materialization**, not simply `FaceBakery` arithmetic.

### Priority consequence

After the production generated-item direct baker, further `FaceBakery`/per-face micro-optimization is no longer the first global target for slow hardware. The largest architectural target is now a **hybrid lazy resource/model pipeline** that avoids loading or materializing work that is not required before the title screen, while preserving NeoForge/mod event semantics.

## Whole-startup CPU samples

The JMC “Java Methods that Execute the Most” export is statistical sampling, not an inclusive wall-time profile. The top entries nevertheless identify several families large enough to investigate.

Selected rows:

| Method family | Samples | Share |
| --- | ---: | ---: |
| `BitSetDiscreteVoxelShape.lambda$join$0` | 750 | 4.88% |
| FancyMenu `Resource.waitForLoadingCompletedOrFailed` | 629 | 4.10% |
| Lithium `LithiumDoublePairList.forMergedIndexes` | 391 | 2.55% |
| `ArraysSupport.unsignedHashCode` | 316 | 2.06% |
| Guava `RegularImmutableMap.get` | 296 | 1.93% |
| `Class.copyFields` | 295 | 1.92% |
| `BitSetDiscreteVoxelShape.lambda$join$1` | 278 | 1.81% |
| `HashMap.getNode` | 276 | 1.80% |
| `LongOpenHashSet.add` | 267 | 1.74% |
| Decocraft `applyElementRotation` | 224 | 1.46% |
| ASM `ClassReader.readCode` | 175 | 1.14% |
| `StateHolder.getValue` | 157 | 1.02% |
| `BitSetDiscreteVoxelShape.<init>` | 147 | 0.96% |
| MoreCulling block-state cache lambda | 141 | 0.92% |
| Sodium `ColorSRGB.linearToSrgb` | 122 | 0.79% |
| MoreCulling `SpriteUtils.doesHaveTranslucency` | 121 | 0.79% |

The explicitly visible voxel-shape join/merger/construction rows alone sum to about **10.20% of sampled Java execution**. This is not a claim that 10.20% of startup wall time can be removed; it is a strong enough signal to justify a source-level profiler for the callers producing those joins.

## Voxel-shape finding

The profile makes voxel-shape construction a new high-priority research lane:

- `BitSetDiscreteVoxelShape` joins are the hottest Java method family in the export.
- Lithium’s `LithiumDoublePairList` merger is already in the hot path.
- `BitSetDiscreteVoxelShape` also appears in allocation pressure.
- C2 spent `7.51 s` compiling `WallBlock.makeShapes(...)` in this run.

Two existing optimizations mean the next experiment must use a different premise:

1. Lithium already replaces vanilla’s `IndirectMerger` with `LithiumDoublePairList`.
2. Exact-pack logs report ToadLib `fastBitSets=true`.

Therefore **do not implement another generic faster merger/bitset solely because the JFR shows shape cost**. First identify *who* constructs/joins the shapes and how often equivalent immutable results are rebuilt.

High-value questions for the next shape profiler:

- Which block classes / mods account for the join calls during startup?
- How much is static initialization vs registration vs later client setup?
- How many shape builds repeat by exact parameter tuple or equivalent geometry?
- Does `WallBlock.makeShapes` or another small set of constructors dominate?
- Can expensive immutable shape tables be generated lazily, shared, canonicalized or precomputed without changing reference/identity-sensitive behavior?
- What exactly does the installed ToadLib `fastBitSets` option transform in this version?

Do not claim compatibility with ToadLib until its implementation has been inspected.

## JIT compilation pressure

JMC compiler statistics:

- compiled methods: `30,832`
- total compiler time: `4 m 27 s`
- peak single compilation time: `7.52 s`
- OSR compilations: `419`
- standard compilations: `30,413`
- resulting compilation size: `84.3 MB`
- resulting code size: `59.4 MB`

Longest examples include:

- `WallBlock.makeShapes(...)`: `7.51 s`
- ASM `ClassReader.readCode(...)`: multiple compilations between roughly `1.2 s` and `2.33 s`
- Mixin `Locals.getLocalsAt(...)`: `2.02 s`
- Mixin `MixinApplicatorStandard.apply(...)`: `1.76 s`
- Mixin `ClassInfo.<init>`: `1.34 s`
- `BlockStateModelLoader`: `1.16 s`
- `MultiVariant.bake`: `1.04 s`
- `ModuleClassLoader.loadClass`: `979 ms`
- UnionFS `newDirStream`: `929 ms`

### Critical interpretation rule

The `4 m 27 s` value is **aggregate compiler-thread time**, not `267 s` of stop-the-world or recoverable startup wall time. It must never be subtracted from the 337 s startup as though it were serial.

It still matters on a four-processor machine because C1/C2 contend for scarce CPU, instruction cache and memory bandwidth while startup threads are active. This makes “remove eager work” more valuable than its direct method timer alone suggests: less work can also mean fewer hot methods to compile, fewer allocations and less downstream GC pressure.

Do **not** make “tune JIT flags” a BootOptim product feature from this profile alone. Prefer reducing the eager Java workload that causes compilation pressure.

## Allocation pressure and GC

Top allocation sites include:

- `LongOpenHashSet.<init>`: `10.46%`
- `Field.copy`: `9.99%`
- `InputStream.readNBytes`: `8.29%`
- `DirectMethodHandle.allocateInstance`: `8.19%`
- Gson `JsonReader`: `3.75%`
- voxel-shape join lambda: `2.92%`
- `Arrays.copyOfRange(byte[])`: `2.85%`
- reflective access verification: `2.13%`
- `BufferedReader`: `1.97%`
- `Arrays.copyOf(byte[])`: `1.89%`
- JDK classfile `EntryMap`: `1.80%`
- Lithium `LithiumDoublePairList`: `1.12%`
- BootOptim Decocraft rotated-quad path: `1.10%`

Top allocated object classes:

- `byte[]`: `21.00%`
- `long[]`: `10.79%`
- `java.lang.reflect.Field`: `9.99%`
- voxel-shape lambda object: `7.21%`
- `int[]`: `6.29%`
- `char[]`: `5.20%`
- `boolean[]`: `3.54%`
- `Object[]`: `3.13%`
- `String`: `2.43%`
- `WeakReference`: `2.13%`
- Mixin `CallbackInfoReturnable`: `1.46%`
- JOML `Matrix4f`: `1.10%`

Allocation pressure by thread:

- `Worker-ResourceReload-1`: `24.43%`
- `modloading-sync-worker`: `20.38%`
- Render thread: `17.26%`
- main: `14.78%`
- `pool-7-thread-1`: `4.82%`
- `Worker-ResourceReload-2`: `4.19%`
- `modloading-worker-0`: `2.58%`
- `Worker-ResourceReload-0`: `2.19%`

GC export:

- total pause time: **`11.6 s`**
- pause count: `131`
- median: `72.1 ms`
- average: `88.5 ms`
- P90: `219 ms`
- P95: `245 ms`
- maximum shown in the raw GC list reaches hundreds of milliseconds; later young collections include values around `433 ms`

`11.6 s` is about **3.44%** of the laptop’s measured main-menu wall time. GC is therefore material, but it is not the dominant root cause.

The JMC summary reports a `P99=669 ms` while the same summary reports `Maximum=573 ms`. Treat this as an export/JMC percentile inconsistency and do not build decisions on that percentile. Use total pause time and the raw per-GC list instead.

### Priority consequence

Do not pursue GC flags as the primary solution. Reduce the byte-array, reflection, JSON, shape and eager-resource allocation sources. A successful lazy/resource redesign should improve both execution and GC as a secondary effect.

## Storage and I/O evidence

JFR recorded `8,899` file-read events. The read-by-path export shows substantial physical data movement.

Examples:

- Decocraft JAR: `3,603` reads, `11.8 MB`
- MusicMakerMod JAR: `2` reads, `9.3 MB`
- FancyMenu slideshow image: `2` reads, `8.5 MB`
- individual FancyMenu panorama PNGs commonly around `5–6 MB`
- many further panorama/slideshow images around `2–5 MB`

File writes are tiny by comparison and are not a current priority.

Contention-by-site also contains many `FileChannelImpl.implRead` groups:

- 825 events, average `12.0 ms`, max `366 ms`
- 544 events, average `11.5 ms`, max `126 ms`
- 227 events, average `23.3 ms`, max `125 ms`
- 145 events, average `12.6 ms`, max `110 ms`

These rows must not be blindly summed because JFR/JMC grouping and overlap matter, but they reinforce the phase-scaling evidence: storage/ZIP/resource traversal is a real weak-hardware cost center.

### FancyMenu

BootOptim’s production panorama supplier prelaunch remains a valid scheduling optimization. On the laptop it still has to wait roughly `14.1 s`, compared with ~`2.57 s` on the fast PC. The profile also shows `Resource.waitForLoadingCompletedOrFailed` at `4.10%` of Java samples.

This does **not** invalidate the existing optimization. It shows its remaining lower bound is now dominated by the physical decode/read work. Future FancyMenu work, if revisited, should investigate conservative persistent decoded/processed resource caching or earlier safe read-ahead with exact file invalidation; not another copy of the already-shipped supplier-prelaunch mechanism.

## Contention and thread-count lesson

Contention by thread includes:

- `Worker-ResourceReload-1`: 778 events, avg `13.0 ms`, max `366 ms`
- `Worker-ResourceReload-2`: 744, avg `15.9 ms`, max `186 ms`
- `Worker-ResourceReload-0`: 483, avg `12.4 ms`, max `126 ms`
- multiple `modloading-worker-0` groups with maxima up to `181 ms`
- Render thread: 19, avg `24.9 ms`, max `249 ms`
- common ForkJoin workers: about 212–224 events each, avg ~`9.6–9.9 ms`

Contention by site repeatedly shows:

- `FileChannelImpl.implRead`
- Mixin launch plugin `handlesClass(...)` / `processClass(...)`
- Palladium deduplication
- one long `PrintStream.write` event

The laptop is not a case where “use more threads” is automatically correct. With only four reported processors, background work, compiler threads, resource workers and I/O already compete. The preferred architecture is to **remove work and bound useful overlap**, not multiply workers indiscriminately.

This is consistent with the project’s earlier PR #14 result: eager top-level bake parallelism improved an isolated bake but regressed time-to-menu.

## Mixin / class transformation interpretation

The laptop reaches BootOptim’s mod entrypoint at `112.594 s`, versus `28.541 s` on the fast PC (~`3.95x`). The JFR also shows:

- ASM `ClassReader.readCode` in hot samples and long C2 compilations
- Mixin launch plugin methods throughout contention rows
- significant reflection allocation (`Field.copy`, `Class.copyFields`)
- module/classloader work among long compilations

This keeps the pre-entrypoint transformation pipeline important, but the old rejected mechanisms remain rejected:

- PR #43 generic side-load byte cache: ~3.71% hit rate, ~41.7 ms estimated saved, ~55 MiB retained
- PR #46 ClassInfo negative-cache bug: only ~4.7 ms of repeated missing resolution
- PR #48 external post-Mixin ASM writer tail: ~760.7 ms total, not a hidden multi-second tail

Issue #67 now captures the materially different architectural hypothesis: a **persistent cross-launch post-Mixin transformed-class cache** for deterministic rewritten classes.

The blocker is real: current ModLauncher exposes no supported public API to replace/decorate the `mixin` launch plugin after the lifecycle point where doing so would be safe. A production implementation must not crack module encapsulation casually with `Unsafe` or `IMPL_LOOKUP`.

Therefore #67 is **P1 research, conditional on a safe interception mechanism**:

- look for an official ModLauncher/NeoForge extension point;
- consider an upstream decorator hook;
- quantify `IMixinConfigPlugin` dynamic semantics and safely cacheable coverage;
- only use deep-access wrapping as a version-gated diagnostic proof if needed, never as silent production behavior.

## Classloading export caveat

The JFR event summary contains `ClassLoad=0` and `ClassDefine=0`. Accordingly, `09-longest-class-loading.txt` says no events were found.

This must **not** be read as evidence that classloading is free. It means this recording configuration did not capture the per-class loading events needed by that JMC view. Other evidence (ASM, ModuleClassLoader, reflection, Mixin) still shows class/transformation pressure.

A future classloading-specific campaign should explicitly enable the required class load/define events rather than infer from this empty view.

## ModernFix dynamic-resources prior art

ModernFix 1.21.1 contains a substantial `perf.dynamic_resources` implementation. Source inspection confirms this is not a trivial “skip baking models” switch. It includes:

- a lazily loading `ModelBakery.topLevelModels` view;
- dynamic unbaked and baked model lookup;
- locks and in-flight recursion guards;
- LRU-backed caches;
- skipped eager item-model enumeration;
- dynamic blockstate loading;
- emulated baked model registries;
- NeoForge model-event wrappers;
- integrations for third-party consumers that access model maps directly.

`ModelBakeEventHelper` explicitly avoids materializing every model in `Map.replaceAll` where possible because fetching every model is described as “insanely slow”.

This proves the general architecture is feasible, but it also demonstrates the compatibility surface.

Important finding: ModernFix’s early config explicitly has a default override setting `mixin.perf.dynamic_resources=false`. Do not assume this pack is currently using the dynamic model path. Before BootOptim implements overlapping logic, determine the exact pack setting and why it is disabled/defaulted.

### BootOptim direction

Do **not** copy ModernFix wholesale. Investigate a hybrid design that complements the installed environment and targets the parts proven to scale catastrophically on the laptop:

- resource enumeration/indexing;
- blockstate/model file discovery;
- eager model universe materialization;
- atlas source discovery/decode where semantically possible.

Any candidate must preserve or emulate:

- `ModelEvent.RegisterAdditional`
- `ModelEvent.ModifyBakingResult`
- `ModelEvent.BakingCompleted`
- custom geometry/loaders
- direct map access by mods
- key/entry/value iteration expectations
- cross-namespace lookups
- resource-pack reload invalidation
- missing-model semantics
- thread/render ownership constraints

An exact-pack verifier/fail-open path is mandatory.

## Priority roadmap after this profile

### P0 — hybrid lazy resource/model pipeline

This is the highest-value global architectural target for the laptop.

Research before implementation:

1. Determine the exact ModernFix `perf.dynamic_resources` setting and any compatibility overrides in the reference pack.
2. Split the observed `block_states`, `block_models` and atlas costs into:
   - directory/JAR enumeration,
   - file reads/decompression,
   - image decode,
   - JSON decode/parse,
   - dependency resolution,
   - object/materialization work.
3. Identify which resources/models are actually demanded before the title screen.
4. Design the smallest lazy/indexed mechanism that avoids eager work without replacing the whole model system unless necessary.
5. Prefer persistent metadata/indexes only with conservative resource-pack/JAR/config invalidation.
6. Build an exhaustive compatibility verifier and fail-open path before any performance claim.

The objective is not just to make `ModelBakery` faster; it is to avoid creating work that the title screen does not need.

### P0 parallel — voxel-shape startup construction

Build a diagnostic first. Attribute joins and allocations to owning block class/mod and parameter identity. Measure repetition and startup critical-path position.

Promising mechanisms, only if measurement supports them:

- lazy static shape-table construction;
- canonicalization/interning of equivalent immutable results;
- memoization by small immutable parameter tuples;
- precomputed direct construction that avoids repeated generic joins.

Do not duplicate Lithium merger or ToadLib bitset work.

### P1 — persistent Mixin rewrite reuse / #67

Keep researching only under the new cross-launch premise. The major technical gate is a safe interception/decorator point plus conservative invalidation and `IMixinConfigPlugin` semantics.

Do not reopen #43/#46/#48 mechanisms.

### P1 — resource I/O attribution and indexing

Even if full model laziness proves too risky, a narrower resource-layer optimization may still be valuable:

- reload-scoped directory indexes;
- fewer repeated UnionFS/ZIP directory walks;
- bounded read-ahead;
- persistent resource metadata keyed by exact pack/JAR/file state;
- avoid duplicate JSON/image reads where identity and invalidation are provable.

The JFR `FileChannelImpl.implRead` contention and the 9–20x resource-phase scaling justify this as a standalone research lane.

### P2 — FancyMenu residual physical image work

Existing prelaunch stays. Reopen only with a distinct mechanism: persistent decoded-resource cache or earlier safe I/O overlap with exact invalidation.

### P2 — MoreCulling startup caches

MoreCulling appears in the visible CPU sample set (~0.9% + ~0.8% rows). Worth a medium experiment after P0/P1, especially if it performs full sprite/block-state scans eagerly. Preserve semantics and avoid optimizing counts without wall-time evidence.

### P3 — logging / miscellaneous

A single `PrintStream.write` contention event reached `573 ms`, but this is not enough to justify broad log suppression. Logging changes can hide diagnostics and alter mod behavior. Only revisit if a dedicated measurement finds a repeatable logging wall-time block.

## Explicit non-priorities from combined history

Do not reopen these solely because the laptop is slow:

- top-level model identity cache from PR #36;
- eager top-level model parallelism from PR #14;
- generated-item span cache based on repeat count;
- per-material resolution caches from PR #57;
- compiled/live `ElementsModel` traversal plan from PR #66;
- generic Mixin side-load cache from PR #43;
- Mixin ClassInfo negative-cache optimization from PR #46;
- external ASM writer-tail optimization from PR #48.

Slow hardware changes the *value* of removing expensive work, but it does not turn a mechanism with a demonstrated negative/near-zero ceiling into a good design.

## PR #66 final result: live ElementsModel traversal plan

PR #66 tested a live-reference flattened traversal attached to the persistent `BlockModel` owner.

Semantic verifier revision:

- `5,448 / 5,448` matches
- `0` mismatches
- `0` fallbacks
- candidate: `189.190 ms`
- stock verification path: `179.709 ms`

An intentionally optimistic ceiling test then removed the per-call structural validation while still comparing against and returning stock:

- `5,448 / 5,448` matches
- `0` mismatches
- `0` fallbacks
- candidate: **`102.394 ms`**
- stock: **`91.267 ms`**

Even the unsafe upper-bound implementation was about **12.2% slower**. The traversal flattening premise therefore has no positive measured ceiling and PR #66 was closed unmerged.

Future normal-elements work needs a materially different premise below/around the remaining face work, or should be superseded by the larger eager-resource architecture.

## Validation policy for weak-hardware work

The laptop is now an important acceptance environment, but it does not replace the fast reference machine.

For architectural changes:

1. compile/package CI;
2. startup CI reaches menu;
3. exact reference pack semantic/runtime validation;
4. A/B on the fast PC;
5. A/B on the 4-thread / 6 GiB laptop;
6. distinguish direct CPU saved, overlapping future time, preparation-gate time and end-to-end menu time;
7. watch allocations/GC as secondary effects;
8. preserve a kill switch and fail-open path for risky lazy/cache mechanisms.

A change that helps only the laptop can still be valuable if it is safe and does not regress the fast machine. Conversely, a fast-PC micro-win that makes the laptop worse is not a successful BootOptim optimization.

## Measurement caveats

- Resource reload preparation/listener times overlap; do not sum them.
- JFR execution samples are statistical CPU evidence, not wall-time attribution.
- JIT “total time” is aggregate compiler-thread time, not serial startup time.
- Allocation pressure percentages come from sampled allocations, not exact total allocated bytes.
- JFR contention rows can be grouped/repeated; do not naively sum all rows.
- The classloading view is empty because class load/define events were not captured.
- JFR itself adds overhead. The phase *ratios* and large qualitative differences are still strong enough for research prioritization, but production speedups require non-JFR A/B runs.

## Preserved evidence

Searchable JMC text exports supplied for this run are represented under:

`docs/research/evidence/laptop-jfr-2026-09-01/`

The ChatGPT File Library interface used for this documentation exposes searchable text chunks but not byte-for-byte raw file transfer into GitHub. For that reason the repository copies are explicitly named `*.excerpt.txt` and contain the exact rows used by this analysis rather than pretending to be complete originals.

If the original export ZIP is later supplied as a directly downloadable attachment, these excerpts can be supplemented/replaced by the untouched raw TXT exports while retaining this analysis document.
