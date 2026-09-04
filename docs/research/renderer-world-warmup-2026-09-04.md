# Renderer coordinated world warmup — 2026-09-04

Status: **VALIDATED FORCING / PHYSICAL WORLD GATE PENDING**

Parent evidence: PR #94, first-consumer title-path candidate.

## Why this follow-up exists

PR #94 proves that retaining the initial entity/block-entity renderer reload until after title preserves a hosted exact-pack TTMM win (~3.493 s median / 3.84%) and that neither map is consumed before title in three candidate runs. It does **not** prove that paying the deferred reload on the first renderer lookup is a good user experience.

The slow-laptop historical ceiling is much larger than hosted (~5.316 s block-entity + ~6.839 s entity post-turn wall). Paying that on the first visible frame could simply turn startup latency into a severe first-world hitch.

This branch changes the normal payment boundary from first renderer lookup to **first non-null world attachment**, while retaining first-consumer forcing as a fallback for unusual earlier consumers.

## Ordering invariant

Minecraft 1.21.1 registers resource reload listeners in this order:

1. `BlockEntityRenderDispatcher`;
2. `EntityRenderDispatcher`.

The parent #94 implementation could force one dispatcher independently based on which public lookup arrived first. This branch adds a coordinator: any forcing boundary rebuilds **both pending dispatchers in vanilla listener order**. A global client-thread reentrancy guard prevents provider lookups during the stock reload from recursively launching the second reload early.

This matters because renderer providers and NeoForge renderer events can have arbitrary mod side effects even though the two renderer maps are otherwise separate.

## World transition boundary

On 1.21.1:

- `Minecraft.updateLevelInEngines(level)` calls `LevelRenderer.setLevel(level)` and later `BlockEntityRenderDispatcher.setLevel(level)`;
- `LevelRenderer.setLevel(level)` calls `EntityRenderDispatcher.setLevel(level)`.

The candidate injects at the head of `updateLevelInEngines` and, only when `level != null`, runs the coordinator first. Therefore deferred renderer reconstruction finishes before either dispatcher is attached to the new world.

The first-consumer hooks remain active. If a title/mod screen or another subsystem really needs a renderer before world attachment, it still forces the same coordinated reload synchronously.

## Hosted forcing smoke — PASSED

The exact-pack benchmark normally exits immediately after recording the title marker. To exercise the deferred reload without changing TTMM, an additional diagnostic property is provided:

```text
-Dboot_optim.experimentRendererForceAfterTitleSmoke=true
```

When enabled alongside the renderer defer property, `ClientStartupHooks` performs:

1. record the ordinary `main_menu` marker;
2. call `RendererReloadCoordinator.forcePending("after_title_smoke")`;
3. only after the full stock/NeoForge reload returns, execute the normal benchmark stop.

PR #95 exact-pack smoke run `33911857477`, head `607e9a1286de20a5862f4517159df41e2951e633`, passed Build, ordinary Startup Benchmark, exact-pack launch, and aggregate.

Observed exact-pack markers:

```text
BOOTOPTIM_STARTUP phase=main_menu uptime_ms=88159 ...
BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=forcing reason=after_title_smoke block_pending=true entity_pending=true thread=Render thread
BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=forced consumer=coordinator:after_title_smoke force_ms=147.010 thread=Render thread
BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=forced consumer=coordinator:after_title_smoke force_ms=1682.770 thread=Render thread
BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=complete reason=after_title_smoke total_ms=1830.114 block_pending=false entity_pending=false thread=Render thread
```

The run reported 0 BootOptim Mixin errors. The full original block-entity and entity reload bodies returned successfully in vanilla order, their pending `ResourceManager` references were cleared, and no new EMF/ETF/renderer exception appeared during or after the force. Immediately after completion the log contains normal FancyMenu/Palladium/Iris lifecycle messages and clean shutdown. The earlier OpenAL device failure is hosted-runner audio noise and predates the renderer force.

Hosted payment sizing from this smoke:

| work | wall |
| --- | ---: |
| block-entity reload | **147.010 ms** |
| entity reload | **1,682.770 ms** |
| coordinated total | **1,830.114 ms** |

This single-run force wall is not an A/B performance estimate. It does establish that the actual deferred payment on the hosted exact pack is finite and substantially smaller than the historical slow-laptop post-turn ceiling. It is also about half the ~3.493 s hosted TTMM median improvement measured for the parent first-consumer candidate, so the current evidence does not support the simplistic model that the entire TTMM win is merely moved unchanged to a later frame.

## What is now proven

- the exact title path does not consume either renderer map before title in the validated #94 3x3 campaign;
- deferring the startup callbacks preserves a hosted TTMM improvement;
- the authoritative stock/NeoForge callback bodies can still execute successfully after title;
- block-entity then entity forcing can be coordinated in original listener order without reentrant recursive forcing;
- after forcing, both pending generations are cleared;
- the normal candidate boundary can be moved to the first non-null world attachment, before either renderer dispatcher receives the new world.

## Remaining limitations

- A post-title forced reload proves construction/callback correctness but is not a true world-render visual test.
- The 1.830 s hosted force duration is useful for sizing but does not predict the old 4-thread Windows laptop; the physical machine remains the decisive hitch gate.
- A mod that directly accesses a private renderer map via accessor before both a public forcing boundary and world attachment can still observe stale state. The exact title-path evidence found no such pre-title consumer in this pack; a generic production implementation may require compatibility exclusions if such mods are identified.
- Subsequent resource reloads remain stock/eager.

## Physical world-entry gate

Run candidate/control from the same refreshed pack state and record separately:

1. time-to-main-menu;
2. title -> world-attachment / first playable frame wall;
3. `BOOTOPTIM_RENDERER_RELOAD_COORDINATOR` total and per-dispatcher `force_ms`;
4. whether forcing occurs with `reason=world_attach` rather than a first-render consumer;
5. visible hitch duration around world transition;
6. Fresh Animations / EMF entity appearance and animation;
7. local and remote player rendering / skin model selection;
8. representative block entities (at minimum chest/sign plus modded animated/special renderer if available in the reference world);
9. disconnect back to title and a second world entry;
10. one manual resource reload after initialization, which must remain stock and complete normally.

Reject or redesign the lifecycle candidate if world attachment produces an unacceptable multi-second freeze, renderer/model corruption, missing player skins, callback ordering problems, or reload-generation errors. If semantics are clean but laptop forcing remains too expensive, the next target is EMF/Fresh Animations intrinsic parser/ASM provider construction rather than moving the payment later again.

## Gate status

1. Build with properties off: **PASS**.
2. Standard Startup Benchmark with properties off: **PASS**.
3. Hosted exact-pack post-title forcing smoke: **PASS**.
4. Hosted callback/order/pending-state gate: **PASS**, 147.010 ms block + 1,682.770 ms entity, 1,830.114 ms total.
5. Physical laptop world-entry + visual + subsequent-reload gate: **PENDING**.
6. Production promotion decision: **BLOCKED on step 5**.
