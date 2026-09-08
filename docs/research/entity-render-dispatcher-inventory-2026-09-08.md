# EntityRenderDispatcher / AddLayers exact-pack inventory — 2026-09-08

Status: **ACTIVE DIAGNOSTIC / NO BEHAVIOR CHANGE**

Base: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Question

PR #184 attributed **2153.135 ms exclusive hosted serial wall** to the initial `EntityRenderDispatcher` resource-reload slot. The slot is after the global preparation gate and is therefore real serial critical-path wall, but the old probe did not separate renderer providers from NeoForge `EntityRenderersEvent.AddLayers` subscribers.

This pass inventories the exact-pack path without moving callbacks, changing listener order, touching GL, or treating inclusive/task-sum timing as savings.

## Semantic boundary

PR #183 is retained as the structural model boundary. Its digest `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050` covers real bounded `BlockModel` parse, parent resolution, texture/material lookup and ordinary `ElementsModel` construction. This renderer diagnostic starts later and does not claim equivalence for sprite upload, baked render output, renderer callbacks or GL.

NeoForge patches `EntityRenderDispatcher.onResourceManagerReload` so that it constructs an `EntityRendererProvider.Context`, rebuilds the entity/player renderer maps, publishes them to dispatcher fields, and then posts `EntityRenderersEvent.AddLayers` with those maps and the same context. `ModLoader.postEvent` walks event priorities and mod containers in order. The NeoForge event bus then invokes its listener array sequentially. Those are compatibility/publication boundaries, not presumed false dependencies.

## Probe

Opt-in only:

```text
-Dboot_optim.profileEntityRendererReload=true
```

Markers:

```text
BOOTOPTIM_ENTITY_RENDER_RELOAD
BOOTOPTIM_ENTITY_RENDER_PROVIDER
BOOTOPTIM_ENTITY_RENDER_ROW
```

The diagnostic:

- snapshots the registered `EntityRenderers.PROVIDERS` and `PLAYER_PROVIDERS` maps without changing them, including provider class and code-source origin;
- measures wall/current-thread CPU for stock `createEntityRenderers`, stock `createPlayerRenderers`, the stock `AddLayers` post, and each existing `EventListener.invoke` while processing `AddLayers`;
- counts accesses through `EntityRendererProvider.Context` to `ResourceManager`, `ModelManager`, `EntityModelSet`, `bakeLayer`, dispatcher/item/block/font dependencies, attributed to the currently measured provider phase or subscriber;
- buffers rows in memory and logs them only when `EntityRenderDispatcher.onResourceManagerReload` returns, keeping formatting/log I/O out of measured subscriber/provider scopes;
- calls every original provider/listener exactly once on the same thread and in the same order; exceptions are not swallowed or transformed.

The probe deliberately does **not** intercept RenderSystem/OpenGL/shader calls. GL classification must come from source/bytecode evidence or be marked unknown; absence of a GL probe is not evidence of purity.

## Classification rules

Each exact-pack piece is classified conservatively:

- **GL-bound**: source/bytecode proves render-thread/GL/native graphics calls. Never move it off-thread.
- **mutable/callback**: an `AddLayers` listener or publication step that mutates renderer/layer maps or arbitrary mod state. Timing alone cannot make it preparable.
- **ModelManager/resource dependent**: dynamic Context access to `getResourceManager`, `getModelManager`, `getModelSet` or `bakeLayer`, or equivalent source evidence. It cannot observe pre-commit/old model state.
- **pure/preparable candidate**: only when the exact provider/subscriber has bounded immutable inputs, no resource/model/GL access, no publication/mutable side effects, and a separate prepare result can be committed later in stock order.
- **unknown**: missing source/bytecode evidence. Unknown is not treated as pure.

A prepare/commit candidate is allowed only for a small positively proven subset. Generic provider or `AddLayers` parallelism is explicitly out of scope.

## Validation gate

1. `./gradlew build` must pass.
2. Generic startup smoke must reach the menu without new BootOptim/Mixin failures.
3. Hosted exact-pack smoke with the property enabled must preserve the pinned fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`, resource selection and normal atlas/menu behavior, and emit non-empty provider/subscriber rows.
4. Only after the inventory proves a bounded pure subset may a separate default-off fail-open prepare/commit candidate be implemented. That candidate must preserve #183 probes/digest where relevant and run hosted exact-pack A/B 3x before any laptop request.

## Evidence links

- PR #47: scheduler/barrier critical-path method; listener-inclusive times must not be summed.
- PR #85: render-thread/GL boundary example for LevelRenderer/post chains.
- PR #138/#141: aggregate reload boundary and hardware-sensitive resource attribution rules.
- PR #183: real-class model replay and semantic digest boundary.
- PR #184: exclusive reload-listener serial-tail attribution; `EntityRenderDispatcher` = 2153.135 ms hosted exclusive slot.

Runtime results and final disposition will be appended only from a valid exact-pack run.
