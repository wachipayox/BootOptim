# AnalogAudio / AnalogPlayer startup endpoint audit — 2026-09-05

Status: **NO-GO FOR RUNTIME OPTIMIZATION / VALID DIAGNOSTIC ENDPOINT IDENTIFIED**

Base audited: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

Scope: Analog Audio `0.1.0` in the pinned exact pack, its optional patched Lavaplayer payload (`AnalogPlayer` `1.0.2`), and the meaning of the first usable UI after the initial `TitleScreen` opening attempt. This is a continuation of PR #122's zero-coverage TitleScreen render probe. It does not reproduce the futures/barrier/apply/title profiler lane and does not infer disk/GPU time from `wall - CPU`.

## Executive decision

There is **no startup-performance candidate worth implementing** in the missing-AnalogPlayer path.

The source-level path taken at startup when AnalogPlayer is absent performs filesystem existence/version checks plus synchronous construction/initialization of a small `Screen`. It does **not** download AnalogPlayer, instantiate Lavaplayer, initialize its audio player manager, touch OpenAL through `LavaRadioStreamer`, or retry a failed dependency load during startup. Those heavier paths happen only after explicit user action or later audio playback.

The correct hosted diagnostic endpoint is therefore not `TitleScreen.render`. It is:

> **the first present that returns after rendering the screen identity that actually remains active after the initial TitleScreen opening attempt, while recording the real screen class.**

In the current exact pack that screen is `com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen`.

That endpoint is valid for **first visibly presented startup UI**. It must not be renamed to "first TitleScreen frame" or silently treated as a fully interactive main menu. The AnalogAudio welcome screen is a blocking setup modal: its three buttons are disabled for 40 screen ticks and Escape is disabled. After the buttons activate, the user must choose an action; "Ask me later" restores the saved previous screen. Therefore a benchmark whose product contract requires the actual navigable Minecraft title menu cannot automatically cross this modal without changing behavior.

No user decision is required to instrument the real-screen present endpoint. A user/product decision is required only if BootOptim wants to redefine its KPI to require the underlying `TitleScreen`, because reaching that screen requires a real user choice or a workload-changing intervention.

## Exact source versions

PR #122's hosted log identifies `analogaudio@0.1.0`. Public AnalogAudio source at commit `22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3` has `mod_version=0.1.0`, targets Minecraft 1.21.1 / NeoForge 21.1.220, and pins `analogplayer_version = 1.0.2`.

Sources audited:

- `AnalogAudioClientEvents.java`: https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/AnalogAudioClientEvents.java
- `LavaplayerWelcomeScreen.java`: https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/gui/LavaplayerWelcomeScreen.java
- `LavaplayerProgressScreen.java`: https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/gui/LavaplayerProgressScreen.java
- `LavaplayerLoader.java`: https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/audio/lavaplayer/LavaplayerLoader.java
- `ClientAudioEngine.java`: https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/audio/ClientAudioEngine.java
- AnalogPlayer 1.0.2 release: https://github.com/palmmc/analogplayer/releases/tag/1.0.2
- AnalogPlayer 1.0.2 `LavaRadioStreamer`: https://github.com/palmmc/analogplayer/blob/1.0.2/src/main/java/com/palm1/analogaudio/lavaplayer/LavaRadioStreamer.java

The AnalogPlayer 1.0.2 release asset is a 34,268,216-byte JAR with published SHA-256 `5b53779dca71a5d9f9e15cf9137a6b44d4ca3bb578c9027b30c30578819cebd7`.

## Callgraph: TitleScreen attempt -> effective welcome screen

The relevant path is synchronous on the client/render thread:

