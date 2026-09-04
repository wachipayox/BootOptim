# Renderer coordinated world warmup — laptop gate, 2026-09-05

Status: **TITLE PATH REPRODUCED / PHYSICAL WORLD GATE INCOMPLETE**

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

## Native test-host failure

Immediately after the first-render marker the Java process terminated without a Java crash report or BootOptim renderer exception. Windows Application Error recorded:

```text
javaw.exe / OpenAL.dll
exception code 0xc0000409
LWJGL temp native: ...lwjgl_wachi...\OpenAL.dll
```

This is a native audio-runtime fault on the test host, not evidence that the renderer coordinator crashed. Do not modify the user's Java, drivers or operating system to work around it. A later physical world gate may use a controlled, isolated game-level audio workaround only if it can be shown not to change renderer timing; otherwise this laptop cannot complete visual/world regression validation.

## Decision

- Do not merge or promote PR #95 from this evidence.
- Keep the hosted title-path result and the physical title-path direction as evidence that the lifecycle premise is real.
- Treat the 4.289 s world-attachment payment as a product-risk gate, not an acceptable automatic trade.
- Before reopening promotion, obtain a control world-entry measurement on a stable audio path and verify normal entity/player/block-entity rendering, second entry and manual resource reload.
- If that payment remains unacceptable, target intrinsic EMF/Fresh Animations/provider construction rather than moving the payment later again.

