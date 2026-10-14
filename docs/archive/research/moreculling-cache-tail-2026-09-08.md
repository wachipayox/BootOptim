# MoreCulling cache listeners in the post-preparation tail — 2026-09-08

## Status

**ACTIVE — attribution confirmed; first algorithmic candidate measured and
rejected on hosted exact-pack, with a lower-overhead alternative still open.**

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

## Candidate result — startup-scoped face cache (PR #192, 2026-09-08)

The first implementation attempted to redirect MoreCulling's injected
`BlockStateBase.moreculling$initShapeCache` method. Mixin ordering rejected
that redirect even at a lower priority because MoreCulling owns and merges the
method at priority 1000. It was replaced by a diagnostic-only injection on the
pure `VoxelShape#getFaceShape` operation, enabled before startup and disabled
at the title screen. The exact-pack A/B then had zero BootOptim/Mixin errors and
the marker proved activation:

```
entries=69265 requested_faces=1591926 computed_faces=415590 reuse_hits=1176336
```

The three-run medians were:

| variant | main menu | reload→FancyMenu | panorama |
| --- | ---: | ---: | ---: |
| control | 88,876 ms | 41,115 ms | 4,210.001 ms |
| candidate | 91,609 ms | 42,881 ms | 4,734.801 ms |

Candidate minus control was **+2,733 ms (+3.08%)** on the critical-path
median. The single 1,535 ms improvement in candidate iteration 1 was dominated
by hosted-run variance; iterations 2/3 were slower. The result rejects the
global startup hook as a production optimization, not the MoreCulling listener
itself: the hook adds two injections and an identity-map lookup to every face
call during startup. A narrower listener-local, lower-overhead design remains
the only justified reopening before closing the cost entirely.

The follow-up last-shape cache removed the external identity map and was tested
with the workflow's same-VM paired mode (three alternating pairs). Its
candidate-minus-control menu deltas were `-1401`, `-5956`, and `+1258` ms;
the median was `-1401 ms`, but the corresponding
`reload_to_fancymenu_finish_ms` median was only `-123 ms` (`-123`, `-2516`,
`+231` ms by pair). Because the sign flips with the alternating order and the
effect is below the observed runner variance, this is **inconclusive and not a
promotion**. A per-object field cache is now staged as the next, structurally
different diagnostic; its property is
`boot_optim.morecullingShapeFaceObjectCache`.

If the hosted gain is small or the mixin cannot be proven active, keep the
attribution and close only the scheduling implementation, not the MoreCulling
cost itself. A physical laptop run is justified only after a coherent hosted
win because the laptop may amplify CPU/HDD-sensitive work.

The per-object field-cache exact-pack paired run was valid after correcting the
JVM-argument formatting. Its candidate marker was present and Mixin errors were
zero, but candidate-minus-control menu deltas were `+5707`, `+1540`, and `+311`
ms (median **+1540 ms**); reload→FancyMenu deltas were `+2472`, `+981`, and
`-4` ms (median **+981 ms**). This is a diagnostic rejection on hosted, but the
project explicitly allows one physical tie-break when a CPU/allocation-sensitive
effect remains uncertain on the old laptop. The first laptop attempt was invalid
because the distributable wrapper still embedded an older inner mod without the
field-cache class; it reached the menu in `366887 ms` but had no candidate marker
and is excluded. Rebuilding `:bootstrap:jar --rerun-tasks` produced wrapper
SHA-256 `379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989`;
the corrected candidate was then run on the physical laptop with the same
wrapper, JVM and pack. Its canonical startup report reached the menu at
`398658 ms`; the matching control (same corrected wrapper, property false)
reached it at `406363 ms`, an apparent total delta of `-7705 ms`. That total
is not attributable to the probe: the candidate was already `-8402 ms` ahead
before the `mod_entrypoint` marker (`118639` vs `127041 ms), while the
post-entrypoint critical path was `280019` vs `279322 ms` (candidate
`+697 ms`). The candidate also had a roughly `+4.9 s` slower reload-to-
FancyMenu tail, and FancyMenu selected different initial backgrounds in the
two runs (`bg_alvarin_house` versus `bg_inv_noche`). The candidate marker was
active and BootOptim/Mixin errors were zero, so the run is valid as a
diagnostic, but the apparent menu win is pre-entrypoint/layout variance and
the measured critical path is slightly worse. **Do not promote** the
per-object field cache; leave the property disabled and close this particular
implementation. A future MoreCulling attempt needs a lower-overhead,
listener-local design and a fixed initial-layout fixture before another
physical tie-break.

## Sources

- PR #184 diagnostic and exact-pack artifact: `https://github.com/wachipayox/BootOptim/pull/184`
- MoreCulling 1.21.1-multiloader source (`Minecraft_managersMixin`):
  `https://github.com/fxmorin/MoreCulling/blob/1.21.1-multiloader/common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java`
- MoreCulling 1.21.1-multiloader source (`BlockStateBase_cullShapeMixin`):
  `https://github.com/fxmorin/MoreCulling/blob/1.21.1-multiloader/common/src/main/java/ca/fxco/moreculling/mixin/models/cullshape/BlockStateBase_cullShapeMixin.java`
