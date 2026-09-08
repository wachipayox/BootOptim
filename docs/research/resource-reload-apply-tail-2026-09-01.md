# Resource reload apply-tail attribution — 2026-09-01

Status: **RESEARCH ONLY**. Base: `agent/integration-current` @ `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`. No production code is changed here.

This note follows `AGENTS.md`: preparation/listener-inclusive timings overlap and must not be summed. The values below are different: PR #47's barrier/turn tracer identifies ordered **post-turn** intervals after the global preparation gate, so the distinct intervals are serial critical-path work. Wall time is still not CPU time.

## Exact-pack observation

Slow laptop:

- 70 reload listeners
- all preparations: ~94,350.817 ms
- all done: ~179,100.562 ms
- `ModelManager` is the preparation gate: ~94,350.502 ms
- post-turn `ModelManager`: ~11,551 ms
- `BlockEntityRenderDispatcher`: ~5,316 ms
- `EntityRenderDispatcher`: ~6,839 ms
- `LevelRenderer`: ~6,507 ms
- final anonymous `Minecraft$...`, index 69: ~22,457 ms

Post-preparation tail is ~84.750 s. These five visible ordered intervals total 52.670 s, ~62.15% of that tail.

## Source graph and ceilings

### `ModelManager.apply` — 11.551 s absolute ceiling

Vanilla 1.21.1 plus exact NeoForge patch:

```text
ModelManager.apply(ReloadState)
  -> atlasPreparations.values().forEach(StitchResult::upload)
     -> TextureAtlas.upload(preparations)
        -> TextureUtil.prepareImage(...)             [GL texture allocation]
        -> each sprite.uploadFirstFrame()            [GL upload]
        -> animation ticker/list construction
  -> publish bakedRegistry/modelGroups/missingModel
  -> [NeoForge] ClientHooks.onModelBake(manager, bakedRegistry, modelBakery)
  -> blockModelShaper.replaceCache(modelCache)
```

The stock atlas-upload path consumes already-prepared `SpriteLoader.Preparations`; it does not need to re-open sprite resources. It is a mix of Java iteration and render-thread/native GL upload/driver work. `ClientHooks.onModelBake` is an arbitrary mod compatibility boundary and can add CPU, model lookups, IO, or other work.

**P0 dependency:** a hybrid-lazy block/item model design may shrink/change registry/cache publication, but it does not automatically remove atlas upload if the same sprite set is still prepared. PR #69 explicitly records that model laziness does not solve sprite load/decode. `onModelBake` is also a semantic gate because mods currently receive the eager baked map.

**Decision:** no implementation yet. First split `apply` into (1) per-atlas upload, (2) NeoForge `onModelBake`, (3) cache/publication. Do not move uploads off the render thread and do not build a redundant model optimization before P0 semantics are settled.

### `BlockEntityRenderDispatcher` — 5.316 s absolute ceiling

```text
onResourceManagerReload
  -> new BlockEntityRendererProvider.Context(... EntityModelSet, Font, renderers ...)
  -> BlockEntityRenderers.createEntityRenderers(context)
     -> PROVIDERS.forEach(type, provider)
        -> provider.create(context)
        -> often context.bakeLayer(layer)
           -> EntityModelSet.bakeLayer(layer)
              -> LayerDefinition.bakeRoot()
              -> new mutable ModelPart tree
```

Vanilla `BlockEntityRendererProvider.Context` does not expose `ResourceManager`. The intrinsic path is therefore primarily render-thread Java CPU/allocation and renderer reconstruction, not resource IO or GL. Custom providers/mixins can add other work.

`EntityModelSet.bakeLayer` rebuilds a `ModelPart` tree each call, but generic memoization of the returned object is unsafe: `ModelPart` is mutable render/animation state and consumers need instance isolation.

**P0 dependency:** separate entity-model system; not automatically removed by lazy block/item `ModelBakery`. A custom provider can consult other renderer/model services, so secondary dependence must be measured.

**Decision:** diagnostic only. Time each provider/type and aggregate `bakeLayer` by layer key. Do not assign a generic optimization until the dominant constructor/mechanism is known.

### `EntityRenderDispatcher` — 6.839 s absolute ceiling

Exact NeoForge 1.21.1 shape:

```text
onResourceManagerReload(ResourceManager rm)
  -> new EntityRendererProvider.Context(... rm, EntityModelSet, ...)
  -> EntityRenderers.createEntityRenderers(context)
     -> provider.create(context) for every registered entity renderer
  -> EntityRenderers.createPlayerRenderers(context)
  -> [NeoForge] EntityRenderersEvent.AddLayers(renderers, playerRenderers, context)
```

