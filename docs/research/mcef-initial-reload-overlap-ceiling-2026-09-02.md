# MCEF initial-resource-reload overlap ceiling — 2026-09-02

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE AS PRODUCTION**

## Target

Exact pack: Minecraft 1.21.1, NeoForge 21.1.248, MCEF `2.1.6-1.21.1`, FancyMenu runtime build `3.9.0-wedit`, WebDisplaysFork `2.5.0-1.21.1`.

MCEF initialization is a clean serial startup front immediately before the initial client resource reload. Recent cold-boot laptop runs measured:

- matched control #77: `Initializing CEF` 18:15:32.165 -> initialized 18:15:44.380 = ~12.215 s;
- read-ahead run #76: 18:34:17.146 -> 18:34:31.302 = ~14.156 s;
- older diagnostic run: ~19.084 s.

The current public ceiling should therefore be treated as variable, roughly 12–19 s depending on the run, not as a guaranteed saving.

## Exact upstream lifecycle

CinemaMod/mcef branch `1.21.1` identifies itself as `mcef_version=2.1.6-1.21.1`.

`CefInitMixin` injects at `Minecraft.setScreen` HEAD. Once the downloader is done, it schedules on Minecraft's executor:

```text
Thread.sleep(1000)
MCEF.initialize()
```

`MCEF.initialize()` runs `CefUtil.init()`, constructs the real app/client, then executes and clears the `awaitingInit` callbacks. `scheduleForInit` only appends to that list. `GameRenderer` pumps CEF only when `MCEF.isInitialized()` is true.

Therefore:

- do not fake `isInitialized`;
- do not dispatch init callbacks manually;
- do not move CEF initialization to an arbitrary worker;
- preserve the real `MCEF.initialize()` on the Minecraft client/render thread.

## Changed premise vs first-consumer defer

The pack's FancyMenu is a custom `3.9.0-wedit` build and its exact fork source is not publicly identifiable. Deferring CEF all the way to the title screen or first browser would increase compatibility risk because unknown title-screen customizations could be CEF consumers.

This experiment instead changes only the ordering around the initial resource reload:

```text
stock:
  automatic CEF init (Render thread, blocking)
  -> createReload(...)
  -> async resource preparations

ceiling candidate:
  suppress only the automatic CefInitMixin call
  -> createReload(...) returns after stock preparation work is scheduled
  -> invoke the real MCEF.initialize() on the Render thread
  -> async resource preparations and CEF initialization can overlap
```

CEF is still initialized long before resource reload completion and title-screen readiness. Downloader behavior and callbacks remain stock.

## Compatibility guards

The experiment is opt-in with:

```text
-Dboot_optim.experimentMcefReloadOverlap=true
```

It is additionally gated to the exact MCEF version string `2.1.6-1.21.1`.

The optional MCEF hook is `@Pseudo` and has no compile-time MCEF dependency. Automatic-init suppression is limited to stack traces originating in `com.cinemamod.mcef.mixins.CefInitMixin`; a direct `MCEF.initialize()` request from another mod is allowed and aborts the defer.

`getApp`, `getClient`, and both `createBrowser` overloads are guarded. If a real consumer occurs during the short defer window, the real initialization is forced before the original consumer continues. CEF remains on the Minecraft thread.

After `ReloadableResourceManager.createReload` returns, the experiment immediately invokes the real initializer and records the duration.

## Required evidence before any production discussion

1. Build CI and Startup CI must pass with MCEF absent; optional targets must fail open.
2. Exact-pack diagnostic run must show:
   - one or more `event=suppress_auto_init` rows;
   - `event=reload_started`;
   - `event=force_init_complete trigger=reload_started result=true`;
   - no `consumer_before_reload`, `abort`, new MCEF exception, or visual/menu regression.
3. Compare initial reload wall and time-to-main-menu against a matched control with the same JAR and the property disabled.
4. A local phase win is insufficient. Promotion requires a repeatable end-to-end win on the laptop and no regression on the fast PC.

The explicit 1-second sleep in `CefInitMixin` remains before the suppressed call in this experiment, so this ceiling does not attempt to recover that second.
