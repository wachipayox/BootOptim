# MCEF first-consumer defer

## Status

Production candidate. Enabled by default only for exact MCEF `2.1.6-1.21.1`.

Kill switch:

```text
-Dboot_optim.mcefFirstConsumerDefer=false
```

## Mechanism

MCEF normally schedules real CEF initialization before the initial resource reload. BootOptim suppresses only that automatic `MCEF.initialize()` while the exact compatibility gate is satisfied. The first guarded CEF consumer synchronously executes the real `MCEF.initialize()` on Minecraft's client/render thread before continuing.

The implementation does not forge `MCEF.isInitialized()`, does not dispatch MCEF init hooks itself, and does not initialize JCEF on a worker. Native MCEF preparation/downloading remains under MCEF's ownership.

Guarded direct APIs are `getApp()`, `getClient()`, and the two known `createBrowser` overloads. FancyMenu `3.9.0-wedit` also has high-level guards for its MCEF video and browser paths because its own readiness bridge can return before those direct APIs are reached. `BrowserElement` retains FancyMenu's original construction path and only retries it after FancyMenu's real MCEF bridge becomes ready.

MCEF's exact-version `CefInitMixin` also queues a client-thread task on ordinary screen transitions. That task sleeps one second before calling `MCEF.initialize()`. While first-consumer defer is active, the call would be suppressed, so each title/options transition would needlessly freeze the render thread. BootOptim now skips only a task submitted from MCEF's injected `redirScreen` handler while the defer state is active. MCEF's handler still performs its own download/error-screen handling; a real browser or video consumer still runs the real initializer through the existing guards. This is scoped to the same exact MCEF version and kill switch.

## Fail-open rules

- Feature is disabled by the kill switch.
- Exact MCEF version mismatch or absent MCEF leaves stock behavior untouched.
- Optional Mixin targets are `@Pseudo` and injections use `require=0`.
- If the automatic initializer is unexpectedly invoked off Minecraft's client thread, BootOptim aborts the defer and allows stock initialization.
- The screen-task skip requires the exact transformed MCEF handler on the current stack. If MCEF changes that handler, the check fails open and leaves its task untouched.
- BootOptim never writes FancyMenu's or MCEF's initialized flags.

## Evidence

Hosted exact-pack PR #88 3x3 A/B: control main-menu median `93.026 s`, candidate `91.032 s`, delta `-1.994 s / -2.14%`.

Dynamic FancyMenu validation then attached the pack's real `video_mcef` block to the proven-active title layout in an ephemeral fixture. The real `MCEFVideoMenuBackground.render` path forced CEF after about `44.112 s` of deferral; CEF initialized successfully in about `1,645.708 ms`, and FancyMenu's existing bridge completed with `MCEFVideoManager successfully initialized!` and no video/player failure markers.

The pack's WebDisplaysFork `2.5.0-1.21.1` registers its normal callback through `MCEF.scheduleForInit` and creates browsers through `MCEF.getClient()`, so the real MCEF lifecycle remains sufficient; no BootOptim-specific WebDisplays callback is dispatched.

Full evidence and validation boundaries: [`../../research/mcef-first-consumer-defer-2026-09-03.md`](../../research/mcef-first-consumer-defer-2026-09-03.md).

The screen-task correction was validated on the fast PC exact pack: BootOptim skipped the redundant delayed MCEF task on menu transitions, and Title→Options / Options→Title displayed-frame gaps fell from approximately one second in the previous diagnostic run to 99 / 100 ms. The user reported normal visuals, successful world entry and successful F3+T. This is a physical behavior check, not a paired TTMM benchmark; see [`../../research/mcef-screen-transition-sleep-2026-09-28.md`](../../research/mcef-screen-transition-sleep-2026-09-28.md).
