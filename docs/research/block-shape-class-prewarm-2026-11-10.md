# Block shape class prewarm — 2026-11-10

## Boundary and audit

Initialize Simulated's `SimBlockShapes`, Propulsion's `PropulsionShapes`,
TFMG's `TFMGShapes` and Create Crafts & Additions' `AlternatorBlock`
on separate daemon threads after vanilla Bootstrap returns. Keep the
original code, evaluation order within each class, and JVM publication
barrier. The render and registration threads never wait on a BootOptim
future. The only possible wait is ordinary JVM class initialization if
an early consumer reaches a class while its initializer is running.

The reachable non-vanilla path consists of the four initializers, their
shape builders, Ponder's VoxelShaper and its rotation helpers, and the
Alternator's superclass/default-interface initialization chain. The
audited methods have no dependency on a mod registry or render state.
They allocate shape-local data and call the same vanilla shape operations.
The wall/cross/pipe memo from #369 is scoped to its builders. Its session
is thread-local, publication uses putIfAbsent, and these four paths
don't enter those builder sessions.

The final mixed-in bytecode is checked before any early initialization.
The contract includes method code, implicit initializers and hierarchy.
It accepts audited Propulsion code with or without its additional
Advanced Tilt Adapter shape. The additional shape uses the same builder
and vanilla box call. No version, JAR hash or file-size gate is used.
Missing or changed code leaves the affected class on the normal path
and logs a per-mod compatibility fallback. The optional interface
audit mixin adds an empty marker which the hierarchy check ignores.

Create's `AllShapes` stays outside this scope. Reading the already
bootstrapped `Blocks.PISTON_HEAD` is valid in itself. That initializer
also derives a piston state via `StateHolder.setValue()` and then
dispatches through `BlockState.getShape()`. In the physical pack,
FerriteCore's enabled neighbor-lookup replacement redirects that
`setValue()` path through its FastMap. That state-table path and the
virtual shape dispatch are outside the builder-only contract. The chute helper reached by this initializer
was audited separately from the chute method that reads Create's
registrations. Reopen AllShapes with an exact guard for the piston
dispatch and its state/cache dependencies at the post-bootstrap boundary.

## Physical shape differential

Windows 11 desktop, fixed local NeoForge 21.1.252 pack, Java 25.0.1,
Palladium `fastBitSets:true` in both arms. A diagnostic after the title
screen dumped six shape queries for each registered state, using the
same empty-block and collision context as the Palladium experiment.
All four candidate workers activated and no candidate worker fell back.
The control and candidate both produced 1,880,694 records for 313,449
states with identical SHA-256
`11fb472acfff0df32f394fdf99072fe6c593e2b748b9c68f33025ffb9be3e86f`.
These diagnostic launches were excluded from the timing sample.

## Hosted exact-pack

[Exact-pack smoke](https://github.com/wachipayox/BootOptim/actions/runs/42846890744)
reached `main_menu` in 90.596 s from JVM start on Linux/Xvfb, Java 25.0.4.
Beta revision `rev_621da0e4b8f761e7d438327b888b49b2`, manifest
`81c4717b1cfa4ab0194f208be388a031635976bcd950df1b3868544f2f673d6a`.
All four mechanisms activated, including Propulsion 1.1.5. Zero
compatibility fallbacks, zero BootOptim mixin errors and no stall.
The final worker ended 13.046 s before the block-registry end marker.

## Physical A/B and decision

**Keep the four-target prewarm.** Same Windows desktop and fixed pack as the
shape differential. Java 25.0.1 plus AOT, one discarded training launch per
variant, AOT verified on all timed runs. Control `d82747b1`, candidate
`8d0577d7`. End-to-end times start at the JVM and are elapsed wall.
Block registration is the interval from the preceding registry marker
(`register_mob_effect`) to `register_block`. These intervals are not CPU
samples or additive worker savings.

| Order | Variant | Main menu (s) | Visible menu (s) | Block interval (s) | Bootstrap end to block end (s) |
|---|---|---:|---:|---:|---:|
| A1 | Control | 44.679 | 51.066 | 7.282 | 13.030 |
| B1 | Candidate | 46.957 | 50.381 | 5.138 | 12.563 |
| B2 | Candidate | 43.880 | 50.590 | 5.263 | 11.478 |
| A2 | Control | 48.215 | 54.743 | 8.599 | 14.771 |
| | Candidate minus control medians | -1.029 | -2.419 | -2.740 | -1.880 |

The candidate completed all four workers before the preceding registry
marker in both runs. The worker windows overlap. The latest worker
completion was 28.175 s in B1 and 26.671 s in B2 from JVM start. Block
registration and the visible menu were earlier in both candidate runs.
`main_menu` ranges overlap, so the small median improvement at that marker
is less certain. The candidate reaches the preceding register marker
1.010 s later at the median. The bootstrap-end-to-block-end contraction
shows some of the relocated work stayed off the earlier critical path.

All four starts reached both endpoints. No BootOptim compatibility
fallback or mixin errors. Texture lookup and intermittent skin lookup
errors were also seen in the controls. The structural shape probe and
smokes establish the exercised workloads. Prewarming still brings
class loading and allocations earlier, and other packs can schedule
work differently.

Candidate bootstrap JAR SHA-256
`f03882572747816717bad686e5c7d78644f22e92063fd9c2f8518c32f023e55d`.
