# FancyMenu usable-menu / resource-preload boundary — 2026-09-05

Status: **REJECTED as a BootOptim runtime defer candidate**

Base: `agent/integration-current` @ `d29a6bad6358c7ff78dadbc5e85bd753c0ad2a54`

This note answers a narrow question: can BootOptim let FancyMenu's title screen become usable before `ResourcePreLoader.preLoadAll` has completed, while leaving the optional visual work to complete later through FancyMenu's stock lifecycle, without cancelling the global reload or weakening gameplay/compatibility semantics?

The answer is **not with the phase API exposed by exact FancyMenu 3.9.0**. Exact bytecode does prove that several visual consumers tolerate resources that are not ready yet, but the preloader itself does not expose a safe hand-off/continuation boundary. A narrow BootOptim mixin that simply returns early, skips waits, or runs `preLoadAll` later would change resource-start ordering, timeout/error timing, thread affinity, or shared resource-handler state.

No runtime candidate is implemented and no laptop run is requested.

## Exact binary and fixture authority

The exact FancyMenu JAR was recovered from the still-live public artifact produced by PR #89's hosted static audit (`fancymenu-mcef-static-audit`, artifact `9887316618`, workflow run `33739976202`). Its SHA-256 is:

```text
8e1c68f2c91aed02057209252bbe221bf3b019c4e82fb20fe35809bac2c08db8
```

This matches PR #109's independently recorded exact-fixture hash and embeds FancyMenu 3.9.0. The audit artifact also contains the exact fixture's `fancymenu_data/customization` files and `javap` output.

Relevant hosted runtime evidence remains:

- #109 run `33975186210`: `wait_calls=132`, `ordinary_calls=2`, `slideshow_calls=10`, `panorama_calls=120`; preload ~4451 ms wall / ~3114 ms current-thread CPU. Panorama waits were ~3507/2277 ms wall/CPU, slideshow waits ~810/799 ms.
- #113 run `33976030376`: preload ~4016 ms; `after_preload_to_title` ~119.5 ms on Linux/llvmpipe. This proves the hosted tail after preload is tiny, but not Windows/Microsoft Basic Render Driver visual equivalence.
- Physical full-pack runs 017–020 remain the hardware-sensitive observation: `preLoadAll` 24.349–44.337 s, total title 371.601–422.797 s, and FancyMenu FINISHED→title 3.5–20.2 s. These values are not treated as recoverable savings without a safe critical-path mechanism.

## Exact call graph

FancyMenu's resource listener and title customization are separate pieces, but initial customization is gated by completion of the reload cycle:

```text
FancyMenu reload listener (MixinMinecraft$1 / SimplePreparableReloadListener)
  apply(...)
    -> ResourceHandlers.reloadAll()
    -> ResourcePreLoader.preLoadAll(120000L)
       -> getRegisteredResourceSources(...)
       -> for each source, in order:
          -> CubicPanoramaSource -> preLoadCubicPanorama(...)
          -> SlideshowSource     -> preLoadSlideshow(...)
          -> ordinary source     -> ResourceHandler.get(source)
          -> Resource.waitForLoadingCompletedOrFailed(timeout)
          -> inspect/log loadingFailed or timeout

reload FINISHED
  -> ScreenCustomizationLayerHandler clears resourceReload
  -> ScreenCustomization marks menu as new
  -> ScreenCustomizationLayer can customize title screen
     -> discover existing screen widgets
     -> construct layout element instances
     -> bind vanilla-widget elements/buttons
     -> render menu backgrounds as a separate background path
```

`ScreenCustomizationLayer` construction itself initializes collections/layout state; it does not synchronously read the panorama/slideshow/video pixels needed for the background. `onInitOrResizeScreenPost` discovers the screen widgets and constructs the element instances regardless of background image readiness.

The exact title customization files independently contain vanilla-button bindings such as `mc_titlescreen_singleplayer_button`, `mc_titlescreen_multiplayer_button`, `mc_titlescreen_options_button` and `mc_titlescreen_quit_button`. The exact random background layouts are universal-layout entries, separate from those button bindings.

## Consumer behavior when the resource is not ready

### Panorama