Unlike the block-entity context, `EntityRendererProvider.Context` exposes `ResourceManager`, `getModelManager()`, and `bakeLayer()`. The interval can therefore contain model-layer reconstruction, block/item model lookups, direct resource reads, renderer object construction, and arbitrary mod `AddLayers` work. Vanilla's dispatcher body has no unavoidable GL call; native/GL samples here should be attributed to a provider/mod callback.

The `AddLayers` event is a major compatibility barrier to lazy-per-type renderer construction: mods receive complete renderer maps and attach layers after construction.

**P0 dependency:** entity model layers are not top-level `ModelBakery` block/item models. P0 may reduce secondary model lookups in custom providers but does not remove renderer-map reconstruction or `AddLayers`.

**Decision:** diagnostic only. Measure per provider/player provider, aggregate `bakeLayer`, scoped resource reads, and `AddLayers` separately.

### `LevelRenderer` — 6.507 s absolute ceiling

1.21.1 reload shape:

```text
LevelRenderer.onResourceManagerReload
  -> initOutline()
     -> close old entityEffect
     -> new PostChain(..., minecraft:shaders/post/entity_outline.json)
     -> resize(window)
  -> if Minecraft.useShaderTransparency():
       initTransparency()
       -> close old transparency state
       -> new PostChain(..., minecraft:shaders/post/transparency.json)
       -> resize(window)
       -> publish named temporary render targets
```

`PostChain` performs real source work:

```text
PostChain.load
  -> resource read + Gson parse of post-chain JSON
  -> targets -> new TextureTarget(...)                 [GL/FBO allocation]
  -> passes -> new PostPass
     -> new EffectInstance
        -> read shaders/program/<name>.json
        -> read/compile vertex and fragment shader sources if uncached
        -> ProgramManager.createProgram/linkShader     [GL/driver]
        -> uniform/attribute lookup
  -> resize full-sized RenderTargets                  [GL allocation/resize]
```

This is mixed IO + Java parsing + GPU-driver shader/link/FBO work. The GL portion must stay on the render thread.

**P0 dependency:** independent of block/item model population. P0 does not remove it.

**Deferral:** this is the cleanest vanilla tail candidate. On initial startup there is no world before the title screen, while these chains are world-render effects. If a narrow split confirms the 6.507 s is in `PostChain` construction/resize, a startup-only lazy `ensureOutline/ensureTransparency` on first world-render demand can move most of it after title without moving GL off-thread. Keep in-world resource reload eager initially.

Compatibility proof must cover mods/mixins expecting non-null fields after reload, shader-load error timing, resource-pack state, and first-world-frame behavior.

**Decision:** implementation agent is justified only after one narrow diagnostic split: `initOutline`, `initTransparency`, `PostChain` constructor, `resize`, and optional shader compile/link aggregate.

### listener #69 anonymous — 22.457 s absolute ceiling

Evidence strongly identifies this with FancyMenu's resource preloader, but the new run should be correlated with the already-existing BootOptim marker before calling the identity proven for this run.

Historical exact-pack PR #57:

- final anonymous listener: ~2,576.803 ms
- FancyMenu/BootOptim `ResourcePreLoader.preLoadAll`: ~2,565.753 ms in the same execution

PR #39 verified FancyMenu 3.9.0 source: local PNG decoding is already asynchronous, but `preLoadCubicPanorama` called one face supplier then waited for it before starting the next. Production BootOptim now pre-launches the six existing face suppliers at panorama entry, preserving FancyMenu's original ordered get/wait/timeout/error loop. GPU texture registration remains lazy and is not moved off-thread.

```text
anonymous reload listener
  -> ResourcePreLoader.preLoadAll
     -> preload entries / panoramas in outer order
        -> preLoadCubicPanorama
           -> [BootOptim] launch six existing face suppliers
           -> FancyMenu original ordered waits/consumption
```

Therefore the render-thread wall interval is plausibly a wait boundary while worker threads perform file IO/PNG decode, not 22.457 s of render-thread CPU. The exact preload list can also contain resource-pack, web, audio or video entries, so the existing marker must be checked before attributing all 22.457 s to panorama PNGs.

**P0 dependency:** independent. P0 can reduce indirect machine contention, but it does not structurally remove this listener: these suppliers start only when FancyMenu reaches its apply turn.

