# Renderer reload first-consumer defer ceiling — 2026-09-04

Status: **EXPERIMENTAL CEILING / DO NOT MERGE**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Premise

PR #92 narrowed the initial resource-reload renderer tail on the hosted exact pack:

- `BlockEntityRenderDispatcher`: ~388 ms, with ~387 ms inside `BlockEntityRenderers.createEntityRenderers`;
- `EntityRenderDispatcher`: ~2,681 ms, with ~2,603 ms inside `EntityRenderers.createEntityRenderers`, ~62 ms in player renderer construction, and only ~16 ms after creation;
- `EntityModelSet.bakeLayer` itself was only ~19.6 ms for entities and ~19.1 ms for block entities; repeated layer keys accounted for only single-digit milliseconds, rejecting a layer-result reuse/cache direction.

The older slow-laptop ordered post-turn trace in PR #75 observed much larger serial ceilings: ~5.316 s block entity and ~6.839 s entity. These callbacks execute after the global preparation barrier and therefore extend time-to-menu directly.

The structural question is now whether the title screen needs these world renderer maps at all. If not, eager creation is lifecycle work placed unnecessarily on the pre-menu critical path.

## Ceiling experiment

Property:

```text
-Dboot_optim.experimentRendererReloadDeferCeiling=true
```

On the first `onResourceManagerReload` callback only, the diagnostic cancels:

- `EntityRenderDispatcher.onResourceManagerReload`;
- `BlockEntityRenderDispatcher.onResourceManagerReload`.

It does not implement a fallback or claim production correctness. This deliberately leaves the startup renderer maps in their pre-reload state so that any genuine pre-title consumer can fail naturally. Subsequent reload calls remain stock.

Markers:

```text
BOOTOPTIM_RENDERER_RELOAD_DEFER_CEILING dispatcher=entity status=skipped
BOOTOPTIM_RENDERER_RELOAD_DEFER_CEILING dispatcher=block_entity status=skipped
```

## Why this is a ceiling, not the final design

A production candidate must preserve the authoritative stock/NeoForge reload and all mod hooks. The intended architecture, only if this ceiling is positive, is first-consumer deferral:

1. retain the current reload generation/resource manager;
2. suppress only the automatic startup invocation;
3. before the first real entity/block-entity renderer consumer, synchronously run the real dispatcher reload on the Render thread;
4. serialize concurrent first consumers;
5. never fake renderer-map contents or manually replay individual mod callbacks;
6. after initialization, use the ordinary maps and ordinary renderer methods;
7. manual/subsequent resource reloads remain stock unless a later generation-safe design is separately proven.

Known compatibility risks include title-screen mods that render entities, direct renderer-map accessors that bypass `getRenderer`, `BlockEntityWithoutLevelRenderer`/special item paths, and mods whose renderer construction has observable side effects before any entity is rendered. Reaching the title is therefore only the first gate; a production candidate also needs a real world-render smoke and reload-generation validation.

## Gate

Hosted exact-pack A/B compares the skip ceiling with stock on the same branch. A useful result requires:

- both skip markers in candidate runs;
- semantic main-menu marker with zero BootOptim Mixin failures;
- a TTMM/reload improvement in the expected direction, without merely moving work into another pre-title listener;
- no renderer-map consumer failure before title.

If that passes, the next branch implements real first-consumer forcing and validates world entry/entity rendering before any laptop promotion decision.
