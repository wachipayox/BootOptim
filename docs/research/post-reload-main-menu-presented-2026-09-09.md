# Resource reload allDone -> actually presented startup UI — 2026-09-09

Status: **NO-GO FOR UNATTENDED NAVIGABLE MAIN MENU / COARSE FIRST-VISIBLE-UI PROFILE VALIDATED**

Authority refreshed before work: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Diagnostic PR: #219, intentionally stacked on #214 (`0448e62bbf08c048249376fcb0d8718ef736501b`) and its structured-trace bridge stack. This is diagnostic only; no production optimization is proposed.

## Scope and decision

This lane covers only the first startup reload's stock `allDone` completion through a screen that has completed a normal render frame and the following stock `Window.updateDisplay()` call. It does not cover ModelManager or the ~10.131 s post-ModelManager/pre-`allDone` tail.

The requested product endpoint was a visually usable/navigable main menu. Under the pinned hosted exact-pack startup state, that endpoint does **not** exist without user interaction: AnalogAudio 0.1.0 synchronously replaces the first `TitleScreen` during `ScreenEvent.Init.Post` with `LavaplayerWelcomeScreen` when AnalogPlayer is missing and its welcome prompt is enabled. The modal disables all three buttons for 40 screen ticks, disables Escape, and then requires an explicit user choice before the saved TitleScreen can be restored.

Therefore the behavior-preserving hosted endpoint is named `startup_ui_presented`, not `main_menu_presented`. It measures the first actually presented active startup screen and records its concrete class. The final profile proves that class is `com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen`.

**Decision: close `allDone -> navigable main menu` as NO-GO under the current exact-pack state.** Do not auto-decline the modal, disable the prompt, seed/install AnalogPlayer, delay/cancel the replacement, or call the modal a main menu. No laptop run or optimization follows from this lane.

## Prior opening-only evidence

PR #214 exact-pack profile run `34290311738` recorded:

- `resource_reload` stock `allDone`: `mono_ns=78059969616`;
- `ScreenEvent.Opening(TitleScreen)`: `mono_ns=79159255459`;
- `allDone -> title opening`: **1099.286 ms**.

That marker was never a usable/presented endpoint. Earlier PR #122 independently found zero TitleScreen-render coverage using both `ScreenEvent.Render.Post` and direct mapped `TitleScreen.render(...) @ RETURN`; PR #128 then identified the AnalogAudio replacement lifecycle and the correct real-screen endpoint.

## Safe diagnostic boundary

For `-Dboot_optim.bootTrace.endpoint=startup_ui_presented` only:

1. `ScreenEvent.Opening(TitleScreen)` records `title_open` and defers the benchmark's synthetic stop.
2. Subsequent screen openings record `startup_screen_replacement_open` with ordinal and concrete class.
3. `ScreenEvent.Init.Post` records the actual screen identity.
4. `RenderFrameEvent.Post` records `startup_ui_render_return` for the currently active screen and stores that class in a thread-local token.
5. A GAME-layer Mixin at the existing `Minecraft.runTick(boolean)` callsite observes immediately after stock `Window.updateDisplay()` returns. It consumes only the token produced on that same thread and records `startup_ui_presented`.
6. Only after this endpoint does the benchmark-only `exitOnTitle` stop the client.

The hook does not target `Window` itself, call GLFW/RenderSystem, wrap or replace the present call, touch executors/futures, change listener order, move GL/native work, or alter gameplay. Mixin remains fail-open (`required=false`, `defaultRequire=0`). FancyMenu panorama preload and MCEF first-consumer behavior are untouched.

## Final hosted exact-pack profile

Validated runtime/doc head: `06a55cba8cddc8091dfa8f28d32caf2a95c609f9`.
Exact-pack workflow: **34295487796 / run #770**, success. Build `34295472448` and normal Startup Benchmark `34295472436` also succeeded.

Profile contract passed:

