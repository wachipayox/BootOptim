# FancyMenu rolling panorama window experiment — 2026-09-02

Status: **REJECTED**

Experiment PR: #83

Production FancyMenu panorama optimization remains retained. This document records only the rejected follow-up that attempted to overlap work **between** panoramas.

## Existing production behavior

BootOptim already prelaunches the six existing image suppliers when FancyMenu enters `ResourcePreLoader.preLoadCubicPanorama`. FancyMenu then executes its original ordered `get()` / `waitForLoadingCompletedOrFailed()` / timeout / error path unchanged.

That production optimization was previously validated in the exact reference pack at roughly 8.31 s -> 2.57 s for the synchronous panorama preload and is not under reconsideration here.

## New premise

Exact FancyMenu 3.9.x source shows that `ResourcePreLoader.preLoadAll` still iterates its registered resource sources sequentially. A cubic panorama is fully waited before the outer loop reaches the next source.

Relevant source properties:

- `ResourceSupplier#get()` reuses `current` while the source is unchanged, so launch-only repeated `get()` calls are effectively idempotent for the same supplier.
- local PNG loading ultimately uses FancyMenu's existing asynchronous `NativeImage.read` path.
- GPU texture registration remains lazy in `PngTexture#getResourceLocation()` and was not moved to a worker.
- `Resource.waitForLoadingCompletedOrFailed()` busy-spins while waiting, so wider decode concurrency also competes with the waiting render thread for CPU.

The hypothesis was therefore that a **bounded** look-ahead could hide part of the next panorama's disk/decode latency without the unsafe all-120 supplier fan-out previously considered.

## Candidate

PR #83 added an experiment-only JVM property:

```text
-Dboot_optim.experimentFancyMenuPanoramaWindow=2
```

Control:

```text
-Dboot_optim.experimentFancyMenuPanoramaWindow=1
```

`window=1` reproduces current production behavior: only the current panorama's six suppliers are prelaunched.

`window=2` additionally prelaunches the immediately following panorama only when both panoramas are contiguous in FancyMenu's actual ordered preload-source list. It does not jump over slideshows, ordinary resources, audio, video, text, or unknown entries. Values other than 2 fall back to 1, so the experiment cannot accidentally become a wider fan-out.

FancyMenu retains original consumption order, timeout/error handling, and GPU-thread behavior. Plan/ahead-launch failures fall back to production window=1 behavior.

## Hosted exact-pack result

Hosted exact-pack A/B workflow #19 ran three fresh VMs for each variant on head:

`2a8869e6b7de9c8f37ceb0b7c83dc3c8315c1f3a`

Build #1051 and Startup #304 were green.

All three candidate runs mechanically proved the intended path:

- `window=2`
- `plan_valid=true`
- `planned_sources=24`
- `planned_panoramas=20`
- `contiguous_pairs=18`
- `ahead_panoramas=18`
- `panoramas=20`
- `suppliers_prelaunched=120`
- `failures=0`
- `plan_mismatches=0`
- BootOptim Mixin errors: 0

Candidate panorama-preload samples:

- 3851.574 ms
- 3797.217 ms
- 3810.386 ms

Median: **3810.386 ms**.

Control window=1 panorama-preload samples:

- 4605.331 ms
- 2424.716 ms
- 3937.680 ms

Median: **3937.680 ms**.

The control's second run was an unusually fast hosted run overall (`main_menu=54.560 s`) and is strong evidence that hosted startup variance is material. Accordingly the end-to-end delta is not interpreted as a deterministic regression.

Median candidate minus control deltas reported by the exact-pack aggregator:

- panorama preload: **-127.294 ms / -3.23%**
- reload -> FancyMenu FINISHED: **-22 ms / -0.05%**
- post-mod-entrypoint: **+320 ms / +0.52%**
- main menu: **+1766 ms / +1.94%**
- MCEF: **-500 ms / -26.62%**; MCEF is a noise/control marker in this hosted surrogate, not target work

The key result is the target interval, not the noisy total: despite 18 real next-panorama launches, `reload -> FancyMenu FINISHED` was effectively unchanged and panorama preload improved only about 0.127 s at the median.

## Decision

**REJECTED / NO-GO.**

Do not request a laptop run for this mechanism. Do not continue mechanically to window=3 or an all-panorama/all-120 supplier prelaunch. The bounded window=2 experiment already demonstrated that additional inter-panorama overlap has very little leverage in the hosted exact software pack while adding more concurrent decode pressure.

Production's existing six-face-per-panorama overlap is unaffected and remains intentionally retained.

## Reopening criteria

Reopen this direction only if a material premise changes, for example:

- a future FancyMenu version replaces the per-image/per-panorama waiting architecture and exposes a bounded shared executor or explicit preload-future API;
- new exact-hardware evidence shows multi-second panorama residual wall with proof that the delay is specifically inter-panorama idle time rather than physical reads/decode/CPU contention;
- the user's custom FancyMenu fork is modified directly to remove busy-spin waiting or provide bounded scheduling semantics, creating a substantially different implementation premise.

Do **not** reopen merely because FancyMenu appears again as a multi-second listener in a heavily instrumented run.

## Evidence

- PR #38 — source inspection of FancyMenu resource preload behavior
- PR #39 — validated six-face overlap experiment
- PR #54 — production promotion of the retained FancyMenu optimization
- PR #75 — resource reload apply-tail attribution and residual inter-panorama hypothesis
- PR #83 — bounded window=2 experiment and hosted exact-pack A/B result
- exact-pack workflow run #19 / PR #83 comment `5517152246`