`LocalTexturePanoramaRenderer.prepare()` constructs six existing image `ResourceSupplier`s; it does not require their decoded pixels to construct the menu layer.

For local PNG files, exact `PngTexture.of(InputStream, ...)` creates a background thread which performs `NativeImage.read`. `PngTexture.getResourceLocation()` is the later consumer boundary: only after a `NativeImage` exists does it create/register a `DynamicTexture` with Minecraft's `TextureManager`. Therefore preload's wait covers file/decode completion, not the eventual texture registration/upload.

`LocalTexturePanoramaRenderer` calls each supplier at render time. If a texture location is not available, the renderer substitutes FancyMenu's missing-texture location for that face. It does not wait for decode completion in the render consumer.

Conclusion: an incomplete panorama does not intrinsically prevent widget construction, but its temporary visual fallback is **not equivalent** to the intended background.

### Slideshow

`ExternalTextureSlideshowRenderer.prepareSlideshow()` synchronously enumerates the slideshow directory and creates image suppliers. It does wait up to 5 s for the first image to establish its dimensions, then marks the renderer `prepared`; this is distinct from requiring every slideshow image to be ready.

During rendering, current/previous/overlay suppliers are queried. If `getResourceLocation()` returns `null`, that image is simply not blitted. `SlideshowMenuBackground` calls `prepareSlideshow()` when the slideshow object is not prepared.

Conclusion: later slideshow images tolerate not-ready state, but a proposed defer must also preserve the existing first-image dimension/setup behavior. Returning from the global preload listener early does not by itself provide that guarantee.

### Native video

`NativeVideoMenuBackground.render()` first draws its background area black, then resolves the video supplier. If no video resource exists yet it returns after the black fill. If the video resource exists but no frame location is available, it also leaves the black/fallback path rather than blocking screen construction.

Exact `Mp4Video.getResourceLocation()` returns a fully-transparent texture while playback has not reached a presentable frame. Player creation is performed on the Minecraft thread when already on that thread, otherwise queued back to it. When a frame texture exists, registration with `TextureManager` occurs on the consumer path. Native-video element rendering has the same fundamental non-blocking resource-location contract.

The exact fixture also has a random MCEF-video universal background (`bg_arbol_carton.txt`). That is a separate MCEF path and must not be conflated with FancyMenu's `Mp4Video`/NativeVideo implementation.

Conclusion: native video has a usable not-ready fallback at render time, but deferring its stock preload lifecycle to an arbitrary worker would violate its thread-affinity assumptions.

### Audio

Title-screen open audio explicitly supports not-ready state. In `ScreenCustomizationLayer.onInitOrResizeScreenPost`, FancyMenu obtains the audio resource; when it is ready it plays it, otherwise it schedules a 100 ms retry and continues constructing/discovering the screen elements.

This does **not** make audio resource creation worker-safe. Exact `OggAudio.of(InputStream, ...)` calls `RenderSystem.assertOnRenderThread()` when it must allocate its own OpenAL clip/source, then starts a background thread for the remaining loading/decoding path. `isReady()` additionally requires a valid OpenAL source.

Conclusion: audio readiness is not a control-construction gate, but the stock creation path has render-thread/OpenAL affinity that rules out a generic delayed-worker `preLoadAll` continuation.

## Why `ResourcePreLoader` has no safe BootOptim phase boundary

The consumer-side fallback behavior is real, but the producer lifecycle is monolithic:

1. `preLoadAll` interleaves **resource initiation, waiting, timeout handling and failure logging** in one ordered loop. It exposes no future, token, continuation, or `launchAll` / `validateAll` pair.
2. Returning before the wait loop finishes leaves later registered resources neither started nor stock-validated.
3. Replacing the waits with zero/no-wait causes the outer loop to start later panoramas/slideshows/ordinary resources immediately, changing concurrency and scheduling. That is not a placeholder-only change and would reopen the already rejected broader overlap direction from #83.
4. Running stock `preLoadAll` later on the render/client thread would block that thread for the same waits and make an apparently reached menu freeze again; that does not satisfy "visually usable menu".
5. Running stock `preLoadAll` later on a worker is not equivalent. `ResourceHandler` stores cached resources and failed sources in ordinary mutable collections (including a `HashMap`) shared with `ResourceSupplier.get()` consumers, and resource families such as OGG require render-thread/OpenAL work at creation time. This introduces races and affinity violations absent from stock.
6. Letting deferred preload continue after the user enters another screen/world would move startup resource CPU/IO into interactive/gameplay time, contrary to BootOptim's no-gameplay-regression contract.

