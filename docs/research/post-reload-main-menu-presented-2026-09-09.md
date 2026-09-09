# Resource reload allDone -> actually presented startup UI — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / EXPECTED NO-GO FOR AN UNATTENDED NAVIGABLE TITLE MENU**

Authority refreshed before work: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Diagnostic branch is intentionally stacked on PR #214 (`0448e62bbf08c048249376fcb0d8718ef736501b`), transitively on the structured-trace bridge stack. It is not a production optimization.

## Scope

This lane covers only the first startup reload's stock `allDone` completion through a screen that has actually completed a normal render frame and the following stock `Window.updateDisplay()` call. It does not cover ModelManager or the ~10.131 s post-ModelManager/pre-`allDone` tail.

The requested product endpoint is a visually usable/navigable main menu. The exact hosted pack has an important lifecycle complication: the first `TitleScreen` opening attempt is synchronously replaced during `ScreenEvent.Init.Post` by AnalogAudio's `LavaplayerWelcomeScreen` when AnalogPlayer is missing. That replacement is a blocking setup modal, not the navigable Minecraft title menu. Therefore the diagnostic endpoint used for hosted coverage is explicitly named `startup_ui_presented`; it records the real active screen class and must not be reported as `main_menu_presented` unless the class is actually `TitleScreen`.

No timing in this document is an optimization claim.

## Prior hosted evidence

PR #214 exact-pack profile run `34290311738` used measurement origin `hosted_exact_pack` and endpoint `main_menu`. Its structured trace recorded:

- `resource_reload` stock `allDone`: `mono_ns=78059969616`;
- legacy `main_menu` (`ScreenEvent.Opening(TitleScreen)`): `mono_ns=79159255459`;
- opening-only tail: **1099.286 ms**.

That run cannot establish a visually usable endpoint because exact-pack benchmark mode stopped from the opening callback.

Earlier PR #122 independently attempted both `ScreenEvent.Render.Post` scoped to TitleScreen and direct mapped `TitleScreen.render(...) @ RETURN`; both had zero title-render coverage despite valid exact-pack launches. PR #128 then source-audited AnalogAudio 0.1.0 and identified the correct behavior-preserving diagnostic boundary as the first actually presented active screen after the TitleScreen opening attempt.

## Current exact-pack observation before the final endpoint smoke

PR #219 run `34294278209` / smoke #751 used the current stacked resource trace plus the first-present diagnostic. A later branch push cancelled the workflow while the client was intentionally still alive, so this run is **not** a completed profile and has no flushed JSONL trace. Its always-uploaded exact-pack artifact is still useful as lifecycle evidence up to cancellation.

The hosted log reached:

```text
00:20:20.293  FancyMenu: Minecraft resource reload: FINISHED
00:20:21.361  BOOTOPTIM_STARTUP phase=main_menu uptime_ms=80622
00:20:21.363  FancyMenu: ScreenCustomizationLayer registered: title_screen
00:20:21.383  Iris: Creating pipeline for dimension minecraft:overworld
00:20:22.393  FancyMenu: ScreenCustomizationLayer registered:
              com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen
00:20:23.502  ModernFix: Game took 82.763 seconds to start
```

The same run had `BOOTOPTIM_MCEF_FIRST_CONSUMER status=deferred` and no later first-consumer/CEF initialization marker before the welcome-screen takeover. Therefore MCEF is not the owner of the observed failure to reach a TitleScreen frame in this hosted launch.

The ~1.068 s FancyMenu-FINISHED -> legacy title-opening log interval in this cancelled run is only a coarse log corroboration. The authoritative allDone -> opening measurement remains #214's 1099.286 ms structured interval; FancyMenu's own FINISHED log is not substituted for `SimpleReloadInstance.allDone`.

## Source-level cause: AnalogAudio replaces TitleScreen during Init.Post

Public AnalogAudio 0.1.0 source at commit `22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3` matches the exact runtime class names. Its `AnalogAudioClientEvents.onScreenInit(ScreenEvent.Init.Post)` runs at `EventPriority.LOW` and does:

```text
if first prompt
and initialized screen instanceof TitleScreen
and lavaplayerWelcomeScreen=true
and LavaplayerLoader.isMissing()
    -> welcomeScreenShown = true
    -> Minecraft.setScreen(new LavaplayerWelcomeScreen(current TitleScreen))
```

The replacement is synchronous on the client/render thread and occurs inside the TitleScreen initialization lifecycle before a normal TitleScreen frame is rendered.

`LavaplayerWelcomeScreen` is intentionally modal:

