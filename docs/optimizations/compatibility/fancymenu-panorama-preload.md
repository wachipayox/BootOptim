# FancyMenu panorama preload overlap

## Scope

Optional startup optimization for FancyMenu's cubic panorama resource preloader. Validated against FancyMenu 3.9.0 in the reference pack.

Kill switch: `-Dboot_optim.fancymenuParallelPanoramaPreload=false`

The mixin is `@Pseudo` and every injection uses `require = 0`; FancyMenu is not a hard runtime dependency.

## Bottleneck

FancyMenu local PNG resources already decode asynchronously, but `ResourcePreLoader.preLoadCubicPanorama` requests one panorama face and waits for it before requesting the next. For six-face panoramas this serializes operations that FancyMenu's own resource layer already supports asynchronously.

## Mechanism

At method entry BootOptim resolves the existing panorama and calls `get()` on its existing image suppliers so their async decode begins early. FancyMenu then runs its original loop unchanged, preserving its normal get/wait/timeout/error ordering. GPU registration/upload remains lazy and on the original thread.

Compatibility access is reflective so a changed FancyMenu API fails open: BootOptim logs once, disables this optimization for the launch, and leaves FancyMenu stock.

## Safety invariants

- FancyMenu absent: no target, no behavior change.
- Reflection/API mismatch: disable and continue stock.
- Existing `ResourceSupplier` instances are reused; no replacement resource lifecycle is invented.
- FancyMenu retains completion, timeout and error semantics.
- OpenGL/GPU work is not moved to background threads.

## Measured evidence

Original exact-pack validation:

- panoramas: 20
- suppliers prelaunched: 120
- failures: 0
- previous synchronous FancyMenu preload: about 8.313 s
- optimized preload: 2.570 s
- reduction: about 5.743 s / 69.1%
- menu/panoramas manually validated visually

Final promotion smoke on PR #50, 2026-08-31:

- `panoramas=20`
- `suppliers_prelaunched=120`
- `failures=0`
- `preload_ms=2391.146`
- panorama/menu behavior manually confirmed correct by the reference-pack user

This is a high-value scheduling optimization and is intentionally retained by the project.