A correct implementation therefore needs a **different FancyMenu architecture**: an explicit two-phase preloader/state machine that launches only operations whose creation affinity is satisfied, records the original ordered deadline/error state, allows consumers to see a stable not-ready resource, and later completes the exact stock validation/cleanup semantics without concurrent unsynchronized handler mutation. That is substantially more than a narrow BootOptim mixin.

## Equivalence contract for any future reopening

A future implementation is eligible only if all of these are proven:

- the global `SimpleReloadInstance` still completes normally; no listener is cancelled or marked complete artificially;
- title-screen widget discovery and vanilla-button bindings are identical, including Singleplayer, Multiplayer, Options, Quit and resource-selection/navigation behavior;
- enabled resource packs and exact external ZIP selection are unchanged;
- the selected random layout/background identity is unchanged;
- every resource intentionally released before first title frame has a defined fallback that cannot cover/remove controls;
- each deferred resource later reaches the same terminal success/failure state, preserves timeout/error reporting semantics, and is closed/replaced on the same lifecycle events;
- decoded image/video/audio work does not create new unbounded fan-out or overlap into gameplay;
- texture registration/upload, player creation and OpenAL-affine creation remain on their required owning thread;
- incompatibility/version mismatch fails open to the complete stock `preLoadAll` path before behavior changes;
- a kill switch restores the full stock path for the launch.

Hosted exact-pack validation must first prove the full button set is present/clickable, resource-pack selection remains exact, the intended background eventually replaces its fallback, and no preload resource is lost. Linux/llvmpipe can reject a broken architecture but cannot certify Windows `Microsoft Basic Render Driver` visual equivalence; only after hosted passes would a physical visual gate be justified.

## Decision

**REJECT / NO RUNTIME EXPERIMENT IN BOOTOPTIM.**

There is a real semantic distinction between "menu controls constructed" and "background resource fully decoded/ready" in FancyMenu 3.9.0. However, there is no safe stock phase boundary at `ResourcePreLoader.preLoadAll` that BootOptim can exploit while preserving the preload listener's ordered start/wait/timeout/error lifecycle.

Do not:

- short-circuit `preLoadAll` or replace its waits merely to reach the title marker earlier;
- replay `preLoadAll` on a worker after title;
- widen panorama prelaunch beyond the retained six-face production mechanism solely to approximate a two-phase preload;
- infer savings from the physical 24–44 s preload interval without moving the actual usable-menu critical path.

Keep the existing production six-face panorama overlap unchanged. It deliberately starts only FancyMenu's already-async PNG suppliers while leaving FancyMenu's original wait/error/timeout loop and GPU consumer path intact.

## Low-risk reopening path

Reopen only if FancyMenu itself (or an explicitly user-maintained editable fork) gains a first-class two-phase preload API/state machine with the invariants above. That would be a materially different premise from #83 and from the rejected renderer-listener cancellation in #95/#102. The first gate would then be hosted exact-pack semantic/visual validation; Windows hardware would follow only if that non-destructive boundary passes hosted.

No hardware-gated BootOptim bypass is implemented here because hardware identity does not repair the lifecycle/thread-safety problem.

## Evidence

- PR #38 — initial FancyMenu 3.9.0 source audit
- PR #39 / merged promotion #54 — retained six-face panorama supplier overlap
- PR #83 — rejected inter-panorama rolling window
- PR #89 — exact fixture FancyMenu/JCEF static audit and public JAR artifact
- PR #95 / #102 — rejected renderer listener defer after black/frozen menu on physical hardware
- PR #109 — exact-bytecode busy-wait CPU diagnostic; hosted run `33975186210`
- PR #111 — full-resource physical variance evidence
- PR #113 — preload/process snapshots; hosted run `33976030376`
- `docs/research/exact-pack-ci.md` — hosted-vs-hardware interpretation contract
