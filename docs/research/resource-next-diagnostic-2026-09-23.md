# Physical constructor and first-frame follow-up — 2026-09-23

Status: **PREPARED, NOT LAUNCHED**. The user explicitly requested preparation
without launching on the laptop. This branch remains diagnostic-only.

Authority: `agent/integration-current` refreshed at `b3f0c5f`. The prior
physical run `resource-split-20260922b` had 142.333 s initial reload,
ModelBakery constructor 46.135 s, four known disjoint child spans leaving
13.549 s, and 19.104 s from TitleScreen opening to the next display update.
Its total startup validation failed a ~5.5 s wall-clock/uptime inconsistency;
these are same-JVM monotonic phase intervals, not a control/candidate speedup.

## Why these boundaries

Prior PR #221 measured the constructor's vanilla item loop, missing model,
special item models, FFAPI additions and NeoForge additional models on hosted
hardware. Its valid run found the vanilla/special work tiny and the remaining
lane largely aligned with CIT's two constructor injections. PR #257 later
closed simple CIT lifecycle caching/scheduling as a safe optimization premise.
The physical 13.549 s is therefore a reason to check whether that ownership
still holds on the slower machine, not permission to repeat the rejected cache.

The 19.104 s physical title-to-display interval is much larger than #219's
hosted `allDone -> first UI` 2.840 s, and is sensitive to this Windows laptop.
PR #219 showed that AnalogAudio's welcome modal may replace TitleScreen, so
the first display is an observable startup UI boundary, not proof of a
navigable menu. PR #117 rejected a FancyMenu wait rewrite because it regressed
the full critical path; this diagnostic does not reopen that mechanism.

## Probe contract

All probes require `-Dboot_optim.profileStartupVariance=true`; disabled runs
follow stock calls. Three new ModelBakery constructor intervals each record
wall, JVM process CPU, current-thread CPU, GC and memory snapshots:

1. `bakery_post_blockstates_to_items`: stock blockstate registration returns
   through the first `ProfilerFiller.popPush("items")` return. This includes
   `getModelGroups` and any injected CIT item-model work before that call;
   it does not assign the whole interval to CIT.
2. `bakery_vanilla_item_loop`: first popPush return to the second
   `popPush("special")` entry. It measures the existing loop as one interval;
   there is no per-item timer.
3. `bakery_additional_models_loop`: NeoForge's registration event returns to
   stock parent-resolution `Collection.forEach` entry.

The original calls execute once in original order. If any call fails, an
unmatched scope invalidates the attribution rather than recording a false
successful duration. In the current mapped 1.21.1 bytecode, constructor
`popPush` has exactly two callsites, and the three first-frame targets occur
once in `Minecraft.runTick` (`GameRenderer.render`, `RenderTarget.blitToScreen`,
`Window.updateDisplay`); these targets were confirmed with `javap -private -c`.

The first hosted smoke exposed that TitleScreen may open *inside*
`GameRenderer.render`; a render-entry probe armed after opening therefore
missed that frame even though the menu was reached. The corrected probe
always records `title_first_frame_render_return` after opening. It records a
full `title_first_frame_render` scope only when opening preceded render entry;
otherwise that scope is intentionally absent. The disjoint
`title_first_frame_blit` and `title_first_frame_display_update` calls are
measured on the actual first frame. The existing `main_menu_presented` marker
still fires after stock display update; a `startup_presented_screen` point
records the screen class then. Opening to render entry and gaps between calls
are calculated from monotonic marker timestamps; do not label wall-minus-CPU
as I/O, GPU or driver without a separate boundary. A modal screen remains
possible, and `getClass().getName()` is identity only, not visual validation.

The `resource_next` parser profile requires all prior seven split scopes,
the three new constructor pairs, blit/display pairs, render-return point and
screen identity. The full render scope is optional because opening may occur
after render entry. Missing required hooks or unmatched
scopes invalidate this profile. It retains the 5 s clock consistency check;
if the laptop repeats the clock discrepancy, total startup stays invalid while
same-process monotonic attribution can still be reported separately.

## Preparation and handoff

Local Gradle `build --offline` passed and 34 Python tests passed. The first
hosted exact-pack smoke reached the menu with 14 packs, 8192x8192x2 atlas and
zero Mixin errors, but proved the initial render-entry probe missed an opening
inside `GameRenderer.render`. The corrected hosted smoke
[run 35835262819](https://github.com/wachipayox/BootOptim/actions/runs/35835262819)
passed. Its artifact `exact-pack-result-smoke-1` contains the three new
constructor pairs, `title_first_frame_render_return`, blit and display-update
pairs, and screen identity. Offline `resource_next` validation on unique early
markers plus latest.log passed with 25 balanced scopes, 72 successful listener
rows, no warnings, one initial reload, first probe uptime 1.766 s and matching
clock origin. The hosted first screen is
`com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen`. The hosted
constructor gaps measured 1.523 s, 0.100 s and 0.006 s; blit 0.004 s and
display update 0.088 s. These Linux/Oracle 25 values validate hook behavior,
not Windows laptop performance or visual/menu usability.
The packaged
standalone wrapper is built from `bootstrap/build/libs/`, not root `build/libs`.
Corrected runtime source commit `2bd70eca1c702a16a9617cceae5d9903b38a6a89`.
Local packaged artifact:
`C:/BootOptimBench/artifacts/resource-next-20260923/bootoptim-resource-next-20260923.jar`,
SHA-256 `53b774844e4dccb8ce28a6d1f4acac52e63646b78b2fd4bde509c21c9432d5dd`.
Attempted remote transfer on 2026-09-23 failed at SSH connection timeout to
`192.168.1.218:22`; no files reached the laptop and no transaction was staged.
The previous run's verified restored state had config SHA-256
`a9744bf7a4c5660f6b58a6fe1fe9309a91ecacc2ac2189bf099dd2a145aa1ecc`
and original wrapper SHA-256
`379bc509efd43a0d2edf7cfb6bc1e2f99dd1601b3d8e3ab43b00c70f6dea4989`.
Recheck both during future Preflight; these historical hashes are not a fresh
remote status.

No remote transaction
Stage or Run action is authorized by this preparation request; Prism instance
configuration and original BootOptim JAR must stay untouched until a later
explicit launch instruction.

For a later launch, repeat remote Preflight immediately before Stage; preserve
the existing Prism instance's original flags, Oracle Java 21 / 6144 MiB,
selected 14 packs, and `exitOnTitle=true`. Stage via the reviewed transaction,
then dispatch Run and end the turn immediately without polling. After the user
reports exit, collect both early and game logs, finished state and options,
run `variance_probe.py --profile resource_next`, then Postflight restores exact
original config/JAR bytes. Only one initial resource reload is in this run;
generation 2+ changed-pack behavior remains a separate coverage gap.
