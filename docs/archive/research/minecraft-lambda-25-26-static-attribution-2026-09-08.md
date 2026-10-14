# Static attribution of `Minecraft$$Lambda` reload slots 25 and 26 (2026-09-08)

Status: **resolved statically; diagnostic-only no-go for scheduling changes**.

This note resolves the two anonymous `net.minecraft.client.Minecraft$$Lambda` listeners measured by PR #184 without depending on a runtime ownership probe. The proof is a join of the exact-pack listener order, the vanilla/NeoForge 1.21.1 constructor callsite, and the exact More Culling 1.0.8 source tag used by the pack.

## Evidence boundary

BootOptim authority for this investigation is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

The timing evidence is PR #184 (`93d59a14fbfc337c4eaf29dec19e1c47d838c03a` runtime diagnostic, `16d383bd2bf45982808129d880a3ee69b53d77bc` docs), hosted exact-pack run `34175380705`. The exact-pack artifact reports, in registration/apply order:

- 24: `net.minecraft.client.renderer.GameRenderer$1`
- 25: `net.minecraft.client.Minecraft$$Lambda/...` — exclusive post-turn slot `3.590560 s`
- 26: `net.minecraft.client.Minecraft$$Lambda/...` — exclusive post-turn slot `0.852055 s`
- 27: `malte0811.ferritecore.impl.Deduplicator$1`
- 28: `net.minecraft.client.renderer.LevelRenderer`

The two lambda slots therefore contribute `4.442615 s` of the `13.5578 s` serial tail after the global preparation gate. As in #47/#184, these are exclusive ordered slots, not inclusive listener task sums. Excluding the separate FancyMenu-correlated final listener, #184 measured an external serial tail of `8.921 s`.

The resource/model semantic boundary comes from #183: real `BlockModel`/NeoForge replay digest `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`, Actions `34173324041`, exact fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`. That replay covers parse/parent/material structural work but not sprite upload, `FaceBakery`, final baked output, arbitrary callbacks, or GL. Nothing below attributes the two post-gate slots to #183's parse/parents/material work.

## Static callsite proof

Vanilla Minecraft 1.21.1 constructs and registers the `GameRenderer` shader reloader, then immediately constructs `LevelRenderer`; there are no vanilla reload listeners between those operations. NeoForge 1.21.1's `Minecraft.java.patch` likewise adds no reload listener in this gap (it only posts the render-level stage registration event around `LevelRenderer`).

