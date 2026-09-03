# MCEF first-consumer defer — 2026-09-03

Status: **VALIDATED / PROMOTION CANDIDATE**

## Premise

PR #78 tried overlapping real CEF initialization with resource reload and was rejected: on the laptop CEF expanded from roughly 15.752 s to 45.941 s and post-entrypoint TTMM regressed by about 10.18 s. The validated premise here is different: keep MCEF's native preparation lifecycle, but suppress the automatic pre-title `MCEF.initialize()` call and run the real initializer only when an actual CEF consumer first needs it.

The implementation never fakes MCEF initialized state, never manually dispatches MCEF init hooks, and never moves JCEF startup to a worker. Initialization remains the real `MCEF.initialize()` on Minecraft's client/render thread.

## Performance evidence

PR #88 hosted exact-pack 3x3 A/B on SHA `5a714c77e24675ded66eb79b155a9cab9c60fb15`:

- control median main menu: **93.026 s**;
- candidate median main menu: **91.032 s**;
- delta: **-1.994 s / -2.14%**;
- all three candidate runs reached the main-menu marker with real MCEF state still `initialized=false` and no pre-title first consumer.

After production hardening, including concurrent first-consumer serialization, the user requested two independent repeat campaigns on the exact same PR #90 runtime head `d45df21b8ad820fae1f898bdaf6220c6adc6b9a7` to distinguish signal from hosted noise.

### Production-head confirmation campaign #1

Fresh-VM exact-pack 3x3:

- candidate main-menu median: **92.831 s**;
- control main-menu median: **93.882 s**;
- delta: **-1.051 s / -1.12%**;
- post-entrypoint delta: **-753 ms / -1.21%**;
- reload -> FancyMenu delta: **-36 ms / -0.08%**;
- panorama delta: **+285.4 ms / +7.08%**;
- control MCEF median: **1.433 s**;
- candidate MCEF: not initialized before title;
- BootOptim Mixin errors: **0** in both variants.

The positive TTMM result therefore did not depend on a faster FancyMenu panorama path; panorama actually moved against the candidate in this campaign.

### Production-head confirmation campaign #2

Independent fresh-VM exact-pack 3x3 on the same SHA:

- candidate main-menu median: **90.109 s**;
- control main-menu median: **92.311 s**;
- delta: **-2.202 s / -2.39%**;
- post-entrypoint delta: **-1.935 s / -3.16%**;
- reload -> FancyMenu delta: **-1.375 s / -3.22%**;
- panorama delta: **-22.5 ms / -0.54%**, effectively tied;
- control MCEF median: **0.944 s**;
- candidate MCEF: not initialized before title;
- BootOptim Mixin errors: **0** in both variants.

Both requested current-head campaigns therefore reproduce the favorable TTMM direction. The exact magnitude remains variable on hosted runners, so the project should retain the conservative claim: first-consumer defer removes unnecessary pre-title CEF work and has repeatedly improved TTMM in the hosted exact-pack surrogate; it is not valid to attribute every second of the observed end-to-end delta directly to CEF.

This is hosted software-pack evidence, not laptop-hardware equivalence. It establishes a coherent TTMM win and confirms that the normal exact-pack title path does not require CEF before BootOptim's main-menu boundary.

## FancyMenu compatibility hardening

FancyMenu `3.9.0-wedit` keeps its own `MCEFUtil.MCEF_initialized` bridge and some video/browser paths return before reaching `MCEF.getClient()` while that flag is false. Direct MCEF API guards alone were therefore insufficient.

The exact-pack fixture contains 22 enabled random `bg_*.txt` layouts, including `bg_arbol_carton.txt` with a valid `video_mcef` background. Dynamic attribution showed those `bg_*` files were not active in the hosted title screen: the actual active title layout was `Elmejormenu_prueba.txt` and initially had no menu background. This explained several invalid forced-video smokes; changing `bg_arbol_carton`'s identifier did not make it an active layout.

The decisive diagnostic attached the exact `menu_background` block from `bg_arbol_carton.txt` to the proven-active `Elmejormenu_prueba.txt` only in the ephemeral hosted fixture. `MCEF Active Title Video Smoke #1` then passed the real render path:

- MCEF defer armed on the Render thread;
- two automatic initialize calls suppressed;
- main menu reached after about **42.894 s** of deferral with MCEF still false;
- first real video render at about **44.112 s** of deferral emitted `consumer=fancymenu_video_render`;
- real CEF initialization completed successfully in about **1,645.708 ms**;
- FancyMenu then extracted its video-player web resources and logged `MCEFVideoManager successfully initialized!`;
- no BootOptim Mixin errors or FancyMenu MCEF video/player failure markers occurred.

That run used exact #88 code, not the temporary active-layout probe.

## WebDisplaysFork 2.5.0 compatibility

The exact fork source is commit `df820aa136b64368a043c2c1e1ef2d0292d233eb` in `brother-bill/webdisplays-mc`; its metadata declares WebDisplays `2.5.0-1.21.1` and MCEF `2.1.6-1.21.1`.

Its lifecycle is compatible with first-consumer defer:

- `SharedProxy.init()` registers `MCEF.scheduleForInit(cef -> onCefInit())`;
- real browser creation goes through `WDBrowser.createBrowser()`, which uses `MCEF.getClient()`;
- `ClientProxy.onCefInit()` installs WebDisplays' scheme/display/router integration after real MCEF initialization.

Therefore BootOptim does not need to fake WebDisplays readiness or dispatch its callback itself. A first WebDisplays browser reaches a guarded MCEF API, forces the real initializer, and MCEF remains responsible for its normal registered callbacks.

## Production invariants

The promotion candidate is default-on only for exact MCEF `2.1.6-1.21.1` and exposes kill switch:

```text
-Dboot_optim.mcefFirstConsumerDefer=false
```

Optional targets use `@Pseudo` / `require=0`; version mismatch, absent MCEF, signature drift, or a non-client-thread automatic initializer all fall back to stock MCEF behavior. FancyMenu high-level guards cover its early bridge short-circuits, while direct MCEF guards cover `getApp`, `getClient`, and both known `createBrowser` overloads.

## Remaining boundary

Hosted validation proves startup/title behavior and a real FancyMenu MCEF-video first consumer. A physical final check is still useful for Windows-native CEF timing/visual output and an in-world WebDisplays browser, but repetitive laptop A/B is not required to establish the mechanism.

Relevant PRs: #78 rejected overlap experiment, #88 first-consumer experiment, #89 FancyMenu/static/dynamic compatibility audit, #90 production promotion and repeated current-head confirmation.
