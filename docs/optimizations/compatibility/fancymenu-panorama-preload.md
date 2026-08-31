# FancyMenu panorama preload overlap

## Scope

Optional compatibility optimization for FancyMenu's cubic panorama resource preloader. Validated against FancyMenu `3.9.0-wedit` / the 3.9.0 resource implementation used by the reference pack.

Kill switch: `-Dboot_optim.fancymenuParallelPanoramaPreload=false`

The mixin is `@Pseudo` and `require = 0`, so FancyMenu is not a runtime dependency of BootOptim.

## Bottleneck

FancyMenu local PNG resources already decode asynchronously. The expensive behavior was in `ResourcePreLoader.preLoadCubicPanorama`: it requested one panorama image, waited for that resource to finish, and only then requested the next image.

For a six-face panorama this serializes six operations that are already designed to run asynchronously. In the reference pack, 20 panoramas produced a long synchronous Render-thread tail during initial resource loading.

## Mechanism

At the start of FancyMenu's existing `preLoadCubicPanorama` method, BootOptim resolves the panorama and calls `get()` on its existing image suppliers before FancyMenu enters its original loop.

That only starts the resources earlier. FancyMenu still executes its own original code afterwards, including:

- its own `get()` calls;
- completion waiting;
- timeout handling;
- error handling;
- original ordering of final checks.

Texture registration/GPU upload is not moved off the render thread. The optimization overlaps CPU/file PNG decoding; it does not parallelize OpenGL work.

Reflection is used intentionally so BootOptim does not link against FancyMenu classes. If the expected 3.9.x API shape is not present, the optimization disables itself for the launch and FancyMenu continues normally.

## Safety invariants

- No FancyMenu installed => no target, no behavior change.
- Compatibility reflection failure => log once, disable the optimization, continue FancyMenu stock.
- Existing `ResourceSupplier` objects are reused; BootOptim does not create replacement resources.
- The original preloader still owns completion, timeout and failure semantics.
- GPU texture registration is not moved to a background thread.

## Resource trade-offs

More panorama PNGs may decode concurrently, so there is a temporary increase in CPU concurrency and decoded-image memory versus FancyMenu's serial waiting. The number of resources and their final lifetime are unchanged. The optimization does not create an additional executor.

## Measured evidence

Exact reference-pack warm validation:

- panoramas: `20`
- suppliers prelaunched: `120`
- prelaunch failures: `0`
- previous FancyMenu synchronous preload: about `8,313 ms`
- optimized preload: `2,569.644 ms`
- synchronous Render-thread reduction: about `5,743 ms` / `69.1%`

The same run reached the main menu at `72.721 s` with BootOptim's mod entrypoint at `29.294 s`. Compared phase-to-phase with nearby experimental runs, post-entrypoint startup improved by multiple seconds.

Visual validation: the reference pack's menu and panoramas were manually checked after the optimized launch and were reported correct.

## Expected results

Packs with many local FancyMenu panoramas/slideshow images can see a large reduction in the synchronous FancyMenu preload tail. Packs with few images, already-cached/trivial images, web-only resources, or different FancyMenu preload code may see little/no benefit; incompatible API shapes fall back automatically.
