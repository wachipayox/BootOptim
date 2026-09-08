# EntityRenderDispatcher / AddLayers exact-pack inventory — 2026-09-08

Status: **COMPLETE / DIAGNOSTIC NO-GO / DO NOT MERGE**

Base: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.
Validated runtime-diagnostic head: `2c806e065689bf29e414d7e81109f56aa52f3c2e`.
PR: https://github.com/wachipayox/BootOptim/pull/190

## Decision

Do **not** implement generic provider parallelism, move `EntityRenderersEvent.AddLayers`, or introduce a `prepare/commit` candidate from this evidence.

The exact-pack `EntityRenderDispatcher` slot is overwhelmingly CPU work on the Render thread, but the expensive provider loop is not a proven pure phase: it constructs renderer/model state against the current `EntityModelSet`, includes explicit `ModelManager`/`ResourceManager` consumers, and is wrapped by Entity Model Features 3.2.4 with mutable reload-local/global state. `AddLayers` is a real mutable callback/publication boundary, but the entire event is only ~18 ms in the valid hosted run.

Therefore this PR should close diagnostic-only and remain a reopening basis. No laptop run and no 3x optimization A/B are justified because there is no behavior-changing candidate.

## Question and boundary

PR #184 attributed **2153.135 ms exclusive hosted serial wall** to the initial `EntityRenderDispatcher` resource-reload slot. The same trace left **13.5578 s** of exclusive serial tail after preparation, or **8.921 s** after excluding FancyMenu. The purpose here was to determine whether the renderer slot contains a bounded CPU-only subset that can prepare before the barrier while preserving stock publication order.

PR #183 remains the structural-model boundary. Its digest `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050` covers real `BlockModel` parse, parent resolution, texture/material lookup and ordinary `ElementsModel` construction. Renderer reconstruction happens later; this diagnostic does not reinterpret #183 as equivalence for renderer instances, model layers, callbacks, baked render state or GL.

## Stock callsites and publication order

The NeoForge 1.21.1 path is mechanically:

1. `EntityRenderDispatcher.onResourceManagerReload(ResourceManager)` builds one `EntityRendererProvider.Context` containing the dispatcher, item/block/in-hand renderers, current `ResourceManager`, current `EntityModelSet`, and font.
2. `EntityRenderers.createEntityRenderers(context)` iterates `PROVIDERS` and calls every `EntityRendererProvider.create(context)`.
3. The result is published to the dispatcher's entity-renderer map.
4. `EntityRenderers.createPlayerRenderers(context)` creates/publishes player renderers.
5. NeoForge posts `EntityRenderersEvent.AddLayers` with those current maps and the same context.
6. `ModLoader.postEvent` dispatches by `EventPriority` and mod-container order; each mod EventBus invokes its registered listeners synchronously.

`AddLayers` exposes the current renderer instances and model context through `getSkin`, `getRenderer`, `getEntityModels` and `getContext`. Those are publication/mutation boundaries, not presumed false dependencies.

## Final diagnostic probe

Opt-in only:

```text
-Dboot_optim.profileEntityRendererReload=true
```

Markers:

```text
BOOTOPTIM_ENTITY_RENDER_RELOAD
BOOTOPTIM_ENTITY_RENDER_PROVIDER
BOOTOPTIM_ENTITY_RENDER_SUBSCRIBER
BOOTOPTIM_ENTITY_RENDER_ACCESS
BOOTOPTIM_ENTITY_RENDER_ROW
```

The final probe does not transform EventBus and does not invoke callbacks itself. It:

- snapshots every registered entity/player provider with registry key, runtime provider class and code-source origin;
- times the stock aggregate entity-provider, player-provider and `AddLayers` phases with wall/current-thread CPU;
- counts `EntityRendererProvider.Context` accesses and attributes sensitive `ResourceManager`, `ModelManager`, `EntityModelSet` and `bakeLayer` uses by caller;
- instruments only public `AddLayers` getters to show which callback asks for current renderer/model state;
- after the dispatcher wall has already been captured, reflectively reads each mod EventBus raw listener list to enumerate the exact listener identities in stock phase/mod order; it does not call any listener;
- buffers output and logs after the measured dispatcher work.

