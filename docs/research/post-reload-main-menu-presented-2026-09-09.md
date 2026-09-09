# Resource reload allDone -> first presented main menu — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / NO OPTIMIZATION**

Authority refreshed before work: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Diagnostic branch is intentionally stacked on PR #214 (`0448e62bbf08c048249376fcb0d8718ef736501b`), transitively on the structured-trace bridge stack. It must not be merged as production instrumentation.

## Scope

This lane covers only the first startup reload's stock `allDone` completion through a title screen that has actually completed rendering and returned from the following `Window.updateDisplay()` presentation call. It does not cover ModelManager or the ~10.131 s post-ModelManager/pre-`allDone` tail.

The endpoint is `main_menu_presented`, not the legacy `ScreenEvent.Opening(TitleScreen)` marker. No timing in this document is an optimization claim.

## Prior hosted evidence

PR #214 exact-pack profile run `34290311738` used measurement origin `hosted_exact_pack` and endpoint `main_menu`. Its structured trace recorded:

- `resource_reload` stock `allDone`: `mono_ns=78059969616`;
- legacy `main_menu` (`ScreenEvent.Opening(TitleScreen)`): `mono_ns=79159255459`;
- opening-only tail: **1099.286 ms**.

That run cannot establish a visually usable endpoint because exact-pack benchmark mode stopped from the opening callback. Its `latest.log` additionally shows FancyMenu's `Minecraft resource reload: FINISHED` at 23:25:24.407 and BootOptim's opening marker at 23:25:25.472. `ScreenCustomizationLayer registered: title_screen` follows at 23:25:25.474. This temporal proximity is not causal attribution.

The same run reported `mcef_init_ms=null` and logged BootOptim's retained MCEF first-consumer state as `status=deferred` before the initial reload. Therefore there is no evidence that CEF initialization owns the hosted opening tail. If title rendering becomes the first MCEF consumer, existing MCEF/FancyMenu logs can correlate that with the new render/present boundaries without adding a native hook.

## Diagnostic design

For `-Dboot_optim.bootTrace.endpoint=main_menu_presented` only:

1. `ScreenEvent.Opening(TitleScreen)` records `title_open` but does not stop the exact-pack benchmark.
2. `ScreenEvent.Init.Post(TitleScreen)` records `title_init_post`, after NeoForge/FancyMenu screen initialization callbacks have run.
3. `ScreenEvent.Render.Post(TitleScreen)` records `title_render_return` and sets a thread-local presentation token.
4. A Mixin at `RETURN` of stock `Window.updateDisplay()` consumes that token on the same calling thread and records `main_menu_presented`.
5. Only after that return does benchmark `exitOnTitle` stop the client.

The presentation hook does not call GLFW or RenderSystem, wrap `Window.updateDisplay`, intercept `RenderSystem.flipFrame`, move GL work, wait on a future, replace an executor, or reschedule any callback. The thread-local token prevents a render on one thread from being paired with a presentation return on another.

The legacy startup report/console `main_menu` marker remains at screen opening for compatibility, but it is not the structured-trace endpoint in this diagnostic mode.

## Interpretation contract

Hosted Linux/Xvfb/llvmpipe is a software-pack semantic gate only for this lane. The useful serial partitions are:

- `allDone -> title_open`: post-reload screen-selection/construction entry;
- `title_open -> title_init_post`: TitleScreen/NeoForge/FancyMenu initialization callbacks;
- `title_init_post -> title_render_return`: first-frame preparation/render path visible through the screen lifecycle;
- `title_render_return -> main_menu_presented`: display/present/event-poll return bucket.

Do not label a wall interval GPU, native, disk, page-cache or Java CPU by subtraction. A material physical tail would still need hardware-side CPU/native/presentation evidence before attribution.

## Physical limitations

A hosted profile cannot validate Windows driver latency, physical GPU behavior, OpenAL timing, Windows JCEF/native media timing, HDD/page-cache effects, or visual correctness on the historical laptop. No physical run is requested by this diagnostic. A future physical diagnostic is justified only if the presented endpoint is first validated in hosted exact-pack and a reproducible physical tail remains material under the same resource selection and endpoint.

## Hosted gate

Required profile contract:

- exact pinned pack / fresh hosted VM;
- origin `hosted_exact_pack`, endpoint `main_menu_presented`;
- one initial reload, exact resource selection, blocks atlas `8192x8192x2`, zero BootOptim Mixin failures;
- one contiguous bootstrap-owned JSONL sequence, zero dropped events;
- monotonic `allDone <= title_open <= title_init_post <= title_render_return <= main_menu_presented`;
- `title_render_return` and `main_menu_presented` must be emitted by the same thread;
- no MCEF/FancyMenu/presentation ownership claim unless an explicit existing marker falls inside the corresponding bounded interval.

## Decision state

No optimization is proposed. The branch exists only to replace the opening-only endpoint with a first-present boundary and classify the hosted tail coarsely. The final disposition depends on the hosted profile gate above.
