# Post-FancyMenu critical-tail diagnostic — hosted TitleScreen no-go — 2026-09-05

Status: **NO-GO FOR THE REQUESTED HOSTED TITLESCREEN RENDER/PRESENT BOUNDARY**

Final branch base: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

PR: #122.

This entry records the attempted low-perturbation diagnostic requested after #120. No diagnostic runtime code survives in the final branch. The attempt established that the requested final boundary — first `TitleScreen` render and the following present — does not exist in the current hosted exact-pack launch because another exact-pack mod synchronously replaces the title screen during its initialization.

This is an observability no-go, not a performance result and not an optimization rejection. It does not identify the source of the variable physical post-FancyMenu wall tail.

## Requested diagnostic contract

The intended first-startup-reload trace was:

```text
FancyMenu ResourcePreLoader.preLoadAll RETURN
  -> SimpleReloadInstance allPreparations
  -> FancyMenu listener ordered turn-ready
  -> FancyMenu listener returned future complete
  -> SimpleReloadInstance allDone
  -> ScreenEvent.Opening(TitleScreen)
  -> first TitleScreen render return
  -> first Window.updateDisplay return after that render
```

The scheduler portion reused the proven #47 primitive: delegate the stock `SimpleReloadInstance.StateFactory.create(...)`, original preparation barrier, returned listener future and original executors, recording monotonic timestamps without summing overlapping listener durations. FancyMenu listener identity was learned dynamically from the original apply-executor context at `preLoadAll RETURN`; no historical listener index or class-name-only match was used.

The attempted property was default-off:

```text
-Dboot_optim.profilePostFancyMenuTail=true
```

The diagnostic contained no sleep/park, no resource or listener reordering, no concurrency changes, no GL/OpenAL movement, no per-resource logging and no production optimization. Pure timeline tests covered monotonic ordering, first-observation retention and single emission.

## Intermediate implementation gates

The final experimental code head before removal was `5b3b5eb9b489d14bfc5ccea817fc0b2764b4a913`.

- Build run `33984571628`: **SUCCESS**, including the timeline tests and packaged-bootstrap validation.
- Startup Benchmark run `33984571634`: **SUCCESS** with the diagnostic property absent, demonstrating that the default-off path did not alter the normal benchmark exit path.
- Hosted exact-pack run `33984571627`: workflow **SUCCESS**, property explicitly enabled, exact resource-selection contract valid, one effective reload, blocks atlas `8192x8192x2`, semantic `BOOTOPTIM_STARTUP phase=main_menu` reached, and `bootoptim_mixin_errors=0`.

Those conditions are necessary but not sufficient for this diagnostic. The required aggregate `BOOTOPTIM_POST_FANCYMENU_TAIL` marker occurred **zero times**, so the hosted run fails the diagnostic coverage gate and is not called a successful diagnostic smoke.

The hosted run's ordinary startup numbers (`main_menu_ms=92991`, production FancyMenu panorama marker `4019.535 ms`, etc.) are not candidate/control data and must not be interpreted as savings or regressions from this instrumentation.

## Two safe render boundaries both had zero coverage

A prior hosted attempt on head `aede61ce878afb1091110d2bb3c639793a628f57`, run `33983951004`, used NeoForge `ScreenEvent.Render.Post` after a `TitleScreen` opening. The exact pack, atlas and main-menu marker were valid, but the aggregate diagnostic marker again occurred zero times.

The follow-up deliberately avoided guessing at FancyMenu internals and used the mapped vanilla boundary directly: `TitleScreen.render(GuiGraphics,int,int,float) @ RETURN`, followed by `Window.updateDisplay() @ RETURN` only after that render. Build and startup remained green, but hosted run `33984571627` still produced no title-render boundary.

Because both a public NeoForge render event and the direct mapped `TitleScreen.render` return fail to occur after the observed `TitleScreen` opening, adding a more invasive injection into FancyMenu/custom-screen internals would be the fragile behavior specifically ruled out by the task.

## Confirmed source-level reason: AnalogAudio replaces TitleScreen during Init.Post

