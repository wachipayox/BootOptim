# LevelRenderer resource-reload split — 2026-09-03

Status: **PROFILED / LIMITED CURRENT CEILING**

Diagnostic PR: #85 (`agent/diagnostic-levelrenderer-reload-split`). The diagnostic branch is intentionally not a production merge candidate.

## Premise

PR #75 attributed a historical slow-laptop ordered post-turn interval of roughly **6.507 s** to the `LevelRenderer` resource-reload listener. Because this interval was measured after the global preparation gate, it was a real serial interval, but it had not been decomposed and therefore was not a defensible savings claim for any individual `LevelRenderer` operation.

Exact Minecraft/NeoForge 1.21.1 source gives the relevant shape:

```text
LevelRenderer.onResourceManagerReload
  -> initOutline()
       -> PostChain.load(entity_outline)
       -> PostChain.resize(...)
  -> if Minecraft.useShaderTransparency(): initTransparency()
```

The reference pack is not using Fabulous graphics at startup, so `initTransparency()` is not entered in the measured runs.

## Diagnostic design

PR #85 added opt-in, first-reload-only timing with no behavior change:

```text
-Dboot_optim.profileLevelRendererReload=true
```

It records:

- total `LevelRenderer.onResourceManagerReload` wall/current-thread CPU;
- `initOutline` wall/current-thread CPU;
- `initTransparency` wall/current-thread CPU;
- residual `other` wall/current-thread CPU;
- `PostChain.load(minecraft:shaders/post/entity_outline.json)`;
- the subsequent `PostChain.resize(...)`.

No call is skipped, reordered, moved off the Render thread, or replaced.

## Hosted exact-pack evidence

Hosted Linux/Xvfb/llvmpipe is useful for mechanism attribution, not for Windows-driver timing.

The final hosted diagnostic measured approximately:

| Scope | Wall |
| --- | ---: |
| `LevelRenderer` reload | 34.588 ms |
| `initOutline` | 34.294 ms |
| `PostChain.load(entity_outline)` | 20.433 ms |
| `PostChain.resize(...)` | 8.716 ms |
| `initTransparency` | 0 ms |

This established that the instrumentation reaches the expected path and that the environment is highly hardware/driver sensitive; it did **not** establish a production ceiling for Windows.

## Slow-laptop exact-pack result

A single attribution run on the reference Windows laptop used Oracle Java `25.0.4+7-LTS-189`, the normal exact pack, no JFR, and only the #85 diagnostic property.

Startup markers for that run were:

```text
mod_entrypoint = 132,587 ms
main_menu      = 424,374 ms
```

The first `LevelRenderer` reload measured:

| Scope | Wall | Render-thread CPU |
| --- | ---: | ---: |
| `LevelRenderer.onResourceManagerReload` | 2,060.814 ms | 171.875 ms |
| `initOutline` | 1,462.166 ms | 171.875 ms |
| `PostChain.load(entity_outline)` | 1,458.248 ms | 171.875 ms |
| `PostChain.resize(1024x769)` | 1.839 ms | 0 ms |
| `initTransparency` | 0 ms | 0 ms |
| residual `other` | 598.647 ms | 0 ms |

The current hardware result therefore does **not** reproduce the old ~6.507 s listener interval. The old interval must not be carried forward as a recoverable `LevelRenderer` ceiling.

The dominant narrow operation in the current run is `PostChain.load(entity_outline)`, at roughly **1.458 s wall**, while only about **171.9 ms** of current Render-thread CPU is visible inside it. The measurement cannot by itself distinguish resource waiting, native/driver work, descheduling, or other non-current-thread-CPU effects, so it would be incorrect to label the ~1.286 s wall/CPU gap as disk I/O or driver time specifically.

`PostChain.resize` is effectively irrelevant in this run (~1.8 ms), so an optimization aimed at target/FBO resizing has no useful startup ceiling here.

## Decision

**NO-GO for treating the historical ~6.5 s as a LevelRenderer savings target.** The diagnostic reduced the current defensible ceiling to about **2.061 s for the whole listener** and about **1.458 s for the entity-outline load** on the slow laptop.

A startup-only defer of the initial entity-outline post chain remains technically plausible because this is world-render infrastructure, but it is now a **secondary, limited-ceiling candidate**, not the next P0. Any future candidate must:

1. leave in-world resource reload eager initially;
2. perform the same `PostChain` construction on the Render thread;
3. force initialization before the first entity-outline/world-render use;
4. preserve NeoForge/mod interactions with `LevelRenderer` state;
5. be evaluated against time-to-main-menu, not just the 1.458 s subphase.

The project currently has a materially larger serial pre-title target: the same laptop run spent about **18.363 s** inside MCEF CEF initialization immediately before resource reload. Therefore MCEF first-consumer/lazy initialization has substantially higher expected value and takes priority over implementing a LevelRenderer defer.

## Reopening criterion

Reopen a production LevelRenderer defer only if one of these becomes true:

- MCEF/high-ceiling resource work is exhausted and a ~1–2 s hardware-specific win is worth the compatibility surface;
- repeated hardware measurements show `entity_outline` load is consistently much larger than this run;
- source/consumer analysis produces a very low-risk first-use boundary with a verifier that can prove identical world-render behavior.

Do not reopen merely because the old PR #75 trace contains a 6.507 s `LevelRenderer` post-turn interval.