```text
Minecraft/GUI setScreen(TitleScreen)
  -> NeoForge ScreenEvent.Opening(old, TitleScreen)
  -> TitleScreen becomes current screen and is initialized
     -> Screen.init(width, height)
        -> ScreenEvent.Init.Pre
        -> TitleScreen.init()
        -> initial focus
        -> ScreenEvent.Init.Post(TitleScreen)
           -> AnalogAudioClientEvents.onScreenInit(...) [priority LOW]
              -> !welcomeScreenShown
              -> event.screen instanceof TitleScreen
              -> ModConfig.Client.lavaplayerWelcomeScreen == true
              -> LavaplayerLoader.isMissing()
                 -> !isInstalled()
                 -> Files.exists(.analogaudio/internal/analogplayer-1.0.2.jar)
              -> welcomeScreenShown = true
              -> currentScreen = Minecraft.getInstance().screen
              -> new LavaplayerWelcomeScreen(currentScreen/title)
                 -> hasOlderVersion()
                    -> Files.isDirectory(.analogaudio/internal)
                    -> if directory exists: Files.list + filename scan
                 -> build translated title/message
                 -> hasOlderVersion() again for message selection
              -> Minecraft.setScreen(LavaplayerWelcomeScreen)
                 -> nested ScreenEvent.Opening(TitleScreen, WelcomeScreen)
                 -> TitleScreen closing/removal
                 -> WelcomeScreen becomes current
                 -> WelcomeScreen.init()
                    -> construct 3 Button widgets
                    -> construct MultiLineLabel
                    -> activateIfReady(): all 3 buttons inactive

next normal frame
  -> render active LavaplayerWelcomeScreen
  -> present/updateDisplay
```

NeoForge's screen hook places `ScreenEvent.Init.Post` after the screen's own `init()` and initial-focus setup, inside the screen initialization path. Its screen-opening hook is posted by `setScreen` before the new screen is committed/initialized. A nested `setScreen` from `Init.Post` therefore legitimately replaces the title before the original title reaches a render frame. This is exactly the lifecycle observed by #122.

Reference NeoForge source for the hook placement:

- `Screen.java.patch`: https://github.com/neoforged/NeoForge/blob/accacb5a4d4cc1b11d553be18b469a3371ca1fe7/patches/net/minecraft/client/gui/screens/Screen.java.patch
- screen opening/set-screen patch: https://github.com/neoforged/NeoForge/blob/accacb5a4d4cc1b11d553be18b469a3371ca1fe7/patches/net/minecraft/client/gui/Gui.java.patch

Those links are current upstream source references; the exact pack uses NeoForge 21.1.248 and AnalogAudio itself was built against 21.1.220. The exact-pack runtime behavior supplies the version-specific confirmation: the TitleScreen opening marker is followed by registration of `LavaplayerWelcomeScreen`, while both a public screen render event scoped to TitleScreen and a direct mapped `TitleScreen.render @ RETURN` had zero coverage in #122.

## What the welcome screen semantically means

The English resource strings call it **"Analog Audio Setup"** and state that Analog Audio requires a patched Lavaplayer library (`Analogplayer`) to stream audio and that crucial mod functionality will not work as intended if the user declines.

The screen offers exactly three choices:

1. **Download & Install** -> opens `LavaplayerProgressScreen`, whose `init()` then starts the asynchronous download.
2. **View on Github** -> opens the AnalogPlayer repository URL.
3. **Ask me later** -> `Minecraft.setScreen(lastScreen)` with no dependency install and no configuration mutation.

All three buttons are initially inactive. `ticksUntilEnable` starts at 40; `tick()` decrements it and enables the buttons when it reaches zero. At the nominal 20 client ticks/s this is approximately two seconds, but that conversion is a behavioral expectation, not a measured startup interval. `shouldCloseOnEsc()` returns false, so Escape cannot bypass the modal while it is shown.

Consequences for BootOptim terminology:

- **First visual UI:** yes, the first presented `LavaplayerWelcomeScreen` is the real visible endpoint in this exact pack and is suitable for a visual-first diagnostic.
- **Interactive setup UI:** not on its first frame; controls deliberately remain disabled for 40 ticks.
- **Minecraft main menu usable for navigation/world entry:** no. The title is behind the modal and requires a user choice. Automatically declining, suppressing, delaying, or installing the dependency would change observable behavior.

