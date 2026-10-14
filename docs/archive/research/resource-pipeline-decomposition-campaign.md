# Resource/model pipeline decomposition campaign

Status: **ACTIVE DIAGNOSTIC — DO NOT MERGE PROFILING CODE INTO INTEGRATION**

Branch: `agent/profile-resource-pipeline-decomposition`

This campaign is the source-level follow-up to the 2026-09-01 slow-laptop JFR evidence. It intentionally carries the broad startup-scaling instrumentation from PR #65, then adds a narrower decomposition of the three resource-facing phases whose laptop/fast-PC scaling was pathological:

- block states: ~20.42x;
- atlas preparation/stitch: ~15.65x;
- block models: ~9.36x;
- for comparison, `bakeModels` itself scaled only ~3.40x.

The question is not "which method has many calls?". The question is: **which eager work exists before the title screen, how much wall/CPU-like work does each family consume, and which family could disappear under a conservative lazy architecture?**

## Measurement semantics

Every `BOOTOPTIM_RESOURCE_PIPELINE` row identifies its aggregation mode.

- `wall_scope_sum`: elapsed time of synchronous/future scopes. Different atlas scopes and async branches can overlap. Do not blindly sum them.
- `task_sum`: sum of individual worker-task/resource durations. This is useful as a CPU/work ceiling and for attribution, but it is explicitly **not** critical-path wall time.
- namespace rows are nested elapsed measurements taken only while a known model/atlas resource context is active. They are attribution evidence, not an independent additive phase.

The first-title-screen marker is emitted before the final aggregate dump. Summary formatting/logging therefore does not inflate BootOptim's main-menu timestamp. JFR remains active until after the dump so unexpected profiler overhead is still visible in the trace.

## Exact 1.21.1 ModelManager hooks

The campaign uses the Mojmap synthetic methods for Minecraft 1.21.1 rather than a generic ResourceManager wrapper:

### Block models

- `ModelManager.lambda$loadBlockModels$7(ResourceManager)` — `MODEL_LISTER.listMatchingResources`, i.e. resource enumeration.
- `lambda$loadBlockModels$10(Executor, Map)` — construction/scheduling of per-resource futures.
- `lambda$loadBlockModels$8(Map.Entry)` — one model resource task (open + parse + pair/result work).
- the `BlockModel.fromStream(Reader)` invocation inside `$8` — inclusive JSON/reader parse cost.
- `lambda$loadBlockModels$9(List)` — final immutable-map collection.

Per-resource task rows retain the resource id, source pack id and namespace, with a bounded top-slowest-resource list.

### Block states

- `ModelManager.lambda$loadBlockStates$11(ResourceManager)` — `BLOCKSTATE_LISTER.listMatchingResourceStacks`, i.e. resource-stack enumeration.
- `lambda$loadBlockStates$14(Executor, Map)` — construction/scheduling of per-blockstate futures.
- `lambda$loadBlockStates$12(Map.Entry)` — one logical blockstate resource-stack task.
- `Resource.openAsReader()` inside `$12` — reader-open overhead.
- `GsonHelper.parse(Reader)` inside `$12` — inclusive parse/read cost, attributed to the current `Resource` source pack.
- `lambda$loadBlockStates$13(List)` — final immutable-map collection.

The task-level and JSON rows are intentionally kept separate. High call counts or high parallel task sums are not treated as end-to-end savings by themselves.

## Atlas decomposition

The atlas path is decomposed at the architecture boundaries present in stock 1.21.1:

1. `SpriteSourceList.load` — atlas definition stack lookup + JSON/codec load.
2. `SpriteSourceList.getSpriteNames` — first source traversal/name discovery.
3. `SpriteSourceList.list` — supplier/resource discovery.
4. `SpriteLoader.runSpriteSuppliers` receives a diagnostic delegating `SpriteResourceLoader`; each delegated `loadSprite(id, Resource)` is timed and attributed by texture id/source pack. This scope includes metadata handling, resource open/read, `NativeImage.read` decode, frame validation and the Forge sprite-content hook.
5. `SpriteLoader.stitch` — post-decode registration/rescale/stitch/region construction. If ModernFix faster texture stitching is active, its internal `Stitcher.stitch` replacement remains in place; this profiler does not replace it.
6. the five-argument `SpriteLoader.loadAndStitch` future — per-atlas outer wall scope.

This split is designed to answer whether the laptop's ~35.3 s atlas branch is dominated by source/resource discovery, image load/decode, or final stitch work.

## Resource-manager namespace attribution

`FallbackResourceManager` is timed only while one of the known block-model, blockstate or atlas discovery contexts is active. The profiler records namespace-level elapsed time for:

- `listResources(path)`;
- `listResourceStacks(path)`;
- `getResource`;
- `getResourceStack`.

Calls from unrelated startup systems are ignored. This is deliberately narrower than a global resource-manager profiler and avoids turning every resource lookup in the pack into a timed/logged event.

The result can show whether enumeration cost is concentrated in particular namespaces. JFR FileRead paths remain the stronger evidence for physical JAR/file latency; namespace timers should be correlated with that recording rather than interpreted as raw disk time.

## ModernFix effective-config census

At the first main menu, the diagnostic reflectively reads ModernFix's already-initialized early config without creating a compile/runtime dependency. It reports the effective controlling option for:

- `mixin.perf.dynamic_resources`;
- `mixin.perf.resourcepacks`;
- `mixin.perf.faster_texture_stitching`;
- `mixin.perf.deduplicate_wall_shapes`;
- stability level when available.

The reflection is fail-open. If ModernFix is absent or its config API changes, BootOptim logs the probe failure and does not affect startup behavior.

This is necessary before using ModernFix dynamic resources as a ceiling/control experiment. The upstream 1.21.1 default override disables dynamic resources, but that default is not proof of the exact pack's effective value.

## Expected decision tree after the laptop run

The next implementation should be chosen from measured ceilings rather than from the phase name:

- **Enumeration / namespace traversal dominates:** investigate ModernFix `perf.resourcepacks` status and residual UnionFS/ZIP directory walks; candidate becomes reload-scoped/persistent resource indexing or avoiding enumeration entirely.
- **Block model/blockstate resource parse dominates:** measure how many parsed resources are actually demanded before title screen; use ModernFix dynamic resources (if currently off) as a diagnostic lazy-control run, then design a compatibility-preserving hybrid rather than copying it blindly.
- **Atlas sprite load/decode dominates:** model laziness will not solve that portion; investigate exact pre-title texture demand, duplicate reads/decode, bounded read-ahead or conservative persistent decoded/processed-resource caching.
- **Final stitch dominates despite ModernFix faster stitching:** inspect whether that option is actually active and what residual `SpriteLoader.stitch` work remains before considering any packing change.

Only after the ceiling is identified should a behavior-changing candidate be written.

## Validation before scarce hardware use

The diagnostic PR must pass:

1. normal Build CI;
2. Startup Benchmark to the main menu;
3. existing #65 JFR/system/reload/model campaign markers;
4. `BOOTOPTIM_RESOURCE_PIPELINE event=summary`;
5. exact ModelManager enumeration rows for both block models and block states;
6. atlas stitch and per-sprite load rows;
7. no BootOptim Mixin failure.

The distributable to use remains the bootstrap JAR under `bootstrap/build/libs/`, not the inner root JAR.

## Non-goals

This branch does not implement lazy ModelBakery, a resource cache, another stitcher, another voxel merger, more worker threads, or any production behavior change. It is a ceiling/attribution campaign only.
