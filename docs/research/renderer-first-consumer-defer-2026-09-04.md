# Renderer first-consumer defer — 2026-09-04

Status: **VALIDATED TITLE PATH / WORLD GATE PENDING**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Established premise

PR #92 attributed the renderer-dispatcher startup tail. Hosted exact-pack measurements showed that renderer reconstruction, not `EntityModelSet.bakeLayer`, dominates:

- block-entity dispatcher ~388 ms total, ~387 ms in provider creation;
- entity dispatcher ~2.681 s total, ~2.603 s in entity provider creation and ~62 ms in player renderer creation;
- repeated model-layer baking was only single-digit milliseconds and is rejected as an optimization target.

Scoped stack sampling inside `EntityRenderers.createEntityRenderers` identified EMF/Fresh Animations expression parsing/optimization and ASM compilation as the dominant intrinsic provider cost. A simple parsed-expression cache is unsafe because context variables bind to model-specific `EMFModelPart` instances; EMF's ASM executor/layout split remains a possible later intrinsic optimization.

PR #93 tested the lifecycle ceiling by cancelling only the first entity and block-entity dispatcher reload. Exact-pack 3x3 main-menu median improved by 7.176 s / 7.55%; all candidates reached title. MCEF/panorama were also favorably noisy in that campaign, so #93 established the lifecycle premise rather than a precise production delta.

Historical slow-laptop barrier/turn attribution in PR #75 saw larger serial post-turn intervals: ~5.316 s for `BlockEntityRenderDispatcher` and ~6.839 s for `EntityRenderDispatcher`. Those remain hardware-specific ceilings rather than promised savings.

## First-consumer implementation

Property:

```text
-Dboot_optim.experimentRendererFirstConsumerDefer=true
```

The candidate preserves the authoritative stock/NeoForge reload rather than replacing its internals:

1. The first startup `onResourceManagerReload(ResourceManager)` call is retained as a pending resource manager and cancelled before eager renderer construction.
2. `EntityRenderDispatcher.getRenderer(...)`, `EntityRenderDispatcher.getSkinMap()`, and `BlockEntityRenderDispatcher.getRenderer(...)` are forcing boundaries.
3. Before the first forcing boundary, the original dispatcher `onResourceManagerReload(...)` method is invoked synchronously on Minecraft's client/render thread.
4. Reentrant lookups while the original reload is constructing renderers do not recursively force.
5. Calls from another thread use Minecraft's `executeBlocking` to serialize the real reload onto the client thread before returning to the consumer.
6. A second resource reload before first consumption supersedes the pending startup generation and runs stock immediately; the stale pending generation is discarded.
7. After successful force, the retained `ResourceManager` reference is dropped.

No renderer/model object is cached/shared and no NeoForge callback is manually replayed.

## Exact-pack A/B result — PR #94

Head: `4c35b4540417525930e81096ae70799c7d10b96f`.

Three fresh hosted VMs per variant:

| metric | candidate | control | delta |
| --- | ---: | ---: | ---: |
| main menu | 87.421 s | 90.914 s | **-3.493 s / -3.84%** |
| mod entrypoint | 30.289 s | 30.551 s | -0.262 s / -0.86% |
| post-entrypoint | 57.285 s | 60.363 s | **-3.078 s / -5.10%** |
| reload -> FancyMenu finish | 38.871 s | 42.113 s | **-3.242 s / -7.70%** |
| MCEF init | 2.132 s | 1.590 s | **+0.542 s candidate slower** |
| FancyMenu panorama | 3.943 s | 4.096 s | -0.153 s |

All three candidate runs:

- emitted `status=deferred` for both dispatchers;
- reached semantic main menu;
- emitted **zero `status=forced` before title**;
- reported zero BootOptim Mixin failures.

One control VM was an unusually fast 73.960 s. Medians are therefore used; pairwise differences and means are not considered stable. Importantly, unrelated MCEF movement was adverse to the candidate in this campaign, so it does not explain the favorable renderer result.

**Conclusion:** the production-shaped first-consumer mechanism preserves a coherent multi-second title-path win and does not accidentally force before title. The remaining blocker is what happens when the deferred real reload is finally paid.

## Listener order and world transition

Vanilla 1.21.1 registers `BlockEntityRenderDispatcher` as a reload listener before `EntityRenderDispatcher`. A coordinated force should preserve that order even if the first logical consumer is an entity renderer.

World attachment provides a safer payment boundary than the first visible renderer call:

- `Minecraft.updateLevelInEngines(nonNullLevel)` calls `LevelRenderer.setLevel(level)` and later `BlockEntityRenderDispatcher.setLevel(level)`;
- `LevelRenderer.setLevel(level)` calls `EntityRenderDispatcher.setLevel(level)`.

Therefore a coordinator injected at the start of the first non-null `Minecraft.updateLevelInEngines` can rebuild block-entity then entity renderers before either dispatcher receives/uses the new world. First-consumer forcing remains a safety net for unusual consumers before world attachment.

## Remaining correctness risks

- A mod can bypass public methods with an accessor to private renderer maps; coordinated world-attachment warmup protects normal world use but not arbitrary pre-world direct access.
- Renderer constructors can have observable side effects another subsystem expects before any lookup. Three title runs without failures are evidence for the exact pack, not a universal proof.
- The deferred force may still be several seconds on slow hardware. Moving it to world-transition avoids a visible first-render frame hitch but can lengthen world-entry loading.
- Cross-dispatcher ordering must remain block-entity then entity to match initial reload listener order.
- Subsequent/manual resource reloads remain eager/stock in the current design.

## Next gate

A separate follow-up branch must:

1. coordinate both deferred reloads in vanilla listener order;
2. after the already-recorded title marker, force the real block-entity then entity reload on hosted exact-pack and measure each force duration without affecting TTMM;
3. verify both reloads return successfully with zero Mixin/EMF errors;
4. add first-non-null-world attachment as the normal warmup point, retaining first-consumer forcing as fallback;
5. require a physical-laptop world-entry/visual gate before production promotion.

## Related

- PR #75 — resource reload apply-tail attribution.
- PR #92 — renderer layer/provider attribution and EMF scoped sampling.
- PR #93 — validated skip ceiling.
- PR #94 — validated first-consumer title-path candidate.
- EMF upstream PR #526 — ASM animation backend introduced in the 3.2 line.
