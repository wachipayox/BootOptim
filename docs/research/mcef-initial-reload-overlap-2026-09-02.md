# MCEF initial resource-reload overlap experiment — 2026-09-02

Status: **REJECTED**

Experiment PR: #78

## Premise

Exact-pack laptop evidence placed MCEF `2.1.6-1.21.1` native CEF initialization immediately before the initial client resource reload. Recent cold runs put that serial interval around 12–16 seconds, with an older run near 19 seconds.

MCEF's real initializer must stay on the Minecraft client/render thread. The experiment therefore did not move CEF to a worker. Instead it tested whether the serial front could be hidden under resource preparation:

```text
stock:
  MCEF.initialize() on Render thread
  -> createReload(...)
  -> resource preparations

candidate:
  suppress first automatic MCEF.initialize()
  -> createReload(...) schedules stock resource work
  -> immediately force the real MCEF.initialize() on Render thread
```

Consumer guards for `getApp`, `getClient` and browser creation forced the real initializer if a browser/video consumer appeared during the short deferral window. Exact MCEF version gating and `@Pseudo` hooks kept MCEF optional/fail-open.

## Exact-pack laptop A/B

The candidate executed the intended lifecycle:

- exact MCEF `2.1.6-1.21.1` gate armed;
- first initialize call suppressed on `Render thread`;
- initial resource reload started;
- real CEF initializer then ran on `Render thread`;
- no `consumer_before_reload` / abort was observed;
- FancyMenu BrowserHandler and MCEFVideoManager initialized subsequently.

Thus the experiment proved its mechanism without an obvious semantic failure in the launch log.

Performance was decisively negative on the 4-thread laptop.

Control:

- `mod_entrypoint=129018 ms`
- `main_menu=473358 ms`
- post-entrypoint -> menu: `344340 ms`
- stock CEF: **15.752 s**
- reload start -> FancyMenu reload finished: **~261.972 s**
- FancyMenu panorama preload: **17.826 s**

Candidate:

- `mod_entrypoint=135092 ms`
- `main_menu=489610 ms`
- post-entrypoint -> menu: **354518 ms**, **+10.178 s worse**
- CEF: **45.941 s**, **+30.189 s / ~2.9x slower**
- reload start -> FancyMenu reload finished: **~266.519 s**, ~**4.547 s worse**
- FancyMenu panorama preload: **12.953 s**

The raw JVM-start -> menu difference was +16.252 s, but ~6.074 s of that difference already existed by `mod_entrypoint`, so it is not all attributable to the candidate. The post-entrypoint marker is the cleaner end-to-end comparison for this mechanism and still regressed by ~10.2 s.

## Interpretation

The experiment did not create spare capacity; it made native CEF initialization compete with the exact pack's already-heavy resource workers. On the constrained 4-thread laptop CEF became a severe contention victim, expanding from ~15.8 s to ~45.9 s.

A local interval can be constructed where overlap looks favorable, but that is not the project objective. Actual time-to-main-menu/post-entrypoint wall regressed, so the overlap mechanism fails the required gate.

## Decision

**REJECTED / NO-GO.**

Do not overlap MCEF native initialization with initial resource preparation again on this premise. Do not request another laptop repeat of this mechanism.

The real MCEF initializer must continue to run on the Minecraft client/render thread; this rejection is not evidence that moving it to a worker would be safe.

## Distinct premise still open

This result does **not** reject first-consumer/lazy MCEF initialization.

That is materially different:

```text
pre-title startup:
  do not initialize CEF at all if no real browser/video consumer needs it

first real consumer:
  force the stock MCEF.initialize() on the Minecraft client/render thread
  -> continue consumer only after real initialization
```

Unlike overlap, first-consumer deferral would avoid the expensive CEF work rather than making it compete with resource workers. It needs independent source/runtime proof because the pack contains a custom `FancyMenu 3.9.0-wedit` build and other MCEF consumers such as WebDisplays.

## Reopening criteria

Reopen resource-reload overlap only if a material premise changes, for example a future MCEF/native runtime makes initialization almost non-contending or the exact pack's resource preparation architecture changes enough to leave proven idle capacity. A still-large serial CEF interval alone is not sufficient.

## Evidence

- PR #78
- PR #78 exact-pack result comment `5514077573`
- MCEF 2.1.6 source/lifecycle research recorded in the experiment PR
