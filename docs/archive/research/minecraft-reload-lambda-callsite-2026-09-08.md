# Minecraft reload lambda callsite mapping — 2026-09-08

Status: diagnostic result, not an optimization.

Base integration: `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
PR: #189. Probe property: `-Dboot_optim.profileMinecraftReloadLambdaIdentity=true`.
Exact-pack fixture SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

## Boundary

PR #184 measured ordered, monotonic exclusive apply slots after the preparation gate. It reported index 25 = 3590.560 ms and index 26 = 852.055 ms, both anonymously named `net.minecraft.client.Minecraft$$Lambda/...`. Those durations are retained evidence from #184; this probe does not time listener bodies. PR #183 remains the real-class replay boundary for ordinary BlockModel parse/parent/material work and that work is not attributed to these listeners.

## Probe

`ReloadableResourceManagerLambdaIdentityMixin` observes only two low-frequency points:

1. `registerReloadListener`: when and only when the listener stable class starts with `net.minecraft.client.Minecraft$$Lambda`, capture one registration stack, object identity, source frame and non-static captured-field *types*.
2. `createReload`: enumerate NeoForge's final sorted `listeners` list and correlate the exact same object identity with its final index.

No listener is wrapped or replaced. No future, executor, barrier, scheduling, GL/render-thread operation, callback or resource order is changed. Disabled, `createReload` returns immediately and each registration reaches only the static `ENABLED`/class checks. Enabled, the only stack captures occur once per matching registration. The probe also does not sample stacks while listener work is running.

Marker: `BOOTOPTIM_RELOAD_LAMBDA_ID`.

## Hosted exact-pack result — VM 1

Actions: `34213276434`, artifact `10050805614` (`exact-pack-result-smoke-1`, artifact ZIP digest `sha256:52cb6132c357d3328c4eee8c73658bd1e685788e64eaf8d07d7e9fa84ac2334b`). Build `34213276588` and generic startup smoke `34213276428` both passed. Exact-pack smoke reached the menu (`main_menu_ms=69225`, process-origin smoke metric) with `bootoptim_mixin_errors=0`.

The exact final listener list had 72 entries and exactly two `Minecraft$$Lambda` objects. Both registration identities matched the final ordered objects:

- index 25: identity `812613429`; registration callsite `net.minecraft.client.Minecraft.handler$fca000$moreculling$onBlockRenderManagerInitialized(Minecraft.java:30046)`; empty captured-field set.
- index 26: identity `534904312`; registration callsite `net.minecraft.client.Minecraft.handler$fca000$moreculling$onBlockRenderManagerInitialized(Minecraft.java:30050)`; empty captured-field set.

Probe self-overhead for the only two stack/field-type captures was **701.623 us total** (`657.218 us`, `44.405 us`). This is instrumentation self-time, not startup A/B evidence.

## Fresh-VM stability — VM 2

Actions: `34214012310`, artifact `10051105667` (`exact-pack-result-smoke-1`, artifact ZIP digest `sha256:ed9ed2967a5f6f7ae0bd2f22d4441f0ee67842c77872753c2723f5182eea554f`). Build `34214012354` and generic startup smoke `34214012292` both passed. Exact-pack smoke reached the menu (`main_menu_ms=90429`, process-origin smoke metric) with `bootoptim_mixin_errors=0`.

The second hosted VM again had 72 final listeners and exactly two `Minecraft$$Lambda` objects:

- index 25: identity `1773773050`; same method and transformed callsite line `30046`; `registration_match=true`.
- index 26: identity `557264167`; same method and transformed callsite line `30050`; `registration_match=true`.

The identities changed between processes as expected, while index, list size, class owner, injected handler and transformed callsite line stayed identical. Probe registration capture self-time was **725.418 us total** (`683.780 us`, `41.638 us`). The mapping is therefore stable across the two fresh hosted VMs tested.

The exact-pack log identifies `More Culling 1.0.8 (moreculling)`. These are not vanilla Minecraft-owned listener bodies: More Culling injects its handler into `Minecraft`, so the JVM names the generated hidden listener classes `net.minecraft.client.Minecraft$$Lambda/...`.

## Exact More Culling 1.0.8 source mapping

The release actually present in the pack is upstream tag `v1.0.8` (`moreculling-neoforge-1.21.1-1.0.8`). In that tag, `common/src/main/java/ca/fxco/moreculling/mixin/Minecraft_managersMixin.java` injects `moreculling$onBlockRenderManagerInitialized` into `Minecraft.<init>` immediately before construction of `LevelRenderer`. The handler assigns `MoreCulling.blockRenderManager` and registers exactly two consecutive `ResourceManagerReloadListener` lambdas in the same order observed by the probe.

Both lambdas are non-capturing (`captured_field_types=""`); their dependencies are reached through static/global state and the `BlockState`/model objects they traverse. Both ignore the `ResourceManager` lambda argument.

### Index 25 — block-state culling-shape cache initialization

Exact v1.0.8 listener body:

`Block.BLOCK_STATE_REGISTRY.forEach(state -> ((StateCullingShapeCache) state).moreculling$initShapeCache())`

`moreculling$initShapeCache()` is implemented by More Culling's `BlockStateBase_cullShapeMixin` and mutates the per-`BlockState` field `moreculling$cullingShapesByFace`.

Dependencies/touches:

- iterates the global `Block.BLOCK_STATE_REGISTRY`;
- for non-occluding states, reads `MoreCulling.blockRenderManager`, calls `BlockRenderDispatcher.getBlockModel(state)`, then asks the baked model through `BakedOpacity.moreculling$getCullingShape(state)`;
- otherwise/fallback calls the block's `getOcclusionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`;
- classifies empty/full/partial voxel shapes; partial shapes call `VoxelShape.getFaceShape` for every direction;
- publishes the result by writing More Culling-owned mutable state on each `BlockState`;
- does not read resources from `ResourceManager` and contains no GL call;
- is downstream of baked-model availability for its non-occluding-model path, but is not BlockModel JSON parse/parent/material resolution from #183.

Classification: **(b) mutable callback/cache work from a mod**, not (c). It invokes block/model behavior across the modded registry and publishes mutable per-state cache arrays. This PR does not authorize parallel preparation or moving it across the baked-model publication boundary.

### Index 26 — baked-model quad/texture translucency cache recomputation

Exact v1.0.8 listener body:

`((BlockModelShaperAccessor) blockRenderManager.getBlockModelShaper()).getModels().forEach((state, model) -> { if (!state.canOcclude()) ((BakedOpacity) model).moreculling$resetTranslucencyCache(state); })`

The map is the published `BlockModelShaper` block-state → baked-model map. `BakedOpacity.moreculling$resetTranslucencyCache` has a default no-op, but More Culling supplies concrete implementations for its supported baked model classes. Those implementations publish results through More Culling's mutable fields on `BlockState` (`moreculling$emptyFaces` and `moreculling$hasTextureTranslucency`).

Concrete dependencies include:

- `SimpleBakedModel`: walks culled/unculled `BakedQuad` lists; for each relevant quad it reaches the sprite's More Culling `SpriteOpacity`, obtains the unmipmapped `NativeImage`, computes UV bounds, tests texture translucency, and writes the state flags;
- `MultiPartBakedModel` and `WeightedBakedModel`: for each direction call More Culling's platform `getQuads(...)` using `EmptyBlockGetter.INSTANCE`/`BlockPos.ZERO`, inspect quad texture translucency, then write the same state flags;
- reads baked model and sprite/image CPU-side data but contains no direct GL upload/render call and does not use the supplied `ResourceManager`;
- is semantically after baked-model publication and can invoke model/platform behavior contributed by mods.

Classification: **(b) mutable callback/cache work from a mod**, not (c). The name says "reset cache", but the relevant v1.0.8 implementations recompute and publish per-state culling/translucency flags from live baked models/quads/sprite pixels; it is not a detached pure parse step.

Historical upstream issue `FxMorin/MoreCulling#414` independently records a failure inside the same second reload-handler family when baked-model availability was inconsistent. That is supporting evidence that its ordering relative to model publication is semantically meaningful, not permission to reschedule it.