Exact More Culling 1.0.8 is tagged `v1.0.8` at commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`. Its common client mixin configuration enables `Minecraft_managersMixin`. That mixin injects into `Minecraft.<init>` at exactly:

`NEW net/minecraft/client/renderer/LevelRenderer`, `shift = BEFORE`.

The injected handler `moreculling$onBlockRenderManagerInitialized` registers **exactly two consecutive `ResourceManagerReloadListener` lambdas** before `LevelRenderer` is constructed:

1. iterate `Block.BLOCK_STATE_REGISTRY` and call `StateCullingShapeCache.moreculling$initShapeCache()` for every state;
2. iterate `blockRenderManager.getBlockModelShaper().modelByStateCache` and call `BakedOpacity.moreculling$resetTranslucencyCache(state)` for every non-occluding state.

This is a one-to-one structural match with #184's two consecutive `Minecraft$$Lambda` objects between the GameRenderer reloader and the next named listeners before `LevelRenderer`. Because the lambda callsites are merged into target class `Minecraft` by Mixin, the generated runtime lambda classes are owned by `net.minecraft.client.Minecraft`, explaining the otherwise misleading `Minecraft$$Lambda` label.

### Synthetic methods

The exact v1.0.8 source has four javac lambda bodies in this injected handler (two outer reload listeners plus their inner collection callbacks). Standard javac numbering for this source shape is reverse-emitted as `$3`, `$2`, `$1`, `$0`:

- first registered reload listener (slot 25): source synthetic outer body `lambda$moreculling$onBlockRenderManagerInitialized$1(ResourceManager)`; nested state consumer `lambda$moreculling$onBlockRenderManagerInitialized$0(BlockState)`;
- second registered reload listener (slot 26): source synthetic outer body `lambda$moreculling$onBlockRenderManagerInitialized$3(ResourceManager)`; nested map `BiConsumer` `lambda$moreculling$onBlockRenderManagerInitialized$2(BlockState, BakedModel)`.

Mixin may unique-rename private synthetic methods when merging them into `Minecraft`; therefore the stable attribution key is the original synthetic suffix plus the injected callsite, not an environment-specific unique prefix. A public More Culling stack trace independently demonstrates this exact transformation for the second listener: transformed `Minecraft` methods include a unique-renamed form of `lambda$moreculling$onBlockRenderManagerInitialized$3` calling a unique-renamed `$2` body. No dynamic BootOptim probe is needed to establish ownership.

For binary reproduction, download the exact `moreculling-neoforge-1.21.1-1.0.8.jar` (Modrinth version `tFPgktUw`) and run:

```text
javap -p -c ca.fxco.moreculling.mixin.Minecraft_managersMixin
```

Then inspect the four synthetic methods above and the two `invokedynamic` callsites in `moreculling$onBlockRenderManagerInitialized`. The source tag itself is sufficient to reproduce the semantic mapping.

## Lambda → work → dependency classification

| #184 slot | More Culling source body / synthetic method | Work | Reads / writes and lifecycle dependencies | GL / resource manager | Classification |
|---|---|---|---|---|---|
| 25 (`3.590560 s`) | first listener; outer `$1(ResourceManager)`, inner `$0(BlockState)` | `Block.BLOCK_STATE_REGISTRY.forEach(...)`; derive each state's culling-face shapes | Reads the global block-state registry; for non-occluding states reads `MoreCulling.blockRenderManager` → `BlockModelShaper` → newly published baked model; may read the model's More Culling cull shape; otherwise virtual-calls `Block.getOcclusionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`. Writes each state's mutable `moreculling$cullingShapesByFace`. | `ResourceManager` argument is unused. No direct `RenderSystem`, OpenGL, texture upload, or other GL call. | **CPU-heavy but lifecycle-mutable; NO-GO for pre-barrier/off-thread relocation.** It consumes current baked-model publication and mutates globally observable block-state caches; virtual block shape dispatch can enter mod code. |
| 26 (`0.852055 s`) | second listener; outer `$3(ResourceManager)`, inner `$2(BlockState,BakedModel)` | iterate `BlockModelShaper.modelByStateCache`; for non-occluding states scan baked quads/sprite opacity and reset translucency/empty-face caches | Requires `MoreCulling.blockRenderManager` and the newly published `BlockModelShaper` model map. `SimpleBakedModel_cacheMixin` walks culled/unculled `BakedQuad`s, obtains the sprite's unmipmapped `NativeImage`, computes UV bounds and scans translucency, then writes mutable More Culling fields on `BlockState` (`hasQuadsOnSide`, `hasTextureTranslucency`). Other baked-model implementations may override the API. | `ResourceManager` argument is unused. `NativeImage` is CPU/native-memory image access, not a GL call; no direct `RenderSystem`/OpenGL API is present. | **CPU-heavy, GL-free in the inspected implementation, but model/sprite-publication dependent and mutating; NO-GO for pre-barrier/off-thread relocation.** |

## Why both execute after the preparation gate

`ResourceManagerReloadListener` is a synchronous/apply-only listener. Its default `reload(...)` waits on `PreparationBarrier.wait(Unit.INSTANCE)` and then invokes `onResourceManagerReload(resourceManager)` on the apply executor. Thus both More Culling lambdas are intentionally post-barrier apply work. Their unused `ResourceManager` parameter does **not** make them preparation-safe.

The ordering matters:

1. `ModelManager` is listener 12 in #184 and publishes the reload's baked-model state during apply.
2. More Culling slot 25 later consumes `blockRenderManager.getBlockModel(...)` for non-occluding states and publishes per-state culling-shape caches.
3. More Culling slot 26 consumes `BlockModelShaper.modelByStateCache` and baked sprite/image data, then publishes opacity/quads caches on the same block states.
4. `LevelRenderer` follows later in the listener list and can render using those caches.

Running either listener before the global preparation barrier would allow it to observe the previous model map/sprites or mutate caches before the new model state is committed. That violates the project's reload-equivalence requirement even though the bodies contain no explicit GL calls.

## Relationship to #183

Neither slot repeats the `BlockModel` JSON parse, parent resolution, or material traversal measured/replayed by #183.

- Slot 25 consumes already constructed/published baked-model/cull-shape state and derives per-`BlockState` face shapes.
- Slot 26 is later baked-output/sprite-image work: it walks `BakedQuad`s and pixel-backed `NativeImage` data to compute translucency. This is explicitly outside #183's structural replay boundary.

Therefore these `4.442615 s` are a distinct post-gate More Culling cache-building cost, not hidden ModelManager parse/parent/material time.

## Decision

**No runtime candidate is prepared in this PR.** Both lambdas are now statically attributed, but neither satisfies the project's conditions for safe pre-barrier preparation:

- both mutate globally reachable cache fields;
- both rely on the new reload's model publication, directly or transitively;
- slot 25 has virtual block-shape dispatch into modded block implementations;
- slot 26 reads baked sprite/`NativeImage` state and invokes model API implementations;
- their apply ordering before renderer consumers is semantically meaningful.

The absence of direct GL calls is insufficient to justify scheduling changes. Moving them to a worker or starting them before ModelManager commit would risk old/new-state mixing and races.

A future optimization must be **algorithmic within the same apply/lifecycle boundary**, or split into a demonstrably immutable snapshot prepared only after the relevant ModelManager/baked-sprite publication. Before any such change, use a diagnostic-only probe that breaks slot 25 down by registry state / fallback-vs-model cull-shape work and slot 26 down by baked-model implementation / quad / pixel scan, without changing execution order. Any candidate then needs exact-pack equivalence plus 3× A/B menu timing; #184's method/slot time alone is not sufficient evidence of startup-wall reduction.

## Reproducible references

BootOptim:

- #47: listener inclusive time is not critical-wall attribution; ordered post-turn accounting is required.
- #138 and #141: existing resource/model boundary diagnostics; no behavior change inferred from boundary timing alone.
- #183: real `BlockModel`/NeoForge replay, Actions `34173324041`, replay digest `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`.
- #184: critical-path slot diagnostic, exact-pack run `34175380705`; runtime commit `93d59a14fbfc337c4eaf29dec19e1c47d838c03a`, docs `16d383bd2bf45982808129d880a3ee69b53d77bc`.
- exact fixture SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

Minecraft / NeoForge / More Culling:

- Minecraft 1.21.1 constructor source (public deobfuscated mirror): `net.minecraft.client.Minecraft`, GameRenderer reload listener immediately before `LevelRenderer` construction.
- NeoForge 1.21.1: `patches/net/minecraft/client/Minecraft.java.patch`; no extra reload listener is inserted in that gap.
- Minecraft `ResourceManagerReloadListener`: default reload waits on the preparation barrier, then runs on the apply executor.
- More Culling tag `v1.0.8`: commit `6cbb3ca33b78ca291a3af7e065a0d3aa74e4c682`.
- More Culling exact source: `common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java`.
- mixin enablement: `common/src/main/resources/moreculling.mixins.json` includes `Minecraft_managersMixin` in the client list.
- slot 25 implementation: `models/cullshape/BlockStateBase_cullShapeMixin.java`.
- slot 26 model implementation example: `models/SimpleBakedModel_cacheMixin.java`; sprite image bridge: `TextureAtlasSprite_opacityMixin.java`.
- model map accessor: `accessors/BlockModelShaperAccessor.java` → `modelByStateCache`.
- More Culling NeoForge 1.0.8 binary identity: Modrinth `tFPgktUw`, `moreculling-neoforge-1.21.1-1.0.8.jar`.
