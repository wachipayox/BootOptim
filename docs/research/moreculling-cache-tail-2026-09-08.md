# MoreCulling cache listeners in the post-preparation tail — 2026-09-08

## Status

**ACTIVE — attribution confirmed; opt-in scheduling probe submitted as PR #192.**

Independent static review in PR #191 confirms the attribution and adds an
important constraint: neither listener is safe to move into preparation. The
probe below therefore tests only same-barrier internal scheduling; it is not a
`prepare/commit` implementation and must not be promoted merely because it
reduces the listener's own wall time.

This entry explains the two anonymous `Minecraft$$Lambda` slots reported by the
PR #184 exact-pack diagnostic. It does not yet promote a runtime optimization.

## Evidence and origin

The reference exact-pack artifact from PR #184 (`exact-pack-result-smoke-1`,
run `34175380705`) records the process-origin-to-main-menu boundary used by the
critical-path profiler. In that boundary, after the preparation barrier:

| listener | owner shown by profiler | exclusive serial slot |
| --- | --- | ---: |
| 25 | `Minecraft$$Lambda/0x000000005cc94000` | 3,590.560 ms |
| 26 | `Minecraft$$Lambda/0x000000005cc94260` | 852.055 ms |

The exact pack contains MoreCulling 1.0.8. Its transformed `Minecraft`
constructor registers two consecutive listeners immediately before constructing
`LevelRenderer`:

1. iterate `Block.BLOCK_STATE_REGISTRY` and call
   `StateCullingShapeCache.moreculling$initShapeCache()`;
2. iterate the block-model map and, for every non-occluding state, call
   `BakedOpacity.moreculling$resetTranslucencyCache(state)` inside a
   fail-open `try/catch`.

The bytecode and upstream source agree on these bodies. The first listener is
the 3.590560 s slot and the second is the 0.852055 s slot; the attribution is
now a callsite fact rather than an inference from an anonymous class name.

The independent dynamic probe in PR #189 correlated the final listener list by
object identity in two fresh exact-pack VMs (`list_size=72`, 25→30046,
26→30050, `registration_match=true`, zero mixin errors). It did not wrap or
time listener execution and added only 701.623/725.418 microseconds of probe
self-time. This closes the identity question without weakening the scheduling
safety gate.

## Semantic and threading assessment

The second listener scans immutable baked-model quad lists and reads immutable
`NativeImage` pixels, then writes flags to the distinct `BlockState` key. Its
`WeightedBakedModel` path can call a platform `getQuads` implementation, so a
parallel candidate must retain the per-entry fail-open catch and must not assume
all custom models are thread-safe.

The first listener is more sensitive. For states with no baked culling shape it
calls arbitrary mod-defined `Block#getOcclusionShape` implementations through
`EmptyBlockGetter`; those callbacks may not be thread-safe. The MoreCulling
state/model fields are per-object, but a model culling shape may be lazily
optimized and shared by several states. A generic parallel loop is therefore a
diagnostic hypothesis, not a production-safe conclusion.

## Candidate and gate

PR #192 (`codex/moreculling-parallel`) adds required-false mixins that only
match MoreCulling's transformed callsites. The primary A/B candidate is a
reload-local identity cache for repeated `VoxelShape.getFaceShape` results
inside listener 25:

- `-Dboot_optim.morecullingShapeFaceDedup=true`

It preserves the original block callback and only reuses the six derived faces
when the exact same shape object recurs during that invocation; the ThreadLocal
cache is removed at return. Two separate scheduling probes are also present for
follow-up, but are not enabled by the primary A/B:

- `-Dboot_optim.morecullingParallelShapes=true`
- `-Dboot_optim.morecullingParallelOpacity=true`

The candidate snapshots the iterable/map on the reload thread, runs bounded
daemon workers, joins every task, and leaves the stock path untouched when the
property is absent. It is intentionally not production code yet.

Required evidence before any promotion:

1. the exact-pack run must prove that the redirects actually match MoreCulling
   (not merely pass because the mixin was skipped);
2. each property must be measured separately with fresh hosted A/B repetitions;
3. no BootOptim/Mixin errors or semantic differences may occur;
4. the critical-path wall result, not task-sum CPU, must improve materially;
5. the first-listener candidate needs an additional compatibility decision for
   arbitrary `Block#getOcclusionShape` callbacks, even if hosted timing wins.

PR #191's independent conclusion is that generic parallelization is a
provisional no-go. The reopen path is an algorithmic optimization that retains
the exact apply barrier and proves equivalence for every affected state/model;
the probe is useful only to falsify that path with an exact-pack run or to
identify a narrowly safe subset.

If the hosted gain is small or the mixin cannot be proven active, keep the
attribution and close only the scheduling implementation, not the MoreCulling
cost itself. A physical laptop run is justified only after a coherent hosted
win because the laptop may amplify CPU/HDD-sensitive work.

## Sources

- PR #184 diagnostic and exact-pack artifact: `https://github.com/wachipayox/BootOptim/pull/184`
- MoreCulling 1.21.1-multiloader source (`Minecraft_managersMixin`):
  `https://github.com/fxmorin/MoreCulling/blob/1.21.1-multiloader/common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java`
- MoreCulling 1.21.1-multiloader source (`BlockStateBase_cullShapeMixin`):
  `https://github.com/fxmorin/MoreCulling/blob/1.21.1-multiloader/common/src/main/java/ca/fxco/moreculling/mixin/models/cullshape/BlockStateBase_cullShapeMixin.java`
