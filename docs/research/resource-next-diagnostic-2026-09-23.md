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

On the first frame after TitleScreen opening, three disjoint call intervals
measure `title_first_frame_render`, `title_first_frame_blit` and
`title_first_frame_display_update`. The existing `main_menu_presented` marker
still fires after stock display update; a `startup_presented_screen` point
records the screen class then. Opening to render entry and gaps between calls
are calculated from monotonic marker timestamps; do not label wall-minus-CPU
as I/O, GPU or driver without a separate boundary. A modal screen remains
possible, and `getClass().getName()` is identity only, not visual validation.

The `resource_next` parser profile requires all prior seven split scopes,
these six new interval pairs, and screen identity. Missing hooks or unmatched
scopes invalidate this profile. It retains the 5 s clock consistency check;
if the laptop repeats the clock discrepancy, total startup stays invalid while
same-process monotonic attribution can still be reported separately.

## Preparation and handoff

Local Gradle `build --offline` passed and 34 Python tests passed. The packaged
standalone wrapper is built from `bootstrap/build/libs/`, not root `build/libs`.
Artifact SHA-256, source commit, remote staging paths and final preflight
result will be filled below after the files are copied. No remote transaction
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