## Metrics and interpretation

- #184 index 25: **3590.560 ms**, monotonic exclusive apply-slot time from the earlier profiler.
- #184 index 26: **852.055 ms**, monotonic exclusive apply-slot time from the earlier profiler.
- Probe VM1: **701.623 us** total registration-capture self-time.
- Probe VM2: **725.418 us** total registration-capture self-time.
- Exact list identity in both fresh VMs: `list_size=72`, exactly two `Minecraft$$Lambda`, indices 25 and 26, `registration_match=true`.
- Menu timings from the two smokes are process-origin smoke health metrics only; they are deliberately not compared as an optimization A/B.

## Decision / next gate

The anonymity is resolved: #184 indices 25/26 are the two More Culling 1.0.8 cache-maintenance listeners injected into `Minecraft`, not unexplained vanilla work and not the BlockModel parse/parents/material domain closed by #183.

Both are category **(b)**. Neither is category (a) because no direct GL/render operation is in the bodies; neither is category (c) because both traverse live modded state/model objects and publish mutable cache state; neither remains category (d) after the two stable mappings and exact-release source match.

There is therefore **no prepare/commit candidate authorized by this PR**. A separate candidate may open only after a More Culling-specific proof establishes a detached immutable computation boundary for one of these caches, with an original-thread commit that preserves mod-overridable behavior, baked-model publication ordering and atomic visibility of the per-state fields. Until that gate is met, do not change listener scheduling, futures, barriers, executors, GL/render-thread work or callback order.
