# Palladium FastBitSet option — 2026-11-10

**Rejected at the shape-equivalence gate.** Keep the pack setting as shipped.
This is a pack-configuration experiment. BootOptim needs no runtime patch.

## Premise and method

In the installed Palladium 1.1.8 binary, the TAIL injection in
`BitSetDiscreteVoxelShape.<init>(III)V` replaces `storage` with a
`FastBitSet` only when `fastBitSets` is true. That implementation of
`set(int)` updates both a fastutil long set and the superclass bit set.
Disabling the option keeps the underlying constructor's storage. This
experiment is separate from the Palladium variant-constructor audit (#324).

On a physical Windows 11 desktop (i5-12600KF, RTX 3070), both variants ran
the same fixed local NeoForge 21.1.252 pack, BootOptim `d82747b1` plus an
identical diagnostic, Java 25.0.1 and a loaded AOT cache. The only
configuration difference was `fastBitSets:true` versus `false`. Both
reached the title screen and the loading overlay hid. The diagnostic ran
after opening the title screen. It enumerated all registered block states,
with an empty block getter at the origin and an empty collision context,
and wrote sorted records for outline, collision, occlusion, block support,
visual and interaction shapes. Each record contains the shape class,
ordered coordinate grids and enumerated boxes, with doubles serialized
as raw bits. Both complete files were compared by key. The initial
partial read was discarded.

## Result

| Setting | States | Shape records | SHA-256 |
|---|---:|---:|---|
| true | 313,449 | 1,880,694 | `11fb472acfff0df32f394fdf99072fe6c593e2b748b9c68f33025ffb9be3e86f` |
| false | 313,449 | 1,880,694 | `1ba31a4a8dc49895ceb6629693b397d45a9a59b46773eb49f9efd7bbbaf27aa6` |

44,418 records differ. Comparing unions on the combined box-boundary grid
eliminates ordering, duplicate boxes and differences in subdivision.
330 records still differ in exact occupied volume. For example, the
collision shape for `create:andesite_tunnel[axis=x,shape=CLOSED]` differs
at a floating-point boundary. This establishes a failed exact-equivalence
test. The experiment did not measure whether those boundary differences
are perceptible during play.

The performance ABBA was gated on identical shape dumps and did not run.
The diagnostic launches were excluded from the timing sample. There is
no CPU, critical-path or time-to-menu improvement established here.

**Reopen** with a mechanism that preserves the existing shape output, or
an explicit pack decision to accept the observed changes with a validated
collision/occlusion tolerance contract. For either, rerun the differential
and the warm-AOT ABBA on a fixed pack before claiming a saving.