The exact hosted log identifies `analogaudio@0.1.0`. Immediately after the semantic title-opening marker it records FancyMenu registering a customization layer for:

```text
com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen
```

Relevant timestamps from hosted run `33984571627`:

```text
18:40:19.775  FancyMenu: Minecraft resource reload: FINISHED
18:40:19.867  BOOTOPTIM_STARTUP phase=main_menu
18:40:19.868  FancyMenu: ScreenCustomizationLayer registered: title_screen
18:40:19.902  FancyMenu: ScreenCustomizationLayer registered: com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen
```

Public AnalogAudio source at commit `22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3` explains this exact lifecycle. `AnalogAudioClientEvents.onScreenInit(ScreenEvent.Init.Post)` runs at `EventPriority.LOW`; when the initialized screen is a `TitleScreen`, `lavaplayerWelcomeScreen` is enabled and `LavaplayerLoader.isMissing()` is true, it synchronously calls:

```java
Minecraft.getInstance().setScreen(
    new LavaplayerWelcomeScreen(currentScreen != null ? currentScreen : event.getScreen()));
```

`LavaplayerWelcomeScreen` extends `Screen`, not `TitleScreen`. The replacement therefore happens inside title initialization, before the original title screen can become the screen rendered by the normal frame path. The hosted evidence is coherent with the source: title **opening** occurs, but no `TitleScreen` render occurs to timestamp.

Source references:

- https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/AnalogAudioClientEvents.java
- https://github.com/palmmc/AnalogAudio/blob/22a1d25a05d2ba0147acf5262fb0e4be6e75a1f3/src/main/java/com/palm1/analogaudio/client/gui/LavaplayerWelcomeScreen.java

## Why the requested hosted boundary is a no-go

There is no safe way for BootOptim to manufacture a first `TitleScreen` render in this hosted workload while keeping the workload unchanged:

- disabling AnalogAudio's welcome screen changes an exact-pack mod configuration/behavior;
- installing or seeding Lavaplayer changes the hosted workload and the screen lifecycle;
- delaying/cancelling the `setScreen` replacement changes another mod's observable UI semantics;
- treating `LavaplayerWelcomeScreen` as if it were the title screen changes the measurement question;
- injecting into FancyMenu or AnalogAudio custom rendering solely to force a marker would be a fragile pack-specific dependency and still would not make the original `TitleScreen` render.

A generic boundary such as “first actual screen render/present after a TitleScreen opening attempt” is technically measurable, but it answers a materially different question. It would measure the welcome screen in this fixture, not the requested first usable title frame. That semantic change should be explicit before reopening the lane.

Therefore the diagnostic code is removed rather than retained behind a kill switch that cannot satisfy its own hosted coverage contract.

## What remains valid from the attempted design

The scheduler semantics themselves remain valid and reusable if a future workload exposes a real title-frame boundary:

- `allPreparations -> FancyMenu turn_ready`: ordered barrier/future wait after the global preparation gate;
- `turn_ready -> listener_future_complete`: ordered FancyMenu apply/listener interval, not automatically CPU work;
- `listener_future_complete -> allDone`: later listener/global reload tail;
- process CPU and Render-thread CPU counters are cumulative attribution aids, not exclusive work;
- a low-CPU wall residual remains only an external/native/I/O/descheduling bucket until another boundary identifies its cause;
- `wall - CPU` must never be labelled disk, page cache, GPU or driver time by subtraction alone.

None of these inclusive intervals is a TTMM saving ceiling.

## Reopening criteria

Reopen only if one of these material premises changes:

1. the hosted exact-pack fixture/harness legitimately reaches and renders a `TitleScreen` without suppressing an exact-pack screen transition; or
2. the project explicitly changes the semantic endpoint from “first TitleScreen render/present” to “first actually presented screen after the title-opening attempt,” accepting that the screen may be a non-title modal/welcome screen.

If reopened under (1), the minimal #47-style scheduler trace plus direct render/present boundaries remains the preferred implementation. If reopened under (2), define the usable-screen contract first and do not call that replacement-screen timestamp a TitleScreen frame.

No laptop run and no A/B are requested from this no-go. The two hosted runs were instrumentation/coverage checks only.
