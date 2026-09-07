# FancyMenu initial-selection coherence — 2026-09-07

Status: **ACTIVE FIX / PC beta validation pending**. This is a direct change in
the user-owned FancyMenu fork, not a BootOptim mixin.

## Confirmed symptom

The Welite beta log showed the first reload selecting and preloading the
`portas_mid` panorama, then the title-screen layer registering and requesting
`anyisus_proyect_night`. The latter was therefore loaded on demand and the
menu briefly showed the missing panorama texture. BootOptim's panorama mixin
was present, but it only observes/starts suppliers; it does not choose the
FancyMenu layout.

## Root cause

The initial-layout preload plan chose a random layout before the first title
screen existed and committed its pick. When FancyMenu later constructed the
live title layer, `RandomLayoutContainer.getRandomLayout()` saw
`ScreenCustomization.isNewMenu() == true` (set when the initial resource
reload finished) and intentionally discarded the cached random pick for
ordinary random groups. The live layer consequently made a second random
choice. The preloaded source and the displayed source could differ even though
the source filter itself was correct.

## Fix

The fork now records the exact random-group picks made by the initial preload
plan using the stable random-group identifier and layout filename. During the
first `title_screen` layer construction only, a matching layout is consumed
before the normal random-selection path runs. This remains valid even if
runtime layout IDs were regenerated between planning and construction. If the
layout disappears or changes, the marker is removed and normal fail-open
selection continues. Other screens, later reloads, and ordinary random-menu
semantics are unchanged.

The next PC run confirmed this part of the fix: the log recorded both
`Initial-layout selection group '0' -> 'bg_inv_noche'` and
`Initial-layout selection applied group '0' -> 'bg_inv_noche'`. The remaining
missing-texture frame therefore came from `PngTexture`'s lazy
`DynamicTexture`/`TextureManager.register` step, not from selecting the wrong
panorama.

The fork source change is accompanied by `docs/initial-layout-preload.md` and
was compiled with `:neoforge:build`. The follow-up log proved that selection
coherence alone was not enough: the selected `invernadero_noche` panorama was
decoded during preload but its `DynamicTexture` was still registered lazily on
the first render. The fork now completes `ITexture.getResourceLocation()` for
selected panorama/slideshow resources on the render thread after decoding;
non-selected/full-list callers retain lazy registration.

The resulting JAR is staged in the local beta profile as a PC visual
validation candidate; the previous active JAR is preserved with a
`.before-gpu-registration-20260907` suffix.

The GPU-registration candidate is `FancyMenu` fork commit `d56549682` and was
compiled with `:neoforge:build`. It calls `getResourceLocation()` only for
selected initial-layout panorama/slideshow textures and only when the reload
callback is on the render thread. Full-list/other callers and incompatible
backends fail open to the previous lazy path.

## Follow-up finding — immediate title-screen rebuild

The next PC beta log still registered `nether_shader` on demand about 1.3 s
after the title layer was registered, despite logging the coherent initial
choice `bg_david_house`. The pack has an `update_screen` path that can rebuild
the title screen immediately after initialization (MCEF/placeholder state can
change during that window). The first-choice marker was consumed correctly,
but its live random-group cache was not explicitly refreshed at that point;
the rebuild could therefore perform a fresh random pick and choose a different
unpreloaded panorama.

The follow-up fork change writes the selected layout's current runtime
identifier into `RandomLayoutContainer.CACHED_PICKS` when the initial marker is
consumed. This keeps immediate same-screen rebuilds on the already decoded and
registered panorama without changing later-screen random semantics. It is
staged in the PC beta as the next validation candidate; the prior active JAR is
preserved with a `.before-cache-persist-20260907` suffix.

## Validation of the cache-only follow-up

The next PC run used the exact active JAR for fork commit `03fd534b2` (SHA-256
`47C89189F7F312704E7220CC150152A7C2B12F0C88FD7DA00A411AD0206D76A1`) and
still showed the defect. The log coherently selected and applied
`bg_alvarin_house_2`, but about 1.3 seconds later registered
`alvaro_zone_night`. Therefore the ordinary runtime-id cache is not sufficient
to survive the title-screen rebuild in this pack; the rebuild is entering a
fresh selection path despite the explicit cache write.

The new fork commit `aba93bf5b` keeps the stable initial layout-name marker
authoritative for all same-title-screen rebuilds until the next resource
reload. It is staged as the active PC beta JAR with SHA-256
`12C42F3AF62265DE5BEFB8E688B6CC668A8365F004BC4D22C664ED24D4E5BB97`; exactly
one active FancyMenu JAR is present. The previous candidate is preserved as
`.before-persistent-title-lock-20260907`.

## Gate

Run the PC beta with exactly one FancyMenu JAR and the full configured
`preload_resources` list. The log must show the same selected panorama in the
initial preload marker and in the first title-screen registration, plus the
new selected-texture registration path, with no missing-texture frame. A
failure should reopen the texture lifecycle analysis rather than broadening
the preload to every configured panorama.