- measurement origin `hosted_exact_pack`;
- endpoint `startup_ui_presented`;
- exact resource selection valid, one reload;
- blocks atlas `8192x8192x2`;
- `bootoptim_mixin_errors=0`;
- one contiguous JSONL event sequence `0..58`;
- trace summary: `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, `error=0`;
- all relevant callbacks emitted on `Render thread`, thread id 65.

Relevant structured events:

```text
resource_reload allDone             92,359,587,277 ns
TitleScreen opening                 93,483,300,092 ns
title Init.Post                     93,518,230,042 ns
LavaplayerWelcomeScreen opening     94,523,365,740 ns
welcome first frame render return   95,069,897,345 ns
welcome first display return        95,199,408,838 ns
```

Serial partitions:

- `allDone -> TitleScreen opening`: **1123.713 ms**;
- `TitleScreen opening -> title Init.Post`: **34.930 ms**;
- `title Init.Post -> LavaplayerWelcomeScreen opening`: **1005.136 ms**;
- `welcome opening -> first completed frame`: **546.532 ms**;
- `first completed frame -> updateDisplay return`: **129.511 ms**;
- `allDone -> first actually presented startup UI`: **2839.822 ms**;
- `TitleScreen opening -> first actually presented startup UI`: **1716.109 ms**.

The `allDone -> opening` result reproduces #214's 1099.286 ms boundary closely (+24.427 ms in this single diagnostic smoke). This is evidence that the original ~1.1 s opening tail was real, while also showing that opening was not the visible endpoint.

## Coarse ownership classification

### `allDone -> TitleScreen opening` — 1123.713 ms

A stock post-reload/client transition bucket on the Render thread. It is not ModelManager time: ModelManager completed earlier and is outside this lane. No subtraction-based attribution is made.

### `TitleScreen opening -> title Init.Post` — 34.930 ms

TitleScreen/NeoForge/FancyMenu initialization-entry work. The hosted log registers FancyMenu's `title_screen` customization layer immediately after the legacy opening marker.

### `title Init.Post -> welcome opening` — 1005.136 ms

A Render-thread screen-init callback tail ending at AnalogAudio's synchronous replacement. Source proves AnalogAudio performs the replacement, but the whole 1.005 s is **not** attributed to AnalogAudio: its missing-AnalogPlayer gate is only filesystem existence/version checks plus welcome-screen construction. The hosted log also places Palladium cache reloads and Iris `Creating pipeline for dimension minecraft:overworld` inside this coarse interval. Without start/return boundaries for those callbacks, ownership cannot be split further safely.

### `welcome opening -> first completed frame` — 546.532 ms

GUI/FancyMenu/Realms/replacement-screen initialization plus first-frame work on the Render thread. The trace contains nested `RealmsNotificationsScreen` and repeated welcome-screen Init.Post observations. This remains an inclusive lifecycle bucket, not exclusive FancyMenu self-time.

### `first completed frame -> updateDisplay return` — 129.511 ms

Post-render/display-return bucket. It is the only bounded interval touching presentation, but hosted Linux/Xvfb/llvmpipe cannot tell whether any part is GPU, driver, native swap/event polling, scheduler delay, or Java work. Do not name it by subtraction.

### MCEF

MCEF is **not** the owner of this hosted tail. The run logs `BOOTOPTIM_MCEF_FIRST_CONSUMER status=deferred` before the reload and no `status=initializing` / successful CEF initialization marker before `startup_ui_presented`. Existing MCEF first-consumer behavior is left unchanged.

## Source-level AnalogAudio endpoint cause

Public AnalogAudio 0.1.0 source at commit `22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3` matches the runtime class names. `AnalogAudioClientEvents.onScreenInit(ScreenEvent.Init.Post)` runs at `EventPriority.LOW` and, when the initialized screen is a TitleScreen, the prompt is enabled, and `LavaplayerLoader.isMissing()` is true, calls:

```text
Minecraft.setScreen(new LavaplayerWelcomeScreen(current TitleScreen))
```

`LavaplayerLoader.isInstalled()` checks for `.analogaudio/internal/analogplayer-1.0.2.jar`; the heavier download/classloader/Lavaplayer paths occur only after explicit user action or later use.

`LavaplayerWelcomeScreen` stores the previous TitleScreen, initializes three buttons disabled for 40 ticks, returns `false` from `shouldCloseOnEsc()`, and restores the previous screen only when the user chooses the decline/back path.

Thus there is no behavior-preserving unattended timestamp for the requested navigable TitleScreen in this fixture.

## Physical limitations and reopening gate

Hosted Linux/Xvfb/llvmpipe validates Java lifecycle ordering, exact screen identity, render-thread ownership, and the software-present return boundary. It cannot validate:

- Windows GPU/driver/display-present latency;
- Microsoft Basic Render Driver behavior;
- Windows CEF/JCEF/native media timing;
- OpenAL behavior;
- HDD/page-cache effects;
- physical visual correctness or input responsiveness.

A physical comparison additionally requires startup-state equivalence: record whether `.analogaudio/internal/analogplayer-1.0.2.jar` exists, whether `lavaplayerWelcomeScreen` is enabled, and whether the modal has already been crossed in that process. If those differ, `allDone -> navigable TitleScreen` is a different workload.

Reopen this lane only if the exact workload is intentionally defined so a navigable main menu exists without synthetic interaction and the same hosted first-present boundary passes in that state. Until then, no physical profile and no optimization are justified.
