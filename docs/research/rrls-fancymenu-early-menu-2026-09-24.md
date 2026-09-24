# RRLS 5.0.11 and the FancyMenu title boundary — 2026-09-24

Status: **RESEARCH / do not ship RRLS's early-menu path with this pack**. This is a fast-PC visual report plus log/source attribution, not a controlled RRLS performance A/B or a fix validation. No laptop was used.

## Reproduction and evidence

The user launched `C:\Users\Wachii\AppData\Roaming\.minecraft_welite_beta_nooptim` on the fast PC with `rrls-5.0.11+mc1.21.1-forge.jar`, the controlled `FancyMenu 3.9.0-wedit` JAR, no BootOptim JAR, and `config/rrls.toml` containing `hideOverlays = "ALL"`, `miniRender = true`, `type = "PROGRESS"`, `reInitScreen = true`, `skipForgeOverlay = false`. The user saw a broken first title screen which repaired after background loading, no RRLS progress overlay, and a several-second freeze near the end of a manual resource-pack reload. The source is RRLS tag `1.21.1-5.0.11` at <https://github.com/dima-dencep/rrls/tree/1.21.1-5.0.11>; the local FancyMenu fork source is `C:\Users\Wachii\OneDrive\Documentos\.Minecraft modding\FancyMenu`.

The 2026-09-24 `latest.log` gives this order (all local wall-clock timestamps):

| Event | Timestamp |
| --- | --- |
| RRLS creates an early resource manager | 02:04:48.187 |
| RRLS quick-reloads Language, Splash, Font and GUI sprites | 02:04:48.206–02:04:49.432 |
| FancyMenu registers its normal reload listener | 02:04:50.596 |
| Minecraft begins the full resource reload | 02:04:52.190 |
| FancyMenu starts title preload | 02:05:36.788 |
| Initial filter keeps one panorama, skips 21 backgrounds | 02:05:36.894 |
| FancyMenu marks reload finished; selected title layout subsequently applies | 02:05:37.039–02:05:37.044 |
| Manual reload starts | 02:05:46.454 |
| FancyMenu begins manual preload on the Render thread | 02:06:09.745 |
| Manual preload prelaunches 20 panoramas, 2 slideshows, 130 suppliers | 02:06:15.320 |
| FancyMenu marks manual reload finished | 02:06:15.793 |

The manual FancyMenu preload spans **6.048 s** on the Render thread, including a **5.575 s** interval to the prelaunch marker. It is a strong explanation for the reported final freeze, but no frame trace was captured, so it is not a measured freeze duration. Total manual reload to FancyMenu-finished is **29.339 s**; do not conflate it with the final freeze. The initial one-layout filter is intentionally restricted to the first reload in `MixinMinecraft.java`; later reloads use `ResourcePreLoader.preLoadAll(120000)` with the complete list. This matches the 20/2/130 marker. The user-owned FancyMenu fork is the appropriate place to change this policy, with layout-selection and invalidation guarantees; a BootOptim-side early return from `preLoadAll` was already rejected in PR #116, and cooperative wait was rejected in #117.

RRLS's `MinecraftClientMixin` suppresses the constructor's loading message and can call `onResourceLoadFinished` early under the hidden-initial state. Its `ReloadableResourceManagerMixin` constructs a provisional manager and quick-reloads only a subset of UI listeners. FancyMenu registers after those quick reloads and finishes its listener only in the full reload. Therefore the visible menu-before-FancyMenu-finished interval is structurally possible and agrees with the user's visual report. The log does not contain a reliable first-visible-frame marker, so it does not establish the exact duration of the broken title. The RRLS source does not expose an atomic old-generation/new-generation commit for arbitrary mod listeners. A compatible design would keep the initial loading screen until required title consumers, including FancyMenu's selected layout and its resources, complete; manual reload could render the existing generation only if every active renderer has a safe lifetime and the new generation is committed in order on the render thread. This is a new architecture, not a safe configuration of the current quick-reload hook.

The absence of a mini progress bar is **unresolved from logs**. With `miniRender = true` and `type = "PROGRESS"`, RRLS's `GameRendererMixin` intends to call `overlay.rrls$miniRender` only when the overlay has the HIDE rendering state. Its NeoForge overlay mixin has a separate early-display path and can choose normal superclass rendering or change state later. No `Failed to draw overlay!` error appears, but there is no per-frame state trace. The config value `hideOverlays = "ALL"` selects hiding on initial load as well as reload; it does not by itself prove that the mini bar rendered or failed.

## Error triage

The current run has 376 `PathPackResources: Invalid path ''` lines and 13 EMF model-repeat errors. A prior same-instance run without RRLS (`2026-09-24-3.log.gz`) already has 128 invalid-path lines and 17 EMF model-repeat errors. The same Create movement and Voxy config errors also occur in both. These messages cannot be attributed to RRLS solely from this run. RRLS may add an early pack-open pass and thus amplify some lines, but attribution would require a controlled same-state comparison or stack trace. No RRLS fatal exception or resource-pack reset was found in `latest.log`.

## Decision and next gate

Do not port RRLS's current `hideOverlays=ALL` early-menu behavior into BootOptim. A narrowly safer RRLS configuration for a future local check is `hideOverlays=RELOADING`, preserving the initial loading barrier, but its manual reload behavior and FancyMenu freeze still require visual validation; it is not a production solution. The controlled FancyMenu fork could instead extend its provenance-aware selected-layout preload to manual reloads while preserving the currently displayed generation until ordered commit. Before implementation, audit how `ResourceHandlers.reloadAll()` invalidates active textures and how FancyMenu reselects the current random layout, then validate initial title, changed resource-pack selection, manual reload, and representative world/menu use. No laptop run is justified until a fast-PC visual pass succeeds.
