# Renderer first-consumer defer — 2026-09-04

Status: **ACTIVE / EXPERIMENTAL**

Base: `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

## Established premise

PR #92 attributed the renderer-dispatcher startup tail. Hosted exact-pack measurements showed that renderer reconstruction, not `EntityModelSet.bakeLayer`, dominates:

- block-entity dispatcher ~388 ms total, ~387 ms in provider creation;
- entity dispatcher ~2.681 s total, ~2.603 s in entity provider creation and ~62 ms in player renderer creation;
- repeated model-layer baking was only single-digit milliseconds and is rejected as an optimization target.

Scoped stack sampling inside `EntityRenderers.createEntityRenderers` then identified EMF/Fresh Animations expression parsing/optimization and ASM compilation as the dominant intrinsic provider cost. This makes the renderer maps unusually expensive in the exact pack but does not itself prove that they are needed before title.

PR #93 tested that lifecycle question by cancelling only the first entity and block-entity dispatcher reload. Exact-pack 3x3 medians:

| metric | candidate skip | control | delta |
| --- | ---: | ---: | ---: |
| main menu | 87.846 s | 95.022 s | **-7.176 s / -7.55%** |
| post-entrypoint | 57.720 s | 62.684 s | **-4.964 s / -7.92%** |
| reload -> FancyMenu finish | 38.773 s | 43.229 s | **-4.456 s / -10.31%** |

All three candidate runs reached the semantic title marker with zero BootOptim Mixin errors. MCEF and panorama were also faster in the candidate medians (~489 ms and ~754 ms respectively), so the full TTMM delta is not assigned exclusively to renderer work. The result nevertheless proves that the exact title path does not require the eagerly rebuilt renderer maps and establishes a multi-second lifecycle ceiling.

Historical slow-laptop barrier/turn attribution in PR #75 saw larger serial post-turn intervals: ~5.316 s for `BlockEntityRenderDispatcher` and ~6.839 s for `EntityRenderDispatcher`. Those figures remain hardware-specific ceilings rather than promised savings.

## Candidate architecture

Property for this experiment:

```text
-Dboot_optim.experimentRendererFirstConsumerDefer=true
```

The candidate preserves the authoritative stock/NeoForge reload rather than replacing its internals:

1. The first startup `onResourceManagerReload(ResourceManager)` call is retained as a pending resource manager and cancelled before eager renderer construction.
2. `EntityRenderDispatcher.getRenderer(...)`, `EntityRenderDispatcher.getSkinMap()`, and `BlockEntityRenderDispatcher.getRenderer(...)` are forcing boundaries.
3. Before the first forcing boundary, the original dispatcher `onResourceManagerReload(...)` method is invoked synchronously on Minecraft's client/render thread.
4. Reentrant lookups while the original reload is constructing renderers do not recursively force.
5. Calls from another thread use Minecraft's `executeBlocking` to serialize the real reload onto the client thread before returning to the consumer.
6. A second resource reload that occurs before first consumption supersedes the pending startup generation and runs stock immediately; the stale pending generation is discarded.
7. After a successful force, the retained `ResourceManager` reference is dropped and all later behavior is stock.

The candidate does not cache renderer objects, share mutable model state, reconstruct NeoForge callbacks, or invent empty/fake maps.

## Correctness risks

- A mod may access the private renderer maps through an accessor/mixin instead of public forcing methods.
- Renderer constructors can have observable side effects that another pre-world subsystem expects even without reading a renderer map.
- `getSkinMap()` is exposed separately and therefore must force entity renderer initialization even if no entity has rendered yet.
- Special-item/block-entity paths must ultimately pass through `BlockEntityRenderDispatcher.getRenderer`; world-entry validation should exercise representative block entities.
- Forcing the full reload at first world render can create a visible hitch. TTMM benefit alone is insufficient for production if the user simply pays the same multi-second stall on the first rendered frame.

## Gates

1. Build and normal Startup CI with property off.
2. Hosted exact-pack A/B 3x3 with candidate property on versus off. Candidate must still reach title with both dispatchers deferred and zero BootOptim Mixin failures.
3. Candidate logs must show no pre-title `status=forced`; forcing before the title would collapse the lifecycle premise.
4. A world-entry/entity-render smoke must prove that the pending real reload is forced successfully and renderer construction/EMF callbacks complete without crashes or missing renderers.
5. Measure the force duration separately. If it is multi-second, investigate moving the force to a safe transition before first world frame or reducing EMF's intrinsic compilation cost before production promotion.
6. Physical laptop A/B remains the final performance gate because the historical ceiling is substantially larger than hosted and first-world hitch perception is hardware-sensitive.

## Related

- PR #75 — resource reload apply-tail attribution.
- PR #92 — renderer layer/provider attribution and EMF scoped sampling.
- PR #93 — skip ceiling, exact-pack 3x3 validation.
- EMF 3.2.4 source — parser/context-specific variable binding and ASM backend separation.
