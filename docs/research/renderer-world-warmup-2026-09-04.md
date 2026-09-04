# Renderer coordinated world warmup — 2026-09-04

Status: **ACTIVE / EXPERIMENTAL**

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

## Hosted forcing smoke

The exact-pack benchmark normally exits immediately after recording the title marker. To exercise the deferred reload without changing TTMM, an additional diagnostic property is provided:

```text
-Dboot_optim.experimentRendererForceAfterTitleSmoke=true
```

When enabled alongside the renderer defer property, `ClientStartupHooks` performs:

1. record the ordinary `main_menu` marker;
2. call `RendererReloadCoordinator.forcePending("after_title_smoke")`;
3. only after the full stock/NeoForge reload returns, execute the normal benchmark stop.

Expected markers:

```text
BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=forcing reason=after_title_smoke ...
BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=forced ... force_ms=...
BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=forced ... force_ms=...
BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=complete reason=after_title_smoke ... block_pending=false entity_pending=false
```

The smoke passes only if both original reload bodies return, pending state is cleared, no BootOptim Mixin failure occurs, and EMF/ETF/NeoForge callbacks do not crash.

## Remaining limitations

- A post-title forced reload proves construction/callback correctness but is not a true world-render visual test.
- The hosted force duration is useful to size the deferred payment but the slow Windows laptop remains the important hitch gate.
- A mod that directly accesses a private renderer map via accessor before both a public forcing boundary and world attachment can still observe stale state. The exact title-path evidence found no such pre-title consumer in this pack; a generic production implementation may require compatibility exclusions if such mods are identified.
- Subsequent resource reloads remain stock/eager.

## Gate

1. Build and standard Startup Benchmark must remain green with experiment properties off.
2. Hosted exact-pack smoke with both experiment properties enabled must reach/record title, then force block-entity→entity successfully and exit cleanly.
3. Record hosted block/entity/total force wall to quantify the payment being moved to world entry.
4. If forcing is correct, run the coordinated world-warmup candidate on the physical laptop and verify: TTMM, world-entry latency, first frames, Fresh Animations/EMF entities, player rendering, and representative block-entity renderers.
5. Only after that decide whether to promote lifecycle deferral as-is or first reduce EMF's intrinsic parser/ASM construction cost.
