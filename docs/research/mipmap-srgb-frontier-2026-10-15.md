# Mipmap sRGB conversion (`linearToSrgb`) frontier — 2026-10-15

Status: **REJECTED as a BootOptim candidate (no memo, no table, no runtime change)**. Evidence and reopening criteria below. Baseline context: [beta-rev3-baseline-2026-10-15](beta-rev3-baseline-2026-10-15.md), hot spot 4.

## Premise

The rev 3 JFR run (hosted, attribution only) put 67 self samples (about 0.67 s sampled CPU, not wall) in Sodium's `ColorSRGB.linearToSrgb`. The reload worker `Worker-ResourceReload-2` carried 62 of them. The sampled caller chain goes through vanilla `MipmapGenerator.averageRgb` (64 samples). The question: can a memo or exact table serve those conversions with bit-identical output and a measurable wall gain?

## (a) Exact equivalence

Source read: `CaffeineMC/sodium`, `common/src/main/java/net/caffeinemc/mods/sodium/client/util/color/ColorSRGB.java` (the `main` branch, not the pinned `0.8.12-beta.1+mc1.21.1` tag; the tag was not fetched).

- The private `linearToSrgb(float c)` is a pure function of the float bits: it clamps to `[2^-13, 1.0 - ε]`, reads one 104-entry `int` table indexed by `(bits - MIN_BITS) >> 20`, and computes `(bias + scale * t) >>> 16` with `t = (bits >>> 12) & 0xff`. It has no state, no I/O, no allocation.
- So a memo keyed by the input bits is exact by construction. A 2^20-entry table over the same index is exact too.
- The public 4-argument overload packs three of these with alpha. It also has no state.

Equivalence therefore holds; it was not tested over the pack's averaging inputs, because the test is unnecessary for a memo that cannot be cheaper than the function (below).

## (b) Ceiling

- The function is already a table lookup plus two integer operations on a 104-entry table. A memo adds a hash probe or an array read with its own cache miss. There is no evidence that the probe is cheaper than the arithmetic.
- A 2^20-entry direct table (about 1 M bytes of `byte`, or 4 MB of `int`) is no better: it trades arithmetic for memory traffic on the same inputs.
- The 0.67 s of sampled CPU is the cost of the call count (averaging touches every texel of every mip level of the block atlas), not of an expensive conversion. Removing the conversion would remove a fraction of that; a memo would at best move it.
- Critical path: not shown. The atlas completion is not proven to gate `main_menu` in this pack. Even a perfect removal would only bound the reload-worker CPU.

Verdict: a memo or table is **not** a candidate. The exact output is guaranteed, but the expected cost is equal to or higher than the current arithmetic.

## Not verified

- Which vanilla or NeoForge class calls the 4-argument path that the JFR frames show. The frames name `MipmapGenerator.averageRgb`, but I did not open the 1.21.1 binary or decompile it. The rejection does not depend on that link.
- The pinned Sodium tag. The source above is the `main` branch.

## Reopening criteria

- A measurement showing `linearToSrgb` is memory-bound (cache misses, not instructions) on the reload workers, with a call count that a cache can actually reduce.
- A change to the averaging algorithm itself in the mipmap path, with a proven equivalence argument for the output. This is a vanilla algorithm change, not a wrapper: it needs its own bounded investigation, and it is outside BootOptim's current model.
- A critical-path proof that the block-atlas completion gates `main_menu` on this pack, with a reload-worker wall ceiling above the measurement noise.

## Related

- Mixin / ModLauncher pipeline: closed in the [research index](README.md); unrelated to this frontier.
- The FancyMenu busy wait (hot spot 1 in the baseline) is a separate, already-rejected premise: [fancymenu-cooperative-wait-2026-09-05](../archive/research/fancymenu-cooperative-wait-2026-09-05.md).