## Missing dependency is not doing hidden heavy startup work

`LavaplayerLoader.isMissing()` is only `!isInstalled()`, and `isInstalled()` only checks whether `.analogaudio/internal/analogplayer-1.0.2.jar` exists.

The welcome-screen constructor calls `hasOlderVersion()` twice. That method checks whether the internal directory exists and, only if it does, lists the directory to find a differently-versioned `analogplayer*.jar`. This is redundant filesystem metadata work, but it is tiny in shape and has no evidence of material TTMM cost.

Crucially, the missing-dependency startup path does **not** call `triggerDownload()`.

Repository-wide usage shows `triggerDownload()` is called from `LavaplayerProgressScreen.startDownload()`, which is reached after the user clicks **Download & Install**. The downloader runs on a daemon thread named `Lavaplayer-Downloader`; before starting it marks `lavaplayerDownloadAttempted=true` and saves config. The `lavaplayerDownloadAttempted` field is loaded/saved but is not consulted to trigger an automatic retry at startup.

Likewise, the real AnalogPlayer library is loaded only from `LavaplayerLoader.getStreamer()`: if the JAR is present it creates a `URLClassLoader`, loads `com.palm1.analogaudio.lavaplayer.LavaRadioStreamer`, and constructs it. `ClientAudioEngine` calls `getStreamer()` from `startPlayback`, i.e. when actual radio/cassette playback is requested in gameplay. With the dependency absent, playback fails open by returning `null`, marking that playback identity failed, and optionally warning the player.

AnalogPlayer 1.0.2's `LavaRadioStreamer` has a static `AudioPlayerManager` initialization that registers YouTube, local and remote audio source managers and later uses OpenAL for playback. None of that belongs to the startup path when the JAR is absent.

Therefore there is no source basis for attributing a multi-second startup tail to "Lavaplayer missing" or to repeated dependency initialization.

## Hosted evidence and cost bound

PR #122 hosted exact-pack run `33984571627` shows this sequence on the Render thread:

```text
18:40:19.775  FancyMenu: Minecraft resource reload: FINISHED
18:40:19.867  BOOTOPTIM_STARTUP phase=main_menu
18:40:19.868  FancyMenu: ScreenCustomizationLayer registered: title_screen
18:40:19.891  Palladium/Iris pipeline-cache messages
18:40:19.902  FancyMenu: ScreenCustomizationLayer registered:
              com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen
```

The 35 ms from BootOptim's TitleScreen opening marker to FancyMenu's welcome-screen registration is **not an AnalogAudio exclusive duration**. It includes other Render-thread listeners/work, visibly including Palladium/Iris messages, and the two log sites are not entry/return instrumentation around AnalogAudio. It only bounds the observed lifecycle transition to tens-of-milliseconds scale in this hosted run; it cannot be promoted to a hardware-independent cost claim.

There is no evidence here of a second-scale AnalogAudio/Lavaplayer-missing startup cost. A dedicated micro-timer would be easy to add but is not justified: the source shape is already small, and the project's objective is TTMM rather than optimizing a few filesystem metadata calls without evidence.

Unrelated AnalogAudio initialization/reload work must remain separate. The same hosted log contains an earlier AnalogAudio/Create compatibility registration failure during resource reload. That event is not on the missing-AnalogPlayer screen path and must not be charged to the welcome-screen transition without separate attribution.

## Candidate audit

### Cache/consolidate dependency checks

A direct AnalogAudio cleanup could compute `hasOlderVersion()` once in the welcome-screen constructor rather than twice, or pass a local dependency-state snapshot into the screen. This can preserve semantics within one constructor call, but the only saved work is one directory existence/list operation. There is no evidence it can move TTMM; BootOptim should not carry a compatibility hook for it.

A process-lifetime cache is worse: the dependency can be installed during the same process, so invalidation would become necessary around successful download and any external file change. That adds correctness surface for negligible expected benefit.

