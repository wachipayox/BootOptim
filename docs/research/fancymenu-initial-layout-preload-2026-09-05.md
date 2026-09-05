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
development profile, not the full exact-pack fixture, and no laptop run has
been performed. The local final-profile JAR was replaced for later testing;
the previous JAR is preserved beside it with the suffix
`.pre-active-layout.bak`.

## Decision and next gate

Keep this as an **ACTIVE** candidate. Do not promote it to BootOptim yet and
do not claim a time-to-main-menu gain from the source smoke alone. The next
gate is a paired exact-pack run with the fork JAR and the option enabled,
followed by the physical laptop comparison when the user signals that the
laptop may be started. Record total time-to-main-menu, reload-to-FancyMenu,
panorama/slideshow preload duration, selected layout identity and any first-use
visual hitch. If the candidate produces no coherent wall-time improvement or
changes the selected layout, revert to the saved JAR and disable the option.