**Residual candidate:** current BootOptim overlaps six faces within one panorama, but the outer sequence of panoramas remains serial. If the existing marker explains most of 22.457 s, investigate a wider **launch-only** prepass at `preLoadAll` that starts all safe/idempotent panorama PNG suppliers before FancyMenu's original ordered wait loop. Do not move the listener wholesale to a worker. Verify supplier idempotence, executor bounds, peak memory on 6 GiB, non-PNG entries, and visual/menu equivalence.

## Listener table

| listener | source work | IO | GL/render-thread-only | P0 hybrid-lazy | ceiling / action |
|---|---|---|---|---|---|
| `ModelManager.apply` ~11.551 s | atlas upload; publish model maps; NeoForge bake callback; cache swap | stock upload none; callback may read | **yes** atlas allocation/upload | map part may shrink; atlas remains if sprite set unchanged | 11.551 s; split upload/hook/cache first |
| `BlockEntityRenderDispatcher` ~5.316 s | reconstruct all BE renderers; mutable layer bake | not intrinsic to vanilla Context | no intrinsic GL | separate entity-model system | 5.316 s; per-provider diagnostic |
| `EntityRenderDispatcher` ~6.839 s | reconstruct entity/player renderers; layer bake; NeoForge `AddLayers` | **possible/direct** via Context/mods | no intrinsic GL | reconstruction/event remain | 6.839 s; providers + event diagnostic |
| `LevelRenderer` ~6.507 s | outline/fabulous post chains; shader/effect/targets | **yes** JSON/shader/aux resources | **yes** compile/link/FBO | independent | up to 6.507 s; strong startup-only defer candidate |
| anonymous #69 ~22.457 s | FancyMenu preload; historical near-1:1 `preLoadAll` correlation | **yes**, entry-specific; historically PNG files | panorama decode worker-side; no eager texture-registration requirement | independent | up to 22.457 s; top candidate if marker confirms |

## CPU interpretation

These are wall intervals, not CPU totals:

- `ModelManager.apply`: render-thread Java plus native/driver upload; possible driver stalls.
- block-entity dispatcher: mostly Java CPU/allocation unless custom provider adds IO/GL.
- entity dispatcher: Java CPU/allocation plus arbitrary resource/mod callback work.
- `LevelRenderer`: resource IO + JSON CPU + native shader/FBO driver work.
- FancyMenu final listener: likely substantial waiting on worker CPU/disk IO; confirm from JFR/thread samples.

Do not claim any wall interval as CPU saved without JFR/thread/process CPU evidence.

## Implementation priority

1. **FancyMenu #69, conditional:** if `BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD preload_ms` is close to 22.457 s, this is the largest clean non-P0 ceiling. Implementation brief: widen only supplier launch concurrency while preserving FancyMenu's original consumption/order/errors. If the marker is small, first attribute preload entry families.
2. **`LevelRenderer`:** narrow diagnostic, then startup-only lazy world post-chain creation if it explains most of 6.507 s. Do not defer in-world reload initially.
3. **Renderer dispatchers:** diagnostic only; find dominant provider / `AddLayers` before production work. Do not share mutable `ModelPart` roots.
4. **`ModelManager.apply`:** diagnostic only; separate atlas upload from NeoForge callback/cache. Atlas work belongs to the sprite/atlas campaign, and model-map work may overlap P0.

## Minimal next diagnostic

Low-cardinality only:

- `ModelManager.apply`: per-atlas upload; total `ClientHooks.onModelBake`; cache/publication.
- block-entity renderer creation: per provider/type; aggregate `bakeLayer` by layer key.
- entity renderer creation: per provider/player; `AddLayers`; `bakeLayer`; scoped resource reads.
- `LevelRenderer`: outline/transparency; `PostChain` ctor vs resize; optional aggregate shader compile/link.
- reuse the existing FancyMenu `preLoadAll` marker; add entry-family attribution only if it does not explain listener #69.

No arbitrary apply parallelism, no OpenGL off render thread, no new production behavior on this branch.

## Evidence

- `AGENTS.md`
- PR #47: `SimpleReloadInstance` barrier/turn critical-path profiler
- PR #39: FancyMenu 3.9.0 panorama preload source analysis
- PR #57: final anonymous listener / FancyMenu timing correlation
- PR #65: slow-machine startup/JFR campaign
- PR #69: resource/model decomposition and hybrid-lazy decision tree
- NeoForge `1.21.1`: exact `ModelManager.java.patch` and `EntityRenderDispatcher.java.patch`
- Mojang-mapped 1.21.1 source shape cross-checked for `ModelManager`, `AtlasSet`, `TextureAtlas`, renderer providers, `EntityModelSet`, `LevelRenderer`, `PostChain`, `PostPass`, and `EffectInstance`