Decision: **NO-GO / micro-cleanup only, not a BootOptim startup candidate.**

### Async dependency check

Moving `isMissing()`/`hasOlderVersion()` off-thread creates a race over which screen should be shown and when. Preserving exact selection would require joining the result before screen choice, eliminating the benefit; not joining changes UI behavior.

Decision: **NO-GO.**

### Delay or suppress welcome-screen replacement

Showing even one TitleScreen frame before the warning, auto-declining, cancelling the nested `setScreen`, or changing event priority changes observable UI/navigation semantics. It also changes the point at which the user can choose to install the library. No equivalence argument covers interaction, audio availability, navigation and first-world behavior.

Decision: **NO-GO unless the project explicitly accepts a behavior/product change; not an optimization under the current contract.**

### Seed/install AnalogPlayer

This changes the exact-pack software state and activates a 34 MB optional library with materially different classloading/audio behavior. It is not a benchmark optimization and is expressly outside this lane.

Decision: **NO-GO.**

## Correct diagnostic endpoint

A future diagnostic should distinguish semantic intent from actual visible result.

Recommended low-cardinality events:

```text
title_open_attempt
  = first ScreenEvent.Opening whose requested/new screen is TitleScreen

effective_screen_render_return
  = first render completion after title_open_attempt for screen identity S
    where S is the currently active screen at render time

effective_screen_present_return
  = first Window.updateDisplay/present return after that render of the same S
```

Emit the aggregate only once and include:

```text
screen_class=<actual class name>
requested_title=true|false
replacement_count=<nested openings since title attempt>
render_screen_still_current=true|false
```

The core invariant is **identity**, not class-name guessing: only pair present with the same screen instance whose render completed, and reject/continue if another synchronous replacement occurs first. This handles AnalogAudio without depending on AnalogAudio internals and remains correct if another pack mod replaces the welcome screen again.

For the current exact pack, expected class coverage is `LavaplayerWelcomeScreen`; for a pack state in which AnalogPlayer exists, it may legitimately be `TitleScreen`. Neither path should be manufactured by the diagnostic.

This endpoint is compatible with #120/#122's scheduler-side timestamps but does not require reproducing Agent 19's broad profiler. It is a final visual boundary only.

## Why "actionable main menu" is a separate KPI

A generic diagnostic cannot automatically prove the underlying Minecraft menu is user-navigable in this workload, because the exact screen deliberately blocks it until a user acts.

One could instrument the welcome screen's 40-tick enable point, but that would only prove the setup modal became actionable, not that Singleplayer/Multiplayer/options are accessible. One could auto-click **Ask me later**, but that changes user interaction and therefore the benchmark workload.

Accordingly BootOptim should keep two concepts distinct:

- **first effective startup screen presented** — automated, behavior-preserving, and measurable now;
- **underlying TitleScreen usable / first-world navigation available** — user-interaction-dependent in this exact pack and not safely automatable without an explicit product decision.

The existing production `BOOTOPTIM_STARTUP phase=main_menu` marker currently fires on the TitleScreen opening attempt and is therefore semantically earlier than the real visible screen in this workload. Existing historical numbers remain valid as their old marker definition, but future diagnostics should not describe that marker as proof that a TitleScreen frame was presented.

## Reopening criteria

Reopen runtime optimization only if a new measurement proves that AnalogAudio's actual missing-dependency transition itself owns material critical-path wall/CPU, not merely because the TitleScreen marker is early.

Reopen the final-boundary diagnostic without changing the pack by using the effective-screen identity/present contract above. A hosted smoke must prove non-zero coverage, one aggregate marker, exact resource selection, one effective reload, expected atlas, zero BootOptim Mixin failures, and the actual screen class. No A/B is needed for diagnostic-only instrumentation.

If a runtime candidate is later found, follow normal project hierarchy: Build/Startup -> hosted exact-pack smoke for semantic coverage -> hosted exact-pack A/B for performance -> physical gate only when justified. No physical A/B is requested from this audit.