An earlier attempt to transform the already-loaded EventBus produced no per-listener rows and was removed. The final read-only design avoids that classloading assumption. Individual `AddLayers` callback timing was intentionally not reintroduced because the valid run proves the whole callback ceiling is only 18.441 ms; transforming dispatch solely to split that tiny ceiling adds observer/compatibility risk without changing the decision.

The probe deliberately does **not** instrument RenderSystem/OpenGL/shader calls. A callback running on `Render thread` is not classified as GL-bound merely for that reason.

## Valid exact-pack result

Exact-pack Actions: https://github.com/wachipayox/BootOptim/actions/runs/34215684725

- result artifact `10051790637`, `sha256:e83c6b7f7e642c66679ecba3ffb54f79b31aedbd7d56b3e92c6ee8745539eced`;
- summary artifact `10051797700`, `sha256:a28f6322d153ac7830657e880d2e8812830655d74612a612ee19d40867996089`;
- pinned fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`: verified;
- resource selection: valid, zero issues, one reload;
- blocks atlas: `8192 x 8192`, 2 levels;
- BootOptim Mixin failures: 0;
- process-origin main menu: **93.198 s**;
- reload -> FancyMenu finished: **43.792 s**.

This was a diagnostic smoke, not an optimization A/B.

### Wall / CPU / critical-path decomposition

| Piece | Wall ms | Render-thread CPU ms | Share of dispatcher wall | Classification |
| --- | ---: | ---: | ---: | --- |
| `EntityRenderDispatcher` total | 2163.293 | 2115.694 | 100% | exclusive serial-slot family; Render-thread execution |
| `createEntityRenderers` | 2086.163 | 2038.738 | 96.435% | CPU-heavy; model/mutable-state dependent; **not proven GL-bound** |
| `createPlayerRenderers` | 54.262 | 54.237 | 2.508% | CPU-heavy; `ModelManager`/model-set dependent |
| `AddLayers` post | 18.441 | 18.445 | 0.852% | mutable mod-callback/publication boundary |
| residual | 4.427 | ~4.274 | 0.205% | context/publication bookkeeping |

Total CPU/wall is ~97.8%; entity-provider CPU/wall is ~97.7%. That is evidence for a CPU-heavy Render-thread slot, **not** evidence that the work is thread-safe or pure. The 2163.293 ms diagnostic total is close to #184's 2153.135 ms exclusive slot; the ~10 ms difference is not treated as a speed change.

No material GL/native wait is demonstrated inside this slot. The assignment's known shader/Veil/Flywheel/LevelRenderer GL/program paths remain separate #184 slots and are not touched here.

## Exact provider inventory

The valid run emitted **184 `BOOTOPTIM_ENTITY_RENDER_PROVIDER` rows**: 182 entity providers plus 2 player providers. Each row in artifact `10051790637` contains the exact entity key, provider runtime class and JAR origin.

Entity provider namespace counts:

| Namespace | Count |
| --- | ---: |
| `minecraft` | 129 |
| `tfmg` | 11 |
| `create` | 9 |
| `create_sa` | 9 |
| `securitycraft` | 6 |
| `simulated` | 3 |
| `exposure` | 3 |
| `aeronautics` | 2 |
| `xercapaint` | 2 |
| `bits_n_bobs` | 1 |
| `buildersdelight` | 1 |
| `corpse` | 1 |
| `decocraft` | 1 |
| `farmersdelight` | 1 |
| `furnish` | 1 |
| `offroad` | 1 |
| `xercamusic` | 1 |

The 53 non-vanilla entity keys are:

- Create/Registrate family: `aeronautics:gust`, `aeronautics:propeller_bearing_contraption`, `bits_n_bobs:inert_stationary_contraption`, `create:carriage_contraption`, `create:contraption`, `create:crafting_blueprint`, `create:gantry_contraption`, `create:package`, `create:potato_projectile`, `create:seat`, `create:stationary_contraption`, `create:super_glue`, `offroad:borehead_contraption_entity`, `simulated:contraption_diagram`, `simulated:honey_glue`, `simulated:launched_plunger`, and 11 `tfmg:*` providers (`blue_spark`, `copper_grenade`, `dry_ice_flake`, `green_spark`, `lithium_spark`, `napalm_bomb_entity`, `napalm_potato`, `pipe_bomb`, `spark`, `thermite_grenade`, `zinc_grenade`). These share Registrate's `EntityBuilder` provider lambda.
- Create Stuff 'N Additions: `brass_cube_r`, `brass_drone`, `drill_module`, `fan_module`, `flamethrower_pr`, `flamethrower_pr_2`, `lifted_block`, `magnet_module`, `vault_module`.
- SecurityCraft: `bouncingbetty`, `bullet`, `imsbomb`, `security_sea_boat`, `securitycamera`, `sentry`.
- Exposure: `camera_stand`, `glass_photograph_frame`, `photograph_frame`.
- Xerca: `xercapaint:canvas`, `xercapaint:easel`, `xercamusic:music_spirit`.
- Other singletons: `buildersdelight:sit`, `corpse:corpse`, `decocraft:seat`, `farmersdelight:rotten_tomato`, `furnish:seat`.

Dynamic Context use during entity provider construction:

```text
entity_dispatcher:185
font:183
bake_layer:175
model_set:48
item_in_hand_renderer:45
item_renderer:23
block_dispatcher:22
model_manager:13
resource_manager:2
```

Player provider construction adds 6 `bakeLayer`, 8 `model_set`, and 2 `model_manager` accesses.

Sensitive caller attribution positively identified, among others:

- `VillagerRenderer::<init>` -> `bakeLayer`, current model set, `ResourceManager`;
- `ZombieVillagerRenderer::<init>` -> `bakeLayer`, `ModelManager`, `ResourceManager`;
- `SkeletonRenderer`, `GiantMobRenderer`, `AbstractZombieRenderer`, `PiglinRenderer`, `ArmorStandRenderer`, `PlayerRenderer` -> `ModelManager` consumers;
- many vanilla constructors -> current `EntityModelSet` / `bakeLayer` consumers;
- Create Stuff 'N Additions renderers -> current `bakeLayer` consumers;
- SecurityCraft renderers -> current `bakeLayer` consumers; the boat-model path alone requested 18 layer bakes.

A provider that did not call one of these instrumented getters is **unknown**, not pure. Constructors may still observe static/global mutable state, renderer instances, mod APIs, caches or other reload state.

## Why the large CPU phase is not preparable

PR #92 independently decomposed the same renderer family in a previous exact-pack smoke. Its entity dispatcher measured **1947.992 ms wall / 1821.375 ms CPU**, with entity-provider creation **1841.919 ms**. `EntityModelSet.bakeLayer` itself was only **12.400 ms** total, of which repeated-layer keys were only **3.681 ms**.

The bounded hot samples were instead dominated by Entity Model Features work such as:

- `MathExpressionParser.readVariableOrConstant`;
- `EMFJemData.processAnimAndKeyString`;
- `MathExpressionParser.<init>`;
- `ModelPartVariableFactory.createsThisVariable`;
- `EMFManager.getModelFromHierarchicalId`;
- expression optimization/parsing helpers.

The exact pack uses Entity Model Features **3.2.4**. That version injects around the synthetic `EntityRenderers` provider loop and writes `EMFManager.getInstance().currentSpecifiedModelLoading` before selected providers, then resets it afterwards. `EMFManager` is a singleton holding mutable reload-local/global caches/maps/sets including JEM/model-layer caches, layer-attempt counters, root-part maps and the current-provider selector.

So the hot provider loop has ordering-visible mutable state even before considering the explicit `ResourceManager`/`ModelManager`/model-layer consumers above. Moving providers earlier or running them concurrently would require a stronger immutable contract from EMF and each affected renderer provider; BootOptim cannot infer such a contract from high CPU utilization.

Classification: **CPU-heavy + mutable/model-state dependent, not pure/preparable**.

## Exact `EntityRenderersEvent.AddLayers` subscribers

The final read-only EventBus inventory found exactly five subscribers, all at `NORMAL`, in stock mod order:

| Order | Mod | Exact listener/callsite | Observed current-state access | Classification |
| ---: | --- | --- | --- | --- |
| 0 | `curios` | `top.theillusivec4.curios.Curios$ClientProxy.addLayers(AddLayers)` | `getSkins` once; `addPlayerLayer` -> `getSkin` twice | mutable current player renderers; callback boundary |
| 1 | `placebo` | `dev.shadowsoffire.placebo.PlaceboClient.addLayers(AddLayers)` | `getEntityModels` + current model set; `getSkins` once; `getSkin` twice | ModelSet dependent + mutable current player renderers |
| 2 | `holdmyitemsnf` | `de.bene2212.holdmyitemsnf.Holdmyitemsnf$$Lambda/...` | no instrumented event/Context getter in this run | arbitrary mod callback; purity unproven |
| 3 | `create_sa` | `ClientEvents$ModBusEvents.addEntityRendererLayers(AddLayers)` | no instrumented event/Context getter in this run | arbitrary mod callback; purity unproven |
| 4 | `create` | `com.simibubi.create.foundation.events.ClientEvents.addEntityRendererLayers(AddLayers)` | no instrumented event/Context getter in this run | arbitrary mod callback; purity unproven |

The whole event costs **18.441 ms wall**, under 1% of the dispatcher slot. Even if a callback were eventually proven pure, this hosted ceiling is too small to justify a risky dispatch/threading change here. Curios and Placebo are positively disqualified from early preparation because they observe the current renderer/model objects. The other three are not promoted to pure merely because these specific getters were not called.

Classification: **mutable/callback boundary; no useful preparable subset identified**.

## Publication / mutable structures

The structures that must remain committed in stock order are:

- newly constructed entity-renderer map;
- newly constructed player-renderer map;
- renderer instances and their layer lists/model parts;
- `EntityRenderDispatcher` fields pointing at those maps;
- any renderer mutations made by `AddLayers` subscribers.

`ModelPart` roots are mutable animation/render state, so direct sharing of baked roots is not a safe shortcut. PR #92 already showed repeated layer-bake wall is far too small to justify a prototype/copy architecture from this slot alone.

## Validation

Validated runtime head: `2c806e065689bf29e414d7e81109f56aa52f3c2e`.

- Build Actions `34215684853`: **SUCCESS**; artifact `10051668827`, `sha256:23bc704875ea7c36121c0c3d79008eae1868564ade2aabd3729a27d8a48c366b`.
- Generic Startup Benchmark `34215684745`: **SUCCESS** to menu; artifact `10051710840`, `sha256:6b0cc94719527d34791536e1dcd29757cdf341b71df8dc1b355e397bb485992c`.
- Exact-pack smoke `34215684725`: **SUCCESS** with valid fixture/resource contract and zero BootOptim Mixin failures; artifacts/digests above.

No optimization candidate exists, so **do not run a 3x candidate/control A/B** and **do not request a laptop run**. Measuring the diagnostic on/off would estimate probe overhead, not demonstrate a critical-path optimization.

## Reopening criteria

Reopen this lane only with one of:

1. a specific provider family whose implementation supplies/proves immutable reload inputs and a side-effect-free prepare result that can be committed later in stock provider order;
2. an EMF change/contract that removes its global mutable provider-loop coupling and exposes a reload-local immutable parse/compile product;
3. a new exact-pack profile showing a materially large provider-specific subphase independent of `ResourceManager`, `ModelManager`, current model layers, renderer instances and mod-global state.

Any future behavior-changing candidate must be default-off/fail-open, preserve stock publication order and #183's structural digest/probes where relevant, pass build + startup smoke, and then run hosted exact-pack candidate/control **3x** before any physical gate.

## Related evidence

- PR #47 — preparation barrier / ordered-turn critical-path methodology.
- PR #85 — separate LevelRenderer/render-thread/GL boundary work.
- PR #92 — renderer layer-rebake diagnostic and EMF-heavy CPU attribution.
- PR #138/#141 — aggregate reload boundary and hardware-sensitive attribution discipline.
- PR #183 — real-class model replay and semantic digest boundary.
- PR #184 — exclusive serial-tail attribution; `EntityRenderDispatcher` = 2153.135 ms hosted slot.
