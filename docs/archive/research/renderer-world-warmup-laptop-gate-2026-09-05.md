# Renderer coordinated world warmup — laptop gate, 2026-09-05

Status: **REJECTED — TITLE PRESENTATION REGRESSION ON PHYSICAL FAST-PC GATE**

Parent: PR #95 (`agent/experiment-renderer-world-warmup`), head `f491f563066de73a310e8feb6c33aecb58451b79`.

## Scope

This document records a physical Windows laptop run of the experimental renderer first-consumer/world-attachment coordinator. It is not a production promotion and does not change the parent branch's hosted conclusions.

The isolated Prism instance used the exact pack, Oracle JDK 25.0.4, four active processors and a 6 GiB heap. The test used only the isolated `C:\BootOptimBench` game directory.

## Title-path A/B

Two immediate same-JAR runs used the PR #95 bootstrap with the renderer switch changed only by JVM property:

| run | `experimentRendererFirstConsumerDefer` | main-menu uptime |
| --- | --- | ---: |
| `renderer-pr95-candidate-title-011` | true | 481.841 s |
| `renderer-pr95-control-title-012` | false | 512.621 s |

The candidate emitted both `status=deferred` markers and zero pre-title `status=forced` markers. The control emitted no renderer-defer markers. The immediate pair therefore has a favorable **-30.780 s / -6.00%** candidate direction, but it is a single warm/carry-over pair on a highly variable laptop, not a standalone performance claim.

## Candidate world entry

The candidate was then launched with `exitOnTitle=false`, `experimentRendererFirstConsumerDefer=true` and `experimentRendererWorldEntryProbe=true`. A new disposable world was created manually.

The coordinator executed at `Minecraft.updateLevelInEngines(non-null)` in the intended order:

| marker | wall |
| --- | ---: |
| block-entity deferred callback | 995.218 ms |
| entity deferred callback | 3,293.000 ms |
| coordinated warmup | 4,289.088 ms |
| ordinary engine attachment after warmup | 6,828 ms |
| attach begin -> first world render | 59,468 ms |

The first world render marker was reached. This proves the deferred callbacks completed and the renderer maps were usable far enough to render the world once. It does **not** prove a good world-entry experience: a 4.289 s synchronous payment at world attachment needs a control comparison and visual validation before any promotion.

## Fast-PC title presentation regression

On the user's normal fast PC, the same candidate reached the end of the resource-pack load while the screen remained visually stuck at the half-complete loading view. Resizing or toggling fullscreen changed the client surface to black, while menu-click sounds still played. This is a live-client/render presentation failure, not merely a slow title transition.

The implementation explains the failure. Both target dispatchers initialize their renderer maps empty (`ImmutableMap.of()` / `Map.of()`), and their initial `onResourceManagerReload` callbacks populate them. PR #95 cancelled those callbacks, while the global resource reload was permitted to complete. The proposed first-consumer hooks cover only selected `getRenderer`/`getSkinMap` calls; title-screen presentation and modded menu render paths are not required to pass through either hook before their first visual frame. The client can therefore continue ticking and play GUI audio with renderer state that has never been rebuilt.

This makes the lifecycle premise unsafe. These callbacks are reload listeners, rather than optional lazy caches. Forcing them immediately before `TitleScreen` could restore correctness, but would put the full 4.289 s renderer payment before the first usable menu frame and therefore does not retain the claimed startup win.

## Native test-host failure

Immediately after the first-render marker the Java process terminated without a Java crash report or BootOptim renderer exception. Windows Application Error recorded:

```text
javaw.exe / OpenAL.dll
exception code 0xc0000409
LWJGL temp native: ...lwjgl_wachi...\OpenAL.dll
```

This is a native audio-runtime fault on the test host, not evidence that the renderer coordinator crashed. Do not modify the user's Java, drivers or operating system to work around it. A later physical world gate may use a controlled, isolated game-level audio workaround only if it can be shown not to change renderer timing; otherwise this laptop cannot complete visual/world regression validation.

## Decision

- Reject PR #95's listener-cancellation / first-consumer defer mechanism. Do not merge or promote it.
- The laptop title pair is no longer performance evidence for a shippable change: its apparent win was obtained while the renderer reload state was incomplete before title presentation.
- Do not request further laptop world-entry A/B runs for this mechanism. The fast-PC physical gate already exposes an observable correctness regression.
- Reopen only with a materially different architecture that preserves completed renderer state before every title/menu render path, plus a measurement boundary based on first visually usable menu frame rather than a title-screen detector alone.
- Target intrinsic EMF/Fresh Animations/provider construction or another safe, independently profiled critical-path mechanism instead of moving these reload listeners later.