- it stores the prior TitleScreen and only restores it after user action;
- all three buttons are disabled for 40 screen ticks;
- Escape does not close the screen;
- after the 40-tick delay, the user must choose Install, View on GitHub, or decline/return to the prior screen.

Thus an unattended benchmark cannot reach the underlying navigable TitleScreen without changing observable pack behavior or synthesizing a user choice.

## Final diagnostic design

For `-Dboot_optim.bootTrace.endpoint=startup_ui_presented` only:

1. `ScreenEvent.Opening(TitleScreen)` records `title_open` but does not stop the exact-pack benchmark.
2. Every subsequent screen opening after that attempt records `startup_screen_replacement_open` with replacement ordinal and concrete class.
3. `ScreenEvent.Init.Post` records TitleScreen init separately and records replacement-screen init with concrete class.
4. `RenderFrameEvent.Post` records `startup_ui_render_return` for the **currently active screen**, not an assumed TitleScreen, and stores that exact class in a thread-local token.
5. A GAME-layer Mixin observes the existing `Window.updateDisplay()` invocation immediately after it returns from `Minecraft.runTick(boolean)`. It consumes only a token produced by the same thread and records `startup_ui_presented` with the rendered screen class and replacement count.
6. Only after this first real presentation does benchmark `exitOnTitle` stop the client.

The hook never targets `Window` itself, calls GLFW/RenderSystem, wraps display/present, touches executors/futures, changes listener order, or moves GL/native work. Mixin remains `required=false` / `defaultRequire=0`.

FancyMenu panorama preload and MCEF first-consumer behavior are untouched. Existing logs are correlation evidence only.

## Interpretation contract

The useful hosted serial partitions are:

- `allDone -> title_open`: stock post-reload path to the initial title-opening attempt;
- `title_open -> startup_screen_replacement_open`: TitleScreen initialization/listener work until another screen takes ownership;
- replacement open/init -> `startup_ui_render_return`: construction/initialization and first frame of the real active startup UI;
- `startup_ui_render_return -> startup_ui_presented`: post-render/display-return bucket.

Do not label any wall interval GPU, native, disk, page-cache, Java CPU, FancyMenu or MCEF by subtraction. Attribution requires an explicit marker/callsite inside that bounded interval.

If the final hosted `startup_ui_presented` detail names `LavaplayerWelcomeScreen`, the run validates only **first visibly presented startup UI**. It simultaneously establishes a NO-GO for automatically measuring the requested navigable TitleScreen under this exact fixture: reaching that menu requires user interaction or a workload/config/dependency change.

## Physical limitations

Hosted Linux/Xvfb/llvmpipe can validate Java lifecycle ordering and screen identity, but cannot validate Windows driver latency, a physical GPU/display-present interval, Windows CEF/JCEF startup, OpenAL timing, HDD/page-cache behavior, or visual correctness on the historical laptop.

There is an additional state-equivalence gate before any physical comparison: the laptop must document whether AnalogPlayer is present under `.analogaudio/internal`, whether `lavaplayerWelcomeScreen` is enabled, and whether a human has already crossed the modal in that process. If those differ from hosted, then `allDone -> navigable TitleScreen` is not the same workload and timing values are not comparable.

No laptop run is requested from this lane. A physical diagnostic becomes meaningful only after the project defines a reproducible startup state in which the requested navigable menu exists without synthetic interaction, and hosted first-present instrumentation passes on that same semantic state.

## Hosted gate

Required profile contract for the coarse screen-identity smoke:

- exact pinned pack / fresh hosted VM;
- origin `hosted_exact_pack`, endpoint `startup_ui_presented`;
- one initial reload, exact resource selection, blocks atlas `8192x8192x2`, zero BootOptim Mixin failures;
- one contiguous bootstrap-owned JSONL sequence, zero dropped events;
- monotonic `allDone <= title_open <= replacement/open-or-render <= startup_ui_render_return <= startup_ui_presented`;
- render and present emitted by the same thread;
- endpoint detail contains the concrete screen class and replacement count;
- no MCEF/FancyMenu/presentation ownership claim without an explicit marker inside the corresponding bounded interval.

## Decision rule

There is no optimization candidate in this PR.

- If the actual presented class is `TitleScreen`, the requested menu-present boundary exists in hosted and the bounded tail can be investigated further.
- If the actual presented class is `LavaplayerWelcomeScreen`, close the requested `allDone -> navigable main menu` lane as **NO-GO under the current exact-pack startup state**. Keep only the coarse first-visible-UI profile and the source-level reason; do not auto-decline, seed AnalogPlayer, disable the prompt, or request a laptop performance run.
