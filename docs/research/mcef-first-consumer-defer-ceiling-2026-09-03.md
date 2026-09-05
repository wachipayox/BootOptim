# MCEF first-consumer defer ceiling — 2026-09-03

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE AS PRODUCTION**

## Premise

The previous MCEF overlap experiment (#78) was rejected. It suppressed the normal pre-reload MCEF initialization only long enough to start the resource reload and then forced real `MCEF.initialize()` immediately. On the slow laptop that created severe contention: CEF initialization expanded from roughly 15.752 s to 45.941 s and post-entrypoint time-to-menu regressed by roughly 10.18 s.

This experiment tests a materially different premise: **do not execute CEF at all before the title boundary unless a real CEF API consumer asks for it**.

It does not move CEF to a worker and does not overlap CEF with the resource reload.

## Current hardware evidence

The 2026-09-03 LevelRenderer diagnostic run is also a useful current MCEF observation:

- `Initializing CEF`: 01:01:24.214
- `Chromium Embedded Framework initialized`: 01:01:42.577
- log-bounded CEF interval: ~18.363 s
- resource reload starts immediately afterwards at 01:01:42.866

The same run reached BootOptim `main_menu` at 424.374 s uptime. The absolute startup time is noisy and is not used as a performance baseline here; the important fact is that CEF remains a large serial block directly before reload.

## Exact lifecycle constraints

MCEF `2.1.6-1.21.1` uses its own download/extraction thread for the native distribution. Its `CefInitMixin` later schedules `MCEF.initialize()` through Minecraft's client executor, after an explicit one-second sleep.

`MCEF.initialize()` owns the real JCEF startup and then dispatches MCEF's registered init hooks. `GameRenderer` pumps the CEF message loop only after MCEF reports initialized.

Therefore the experiment preserves these invariants:

- no fake `MCEF.isInitialized()` state;
- no manual dispatch of MCEF init hooks;
- no JCEF initialization on an arbitrary worker;
- the real `MCEF.initialize()` remains the only initialization path;
- MCEF remains optional and the hook is version-gated/fail-open.

The explicit one-second sleep in MCEF's automatic init path still happens before BootOptim sees and suppresses `initialize()`. This ceiling does **not** claim that second as recovered time.

## Exact-pack consumers already known

FancyMenu `3.9.0-wedit` logs during mod initialization that `BrowserHandler` and `MCEFVideoManager` will wait because MCEF is not yet initialized. That demonstrates those initializers can register/wait without CEF already being ready.

WebDisplaysFork registers work through MCEF's init-hook mechanism; its actual browser creation happens from later consumers. However the custom exact-pack FancyMenu layout and forked mods remain authoritative, so source-level expectations are not enough to promote a defer.

The decisive test is dynamic: can the exact pack reach BootOptim's title/main-menu marker with MCEF's real initialized state still `false` and without any guarded consumer forcing initialization?

## Diagnostic design

Property:

```text
-Dboot_optim.experimentMcefFirstConsumerDefer=true
```

The experiment is exact-version gated to MCEF `2.1.6-1.21.1`.

At the normal automatic `MCEF.initialize()` call:

1. verify the feature is enabled and exact MCEF version matches;
2. require Minecraft's client thread;
3. leave MCEF's real initialized state untouched;
4. cancel only this automatic call and return the value expected by MCEF's caller;
5. remain in `DEFERRED` state.

Direct consumer guards are installed on the MCEF APIs already validated in #78:

- `getApp()`;
- `getClient()`;
- `createBrowser(String, boolean)`;
- `createBrowser(String, boolean, int, int)`.

If one is invoked while deferred, BootOptim synchronously requests the **real** `MCEF.initialize()` on the Minecraft client thread before the original consumer proceeds. If initialization already happened, BootOptim does nothing. A late queued automatic init is suppressed only when MCEF itself confirms it is already initialized, preventing accidental double initialization.

Immediately before BootOptim emits the normal `main_menu` marker, the diagnostic logs MCEF state but deliberately does not initialize it.

## Required markers

Candidate success for the TTMM ceiling requires:

```text
BOOTOPTIM_MCEF_FIRST_CONSUMER event=armed ...
BOOTOPTIM_MCEF_FIRST_CONSUMER event=deferred ...
BOOTOPTIM_MCEF_FIRST_CONSUMER event=suppress_auto_init ...
BOOTOPTIM_MCEF_FIRST_CONSUMER event=main_menu state=deferred initialized=false ...
```

and must have no:

```text
event=consumer_force
event=force_init_failed
event=abort
```

before `main_menu`.

If a consumer appears, that is useful evidence rather than a failure of the guard: the experiment must initialize CEF before allowing the consumer to proceed. It simply means the current exact-pack title path is not CEF-free up to that point.

## Hosted A/B

Use the pinned exact-pack hosted surrogate with three independent VMs per side:

```text
candidate: -Dboot_optim.experimentMcefFirstConsumerDefer=true
control:   -Dboot_optim.experimentMcefFirstConsumerDefer=false
```

Primary measurements:

- `main_menu` median;
- `mod_entrypoint -> main_menu` median;
- reload start -> FancyMenu/reload completion where available;
- candidate MCEF first-consumer markers;
- any new MCEF/FancyMenu/WebDisplays errors.

The hosted runner exits at the BootOptim title marker. Therefore `main_menu state=deferred initialized=false` proves only that **TTMM up to that marker** did not require CEF. It is not by itself enough to promote an indefinitely deferred production implementation.

## Promotion boundary if the ceiling wins

A production candidate would need a conservative lifetime boundary beyond the benchmark marker. The preferred architecture remains:

1. normal MCEF native download/preparation continues early;
2. mods register normal MCEF init hooks;
3. suppress the automatic pre-title CEF init;
4. first real guarded browser/app/client consumer forces the real init;
5. add a conservative fail-safe before entering a world if needed for WebDisplays or unguarded consumers;
6. preserve all initialization and callbacks on Minecraft's client/render thread.

Before production, validate the exact FancyMenu first screen beyond the CI marker and test a world transition/WebDisplays consumer. A strong hosted TTMM win is a ceiling, not sufficient semantic proof.

## Stop conditions

Close without production candidate if any of the following occurs:

- exact-pack pre-title consumer forces CEF before the title marker;
- no repeatable hosted TTMM win despite CEF remaining deferred;
- new FancyMenu/WebDisplays/MCEF error or visible title regression;
- version/signature guards cannot remain fail-open;
- correct first-consumer coverage would require broad invasive interception of unrelated mods.

This experiment is deliberately separate from #78. The rejected resource-reload overlap premise must not be reopened merely because this first-consumer ceiling exists.
