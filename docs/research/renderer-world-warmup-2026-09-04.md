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

## Physical world-entry probe

The final hardware gate needs candidate/control timings on the same clock. Property:

```text
-Dboot_optim.experimentRendererWorldEntryProbe=true
```

The probe is independent of the optimization switch so it can run in both variants. The candidate also uses:

```text
-Dboot_optim.experimentRendererFirstConsumerDefer=true
```

and the control uses:

```text
-Dboot_optim.experimentRendererFirstConsumerDefer=false
```

The probe emits, for every non-null world attachment:

```text
BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_begin entry=1 uptime_ms=... renderer_reload_pending=true|false ...
BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_ready entry=1 uptime_ms=... warmup_ms=... ...
BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_complete entry=1 uptime_ms=... vanilla_attach_ms=... since_attach_begin_ms=... ...
BOOTOPTIM_RENDERER_WORLD_ENTRY status=first_render entry=1 uptime_ms=... since_attach_begin_ms=... since_attach_ready_ms=... since_attach_complete_ms=... ...
```

Semantics of the four points:

- `attach_begin`: HEAD of `Minecraft.updateLevelInEngines(non-null)`, before any renderer warmup or vanilla level attachment;
- `attach_ready`: immediately after candidate warmup (or immediately in control), still before vanilla `LevelRenderer`/dispatcher attachment;
- `attach_complete`: TAIL of `updateLevelInEngines`, after ordinary engine/dispatcher attachment;
- `first_render`: first `GameRenderer.renderLevel(DeltaTracker)` after that attachment.

The markers are numbered. After disconnecting and entering a second world, `entry=2` must appear; for the candidate it should report `renderer_reload_pending=false` and no second `reason=world_attach` coordinated force.

A Windows helper on this branch parses these markers and the force markers:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/laptop/summarize-renderer-world-gate.ps1 -LogPath <path-to-latest.log>
```

Use `-Json` for machine-readable output. It reports main-menu uptime, title→attach, warmup, remaining vanilla attach, attach→first-render, attach-complete→first-render, block/entity/coordinator force wall, and BootOptim Mixin failures.

## Remaining limitations

- A post-title forced reload proves construction/callback correctness but is not a true world-render visual test.
- The 1.830 s hosted force duration is useful for sizing but does not predict the old 4-thread Windows laptop; the physical machine remains the decisive hitch gate.
- A mod that directly accesses a private renderer map via accessor before both a public forcing boundary and world attachment can still observe stale state. The exact title-path evidence found no such pre-title consumer in this pack; a generic production implementation may require compatibility exclusions if such mods are identified.
- Subsequent resource reloads remain stock/eager.

## Physical world-entry gate

Run candidate/control from the same refreshed pack state and record separately:

1. time-to-main-menu;
2. title → `attach_begin` and title → `first_render` wall from the probe;
3. candidate `warmup_ms`, vanilla attach remainder, and `attach_complete` → `first_render` wall;
4. `BOOTOPTIM_RENDERER_RELOAD_COORDINATOR` total and per-dispatcher `force_ms`;
5. whether forcing occurs with `reason=world_attach` rather than a first-render consumer;
6. visible hitch duration around world transition;
7. Fresh Animations / EMF entity appearance and animation;
8. local and remote player rendering / skin model selection;
9. representative block entities (at minimum chest/sign plus modded animated/special renderer if available in the reference world);
10. disconnect back to title and a second world entry: `entry=2`, no pending renderer reload;
11. one manual resource reload after initialization, which must remain stock and complete normally.

Reject or redesign the lifecycle candidate if world attachment produces an unacceptable multi-second freeze, renderer/model corruption, missing player skins, callback ordering problems, or reload-generation errors. If semantics are clean but laptop forcing remains too expensive, the next target is EMF/Fresh Animations intrinsic parser/ASM provider construction rather than moving the payment later again.

## Hosted CI boundary

The hosted exact-pack workflow intentionally stops at the main-menu marker. Its `preparePackBenchmark` copy excludes `saves/`, and `runPackBenchmarkClient` sets `boot_optim.benchmark.exitOnTitle=true`. Fabricating a synthetic `ClientLevel` or a tiny artificial save solely to make a world check green would not validate Fresh Animations, player skins, real block entities, or the user-visible Windows transition. The real-world hardware gate above therefore remains explicit rather than being replaced with a misleading hosted surrogate.

## Gate status

1. Build with properties off: **PASS** on the forcing candidate; current probe-enhanced HEAD must remain green.
2. Standard Startup Benchmark with properties off: **PASS** on the forcing candidate; current probe-enhanced HEAD must remain green.
3. Hosted exact-pack post-title forcing smoke: **PASS** on the forcing candidate; current probe-enhanced HEAD is re-running the same smoke.
4. Hosted callback/order/pending-state gate: **PASS**, 147.010 ms block + 1,682.770 ms entity, 1,830.114 ms total.
5. Physical laptop world-entry + visual + subsequent-reload gate: **PENDING**.
6. Production promotion decision: **BLOCKED on step 5**.
