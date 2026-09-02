# LevelRenderer resource-reload split diagnostic — 2026-09-03

Status: **DIAGNOSTIC ONLY / DO NOT MERGE**

## Premise

The slow-laptop reload critical-path trace recorded an ordered post-turn interval of roughly **6.507 s** for `LevelRenderer`. Because this interval is after the global preparation gate, it is a real serial wall ceiling, not a listener-inclusive async task sum.

PR #75 attributed the vanilla 1.21.1 reload shape to world-render post-processing setup:

```text
LevelRenderer.onResourceManagerReload
  -> initOutline()
  -> if Minecraft.useShaderTransparency(): initTransparency()
```

Exact 1.21.1 source inspection confirms that `onResourceManagerReload(ResourceManager)` calls `initOutline()` and conditionally enters shader-transparency setup. `initOutline` is public; `initTransparency` is private.

These methods create/replace world-render post chains and targets. They may include resource JSON/shader reads, Java parsing/object construction, GL shader/program/FBO work, and driver stalls. GL work must remain on the render thread.

## Question

Before any startup-only lazy/defer experiment, determine how much of the `LevelRenderer` reload wall is actually inside:

1. `initOutline`;
2. `initTransparency`;
3. remaining `onResourceManagerReload` work.

Also compare current render-thread CPU time with wall time. A large wall/CPU gap is evidence of waiting/native/driver time, not Java CPU that can simply be parallelized.

## Diagnostic

Opt-in property:

```text
-Dboot_optim.profileLevelRendererReload=true
```

Marker:

```text
BOOTOPTIM_LEVEL_RENDERER_RELOAD
```

The mixin measures only the first `LevelRenderer.onResourceManagerReload` and records:

- total wall and current-thread CPU;
- `initOutline` wall/CPU/call count;
- `initTransparency` wall/CPU/call count;
- residual `other` wall/CPU;
- executing thread name.

No call is skipped, moved, reordered, wrapped in another executor, or replaced. CPU timing is read only when the JVM already exposes enabled current-thread CPU time; otherwise CPU fields report `unavailable`.

## Decision gate

- If outline/transparency account for most of the exact-pack LevelRenderer interval, investigate a separate startup-only defer experiment. The production candidate must still initialize the same post chains on the render thread before their first world-render use and must leave in-world resource reload behavior eager initially.
- If most wall remains in `other`, do **not** implement lazy post chains; attribute the residual first.
- Hosted llvmpipe timing is useful for mechanism attribution but is not a final Windows/GPU performance gate. Any GL-heavy production candidate still requires real-hardware validation.

## Evidence

- PR #47 — ordered reload critical-path tracer
- PR #75 — source-level apply-tail attribution
- NeoForge 1.21.1 `LevelRenderer.java.patch`
- Minecraft 1.21.1 mapped/decompiled source shape cross-check
