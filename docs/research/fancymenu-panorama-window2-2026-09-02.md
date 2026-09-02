# FancyMenu bounded panorama rolling-window experiment — 2026-09-02

Status: **EXPERIMENT / DO NOT MERGE WITHOUT EXACT-PACK A/B**

Base integration: `dc728fc3391ab6a1ca3872a7e2effa00d8f488d1`.

## What this does not repeat

Production BootOptim already pre-launches the six existing async PNG suppliers for the **current** cubic panorama before FancyMenu executes its original ordered waits. That optimization is retained and previously reduced the reference fast-PC preload from roughly 8.31 s to 2.57 s.

This experiment does **not** replace that mechanism and does not launch all 120 observed suppliers at once.

Control:

```text
-Dboot_optim.experimentFancyMenuPanoramaWindow=1
```

Candidate:

```text
-Dboot_optim.experimentFancyMenuPanoramaWindow=2
```

Any value other than `2` deliberately becomes window `1`; this branch cannot accidentally turn into an unbounded all-panorama fan-out.

## Source-level premise

PR #38 inspected the exact FancyMenu 3.9.0 NeoForge source artifact for Minecraft 1.21.1. The relevant structure is:

```text
ResourcePreLoader.preLoadAll
  -> getRegisteredResourceSources(null)
  -> ordered for(ResourceSource source)
     -> CubicPanoramaSource: preLoadCubicPanorama(...)
        -> for panorama.panoramaImageSuppliers
           -> supplier.get()
           -> resource.waitForLoadingCompletedOrFailed(...)
```

Thus production BootOptim overlaps the six faces inside one panorama, but FancyMenu does not advance the outer source loop until the current panorama's ordered waits complete.

`ResourceSupplier#get()` retains its current `Resource` while the resolved source remains unchanged. Calling `get()` early therefore starts/reuses the same resource object rather than constructing a second independent decode for the later stock call.

For local PNGs, `PngTexture.local -> PngTexture.of` starts a thread whose worker performs `NativeImage.read`. Dynamic texture registration remains lazy in `PngTexture.getResourceLocation()`, outside preload. The experiment does not move GL/TextureManager work to background threads.

## Bounded mechanism

At `preLoadAll` entry, only when window `2` is requested, BootOptim uses FancyMenu's public `getRegisteredResourceSources(null)` to build an ordered lightweight plan of cubic-panorama entries. No resource is opened by plan construction.

At each stock `preLoadCubicPanorama` entry:

- window `1`: run the already-validated production prelaunch for the current panorama only;
- window `2`: prelaunch the current panorama plus at most the immediately following panorama **only when both belong to the same contiguous run of cubic-panorama sources**.

The contiguous-run rule is deliberate. The experiment never jumps over a slideshow, ordinary image, audio, video, text, or unknown preload entry in order to start a later panorama. This limits the scheduling change to the exact residual being tested.

With six faces per panorama, the intended maximum is roughly 12 face decodes in flight at the first boundary, not 120. Once FancyMenu finishes waiting for panorama N, production semantics guarantee N's six faces are complete before the outer loop advances; the rolling window then starts at most one new panorama.

Each planned panorama occurrence is prelaunched at most once by BootOptim. FancyMenu itself still performs every original `get()`, wait, timeout, failure check and log in original order.

## Fail-open behavior

The existing production compatibility failure path remains unchanged for the current panorama: if the known FancyMenu API cannot be reflected, BootOptim disables panorama prelaunch for the launch and FancyMenu proceeds stock.

Rolling-only failures are narrower:

- plan build mismatch/failure -> stop rolling and fall back to production window `1` behavior;
- ahead-panorama prelaunch failure -> stop rolling, but do **not** disable the existing production prelaunch for later current panoramas;
- no hard FancyMenu dependency is introduced; the mixin stays `@Pseudo` / `require=0`.

The final marker extends the existing line with:

```text
window=
plan_valid=
planned_sources=
planned_panoramas=
contiguous_pairs=
ahead_panoramas=
plan_mismatches=
```

Existing `preload_ms` remains the same outer `preLoadAll` wall marker.

## Why only window 2 first

The old laptop has four logical CPUs and the historical JFR showed storage contention. FancyMenu's local PNG implementation creates one Java thread per decode. An all-20-panorama or all-120-supplier launch can therefore create severe CPU/I/O contention and memory pressure, especially because some panorama faces are multi-megabyte images.

Window `2` is the smallest architectural change that can hide the outer panorama-to-panorama serialization while bounding concurrency. If it does not improve critical wall, there is no justification to test window `3` merely because more concurrency is possible.

## Validation

1. Build CI and vanilla Startup CI must stay green.
2. Exact-pack candidate must report window `2`, a valid plan, 20 actual panoramas, 120 total prelaunched face suppliers, zero failures/mismatches, and non-zero ahead-panorama count if the exact preload list contains contiguous panoramas.
3. Control must report window `1` and preserve current production behavior.
4. Hosted exact-pack A/B: three fresh VMs per variant. Judge `main_menu`, post-entrypoint, reload -> FancyMenu finish, and `BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD preload_ms`; MCEF is a noise marker.
5. If hosted results are negative/tied, reject without laptop reruns.
6. Only a coherent hosted win justifies a final laptop A/B because the real mechanism is CPU/storage scheduling and the laptop remains the hardware authority.
7. Visual/menu validation remains required before production promotion even if timings win.

## Stop conditions

Reject window `2` if:

- the plan does not match exact-pack source order;
- failures/mismatches occur;
- preload improves locally but `main_menu`/reload critical wall does not;
- unrelated preload families regress from cross-entry contention;
- or hosted results are tied/negative.

Do not infer recoverable wall from task sums and do not merge this experiment merely because it launches more work earlier.
