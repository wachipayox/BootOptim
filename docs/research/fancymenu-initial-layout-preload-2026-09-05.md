# FancyMenu initial-layout resource preload — 2026-09-05

Status: **ACTIVE** candidate in the user's `FancyMenu 3.9.0-wedit` fork. This
is not yet a BootOptim production optimization and is not listed in
`docs/optimizations/`.

## Target and observed configuration

The user's final local profile is
`C:\Users\Wachii\AppData\Roaming\.minecraft_welite_beta`. Its
`config/fancymenu/options.txt` requests 24 resources:

- 20 cubic panoramas;
- 2 slideshows;
- 2 ordinary local image resources.

The enabled `Elmejormenu_prueba.txt` title layout has no menu background of
its own. The background is supplied by 22 enabled universal `bg_*.txt`
layouts in random group `0`, with `randomonlyfirsttime = true`. FancyMenu's
preloader receives a flat list and eagerly waits for every configured
panorama/slideshow before the screen layer has selected the one universal
layout that will actually be shown.

Changing only `preload_resources` could reduce the work, but would either fix
the menu to one background or defer an unselected random background until a
later first use. Neither preserves the current menu behaviour. The existing
BootOptim six-face panorama overlap remains compatible with this direction;
selected panoramas still use the existing supplier prelaunch.

## Fork change

The source is in the user-owned repository
`C:\Users\Wachii\OneDrive\Documentos\.Minecraft modding\FancyMenu`, branch
`codex/active-layout-preload`:

- `0bb403aff` — adds the opt-in implementation;
- `c3f79f7e4` — documents the option in the FancyMenu fork.

The new loading option is:

```text
B:preload_only_initial_layout_resources = 'true';
```

It defaults to `false` for all other profiles. When enabled, only the first
Minecraft resource reload builds a staged initial title-screen plan:

1. select the same eligible normal/random layouts as the screen layer;
2. stage random picks in a copy of FancyMenu's pick cache;
3. extract only visible panorama/slideshow sources from those selected layouts;
4. preload those sources while retaining every ordinary configured resource;
5. commit the staged picks only after the plan is accepted.

Any layout/requirement/source-analysis failure returns `null`, which preserves
FancyMenu's complete original preload list. Later reloads also retain the
original complete-list behaviour. This keeps the selected layout and the
preloaded resource identical without moving rendering or GPU work off-thread.

## Validation so far

The fork passed both `:common:build` and `:neoforge:build`. A client smoke run
using the fork's development profile reached resource-reload completion and
the title screen without an exception, fatal error, or FancyMenu/Mixin failure.
The log reported:

```text
[FANCYMENU] Initial-layout preload filter kept 1 background sources and skipped 21 configured background sources
```

This is a mechanism/safety smoke, not an end-to-end timing result: it used the
development profile, not the full exact-pack fixture. The local final-profile
JAR was replaced for later testing; the previous JAR is preserved beside it
with the suffix `.pre-active-layout.bak`.

## Physical laptop gate (2026-09-05)

The candidate was exercised on the exact-pack laptop profile through Prism,
using candidate SHA-256
`B418F49257CE8BCCBB9C010C13147DB0656FF540FCFA81A8AF59408DEBC79390`.

| run | `preload_resources` state | option | total to menu | preload → reload finished | filter result | disposition |
| --- | --- | --- | ---: | ---: | --- | --- |
| active-layout-001 | full list (22 background + 2 ordinary sources) | true | 373.017 s | 1.005 s | kept 1, skipped 21 | mechanism-valid candidate run |
| control-001 | list was not held identical after staging | false | 449.962 s | 26.852 s | no filter | **invalid A/B** |
| active-layout-002 | empty list | true | 393.516 s | 0.251 s | kept 1, skipped 0 | **invalid for the intended pack** |
| active-layout-003 | full list (1163-character serialized value) | true | 1272.745 s | 3.301 s | kept 1, skipped 21 | mechanism-valid; timing contaminated |
| control-002 | full list at launch | false | 388.441 s | 46.555 s | no filter | clean control |
| active-layout-004 | original and candidate JAR both present | intended true | 505.130 s | 0.326 s | no filter | **invalid: original JAR won** |
| active-layout-005 | empty list after control-002 | true | 357.966 s | 2.416 s | no filter | **invalid for the intended pack** |
| active-layout-006 | full list, candidate JAR only | true | 335.574 s | 3.731 s | kept 1, skipped 21 | clean candidate |

Run 003 started after Prism/JVM preparation had left a Java process alive for
about 16 minutes before the BootOptim report began, so its total startup time
cannot be compared with the earlier runs. It nevertheless proves two useful
properties on the slow hardware: the full configured list is read without
being erased, and the selected-layout filter executes through resource reload
to the main menu. The options file still contained the complete serialized
list after the process exited. The candidate JAR was then moved to the remote
preservation directory, the original JAR was restored with its known SHA-256
`8E1C68F2C91AED02057209252BBE221BF3B019C4E82FB20FE35809BAC2C08DB8`, and the
option was reset to `false`.

The 449.962 s control must not be used as evidence of a regression: the
source-list state was not held constant. The empty-list candidate is likewise
not a pack test. No paired, clean candidate/control A/B exists yet, and the
candidate is therefore not promoted.

The later clean control/candidate pair makes the mechanism materially more
credible. Control 002 reached `main_menu` at 388.441 s (`mod_entrypoint`
128.860 s); the isolated full-list candidate 006 reached it at 335.574 s
(`mod_entrypoint` 122.774 s). The candidate therefore saved 52.867 s overall,
with 46.781 s of the difference after `mod_entrypoint`. The reload interval
from the FancyMenu preload marker to `Minecraft resource reload: FINISHED` was
46.555 s for the control and 3.731 s for the candidate. This is still one
control/candidate pair on a noisy machine, but the effect is much larger than
the observed within-phase noise and the filter marker proves that the intended
full list was active.

Two staging mistakes were also found and recorded here because they can
otherwise masquerade as variance: run 004 left both FancyMenu JARs in `mods`
and loaded the original, while run 005 followed a control that had already
reset the serialized list to empty. The runner must enforce exactly one
FancyMenu JAR and restore the full serialized line immediately before every
candidate/control launch.

## Decision and next gate

Keep this as an **ACTIVE** candidate. Do not promote it to BootOptim yet and
do not claim a time-to-main-menu gain from these unpaired laptop runs. The next
gate is a clean paired exact-pack run with the same full serialized list for
control and candidate, preferably using a detached runner that records the
launcher/JVM preparation separately. Record total time-to-main-menu,
reload-to-FancyMenu, panorama/slideshow preload duration, selected layout
identity and any first-use visual hitch. If the candidate produces no coherent
wall-time improvement or changes the selected layout, revert to the saved JAR
and disable the option.
