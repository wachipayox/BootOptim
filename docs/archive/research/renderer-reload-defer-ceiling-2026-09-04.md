# Renderer reload first-consumer defer ceiling — 2026-09-04

Status: **VALIDATED CEILING / DIAGNOSTIC ONLY**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Premise

PR #92 narrowed the initial resource-reload renderer tail on the hosted exact pack:

- `BlockEntityRenderDispatcher`: ~388 ms, with ~387 ms inside `BlockEntityRenderers.createEntityRenderers`;
- `EntityRenderDispatcher`: ~2,681 ms, with ~2,603 ms inside `EntityRenderers.createEntityRenderers`, ~62 ms in player renderer construction, and only ~16 ms after creation;
- `EntityModelSet.bakeLayer` itself was only ~19.6 ms for entities and ~19.1 ms for block entities; repeated layer keys accounted for only single-digit milliseconds, rejecting a layer-result reuse/cache direction.

The older slow-laptop ordered post-turn trace in PR #75 observed much larger serial ceilings: ~5.316 s block entity and ~6.839 s entity. These callbacks execute after the global preparation barrier and therefore extend time-to-menu directly.

Scoped stack sampling added on #92 identified EMF/Fresh Animations math-expression parsing, model-part variable resolution, expression-tree optimization and ASM compilation as the dominant intrinsic work inside entity renderer creation.

## Ceiling experiment

Property:

```text
-Dboot_optim.experimentRendererReloadDeferCeiling=true
```

On the first `onResourceManagerReload` callback only, the diagnostic cancels:

- `EntityRenderDispatcher.onResourceManagerReload`;
- `BlockEntityRenderDispatcher.onResourceManagerReload`.

It deliberately implements no fallback. Any genuine pre-title renderer-map dependency therefore had an opportunity to fail naturally.

## Exact-pack result

Hosted exact-pack A/B, three fresh VMs per variant:

| metric | candidate skip | control | candidate - control |
| --- | ---: | ---: | ---: |
| main menu | 87.846 s | 95.022 s | **-7.176 s / -7.55%** |
| mod entrypoint | 30.126 s | 32.052 s | -1.926 s |
| post-entrypoint | 57.720 s | 62.684 s | **-4.964 s / -7.92%** |
| reload -> FancyMenu finish | 38.773 s | 43.229 s | **-4.456 s / -10.31%** |
| FancyMenu panorama | 3.858 s | 4.612 s | -0.754 s |
| MCEF init | 1.407 s | 1.896 s | -0.489 s |

All three candidate runs:

- emitted both skip markers;
- reached the semantic main-menu marker;
- reported zero BootOptim Mixin failures.

MCEF and panorama medians were also materially favorable in candidate. Therefore the full -7.176 s TTMM delta must **not** be attributed exclusively to renderer reconstruction. Even so, their combined ~1.24 s favorable movement does not explain the multi-second post-entrypoint/reload delta, and the key lifecycle fact is independent of timing noise: the exact title path completed three times without either renderer map being rebuilt.

**Conclusion:** eager renderer reconstruction is proven unnecessary for reaching the exact-pack title screen and is a valid multi-second lifecycle optimization target. This branch remains non-production because it never reconstructs the maps before process exit.

## Production-shaped follow-up

A real candidate must preserve the authoritative stock/NeoForge reload and all mod hooks:

1. retain the startup reload generation/resource manager;
2. suppress only the automatic initial invocation;
3. before the first real entity/block-entity renderer consumer, synchronously run the real dispatcher reload on the client/render thread;
4. serialize off-thread first consumers through the client executor;
5. never fake renderer-map contents or manually replay individual mod callbacks;
6. after initialization, use the ordinary maps and ordinary renderer methods;
7. let a newer reload supersede a still-pending older generation.

That implementation is pursued separately in PR #94.

## Remaining gate

PR #94 must prove that first-consumer forcing preserves the title win and can execute the full original reload successfully when forced. The final product decision additionally requires addressing any first-world hitch: moving a 5-12 s stall from startup into the first rendered frame is not sufficient by itself.
