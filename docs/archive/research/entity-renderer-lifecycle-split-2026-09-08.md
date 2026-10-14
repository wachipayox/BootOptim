# Entity renderer lifecycle split audit — 2026-09-08

Status: **COMPLETE / NO-GO FOR LIFECYCLE SPLIT / DOCS-ONLY**

Agent: 63.

Authority refreshed before branching: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. The task-supplied `7c2cb82cd68cd86b343b12177810a1116add5902` is an ancestor of that public head (43 commits behind at refresh time). Branch: `agent63/entity-renderer-lifecycle-split-20260908`.

Required-context note: `docs/research/five-front-replay-triage-2026-09-08.md` was not present at the supplied SHA, the refreshed integration head, or the visible repository search/PR history. This audit does not invent missing content from that document.

## Decision

There is no lifecycle-safe BootOptim split for the exact-pack `EntityRenderers.createEntityRenderers` cost under the current Minecraft/NeoForge 1.21.1 + Entity Model Features 3.2.4 contracts.

Do not move generic providers before the resource-reload barrier, do not execute them on workers, do not construct renderer/model objects into a detached future for later publication, and do not defer the provider loop to first world/render. No behavior-changing candidate, hosted A/B, or physical-laptop run is justified from this front.

The reason is stronger than “providers may be arbitrary”. The measured hot work is nested in EMF's `EntityModelSet.bakeLayer` interception and consumes/mutates exact-reload state while constructing the actual mutable `ModelPart`/renderer graph. In EMF 3.2.4, provider identity is communicated through a shared mutable `EMFManager.currentSpecifiedModelLoading` string around each `EntityRenderers` provider call. The same manager owns reload-local mutable JEM/model caches, model-layer mappings, variation/debug roots, validation state, and other ordering-sensitive selectors. The expensive animation-expression preparation resolves against the actual `EMFModelPart` graph, so the current implementation does not expose a text-only or immutable compile product that BootOptim can prepare independently and commit later.

Publication itself is not the bottleneck: PR #190 leaves only ~4.427 ms residual after entity providers, player providers, and `AddLayers`. Deferring construction would therefore defer the actual ~2.1 s CPU work, producing a first-world/render hitch unless an independent immutable preparation product is first created. No such product exists in the current provider/EMF APIs.

## Evidence population and metric types

PR #184 hosted exact-pack run `34175380705` measured `EntityRenderDispatcher` as **2153.135 ms exclusive ordered serial-slot wall**. That is critical-tail wall, not an inclusive listener duration or task-sum.

PR #190 hosted exact-pack run `34215684725` reproduced the family as **2163.293 ms wall / 2115.694 ms current Render-thread CPU**, with process-origin `main_menu=93.198 s`, exact resource selection valid, `8192x8192x2` block atlas and zero BootOptim Mixin failures. Its internal phase timers are direct wall/current-thread CPU measurements nested inside the dispatcher slot; they are not independently additive startup savings.

| Callsite / phase | Hosted wall | Current-thread CPU | State/dependency boundary | Lifecycle classification |
| --- | ---: | ---: | --- | --- |
| `EntityRenderDispatcher.onResourceManagerReload` total | 2163.293 ms | 2115.694 ms | current resource generation; Render thread; publishes renderer maps then callbacks | exclusive serial-slot family, CPU-heavy |
| `EntityRenderers.createEntityRenderers(context)` | **2086.163 ms** | **2038.738 ms** | 182 entity providers; current dispatcher/renderers/font, `EntityModelSet`, `ModelManager`, `ResourceManager`; EMF interception | **not separable under current contracts** |
| `EntityRenderers.createPlayerRenderers(context)` | 54.262 ms | 54.237 ms | two player providers; current model set/model manager and mutable player renderer instances | owner-thread construction/publication |
| NeoForge `EntityRenderersEvent.AddLayers` | 18.441 ms | 18.445 ms | synchronous arbitrary mod callbacks over newly published renderer/model objects | mandatory mutation/callback boundary; not a target |
| residual context/publication bookkeeping | 4.427 ms | ~4.274 ms | map/context assignment/bookkeeping | too small to explain slot |

`createEntityRenderers` accounts for 96.435% of dispatcher wall. Its CPU/wall ratio is ~97.7%, so the valid evidence says CPU-heavy Render-thread execution; it does **not** prove GL work or permission to off-thread it.

## Exact-pack provider dependencies

PR #190 inventories 184 providers total: 182 entity + 2 player. The entity-provider namespace counts are 129 `minecraft`, 11 `tfmg`, 9 `create`, 9 `create_sa`, 6 `securitycraft`, 3 `simulated`, 3 `exposure`, 2 `aeronautics`, 2 `xercapaint`, and one each for `bits_n_bobs`, `buildersdelight`, `corpse`, `decocraft`, `farmersdelight`, `furnish`, `offroad`, and `xercamusic`.

Dynamic `EntityRendererProvider.Context` access during entity construction was `entity_dispatcher:185`, `font:183`, `bake_layer:175`, `model_set:48`, `item_in_hand_renderer:45`, `item_renderer:23`, `block_dispatcher:22`, `model_manager:13`, `resource_manager:2`. Player construction adds six `bakeLayer`, eight model-set and two model-manager accesses.

Positive sensitive callers include Villager/ZombieVillager resource-manager paths, Skeleton/Giant/AbstractZombie/Piglin/ArmorStand/Player model-manager consumers, many current model-layer users, Create Stuff 'N Additions layers, and SecurityCraft layers. A provider lacking one of these instrumented getters is unknown, not proven pure: constructors can still observe mod/static/global mutable state or invoke APIs not covered by the Context getter probe.

PR #92 independently showed that vanilla `EntityModelSet.bakeLayer` mechanics are not the cost to optimize in isolation: only **12.400 ms** of an approximately **1947.992 ms** dispatcher was inside `bakeLayer`, with repeated-layer work only ~3.681 ms. Its bounded stack samples instead concentrated in EMF animation/expression/JEM processing (`MathExpressionParser`, `EMFJemData.processAnimAndKeyString`, model-variable resolution and `EMFManager.getModelFromHierarchicalId`).

## Exact EMF 3.2.4 lifecycle and why it blocks prepare/commit

The exact EMF version used by the pack is 3.2.4, upstream commit `9cda2159e9fd4ef631b4dda0500fe1cfc46520f1`.

`MixinEntityRenderers` injects at the synthetic per-provider body of `EntityRenderers.createEntityRenderers`. At provider HEAD it writes `EMFManager.currentSpecifiedModelLoading` for provider-specific cases such as spectral arrows, breeze wind charges and boats; at provider TAIL it resets the field. In this exact version that selector is a plain mutable `String`, not a thread-local value. Provider overlap therefore changes an EMF-visible ordering channel before considering any provider's own thread safety.

`MixinEntityModelLoader` intercepts every `EntityModelSet.bakeLayer` return and replaces the returned root with `EMFManager.injectIntoModelRootGetter(layer, root)`. That method consumes the current provider selector and current resource/model state, updates model-layer and fallback/variation state, locates/loads JEM resources, and may construct `EMFModelPartRoot` around the actual mutable vanilla `ModelPart` root.

`EMFManager` 3.2.4 mixes concurrent and non-concurrent mutable reload state: among other fields, `cache_JemDataByFileName` is a `HashMap`, `modelsAnnounced`/EBE sets are `HashSet`s, known pack order is an `ArrayList`, while layer/root maps use concurrent collections. It also owns mutable selectors/counters such as `currentSpecifiedModelLoading`, `currentBlockEntityTypeLoading`, `isAnimationValidationPhase`, trader-llama/undead-horse state and the last-created root.

At reload start EMF sets `EMF.isLoadingPhase=true` and calls `EMFManager.resetInstance()`, clearing resource/model/global-variable caches and replacing the singleton. This gives the manager reload-local lifetime, but not immutable or per-provider ownership.

`getJemDataWithDirectory` resolves through `Minecraft.getInstance().getResourceManager()`, opens the current JEM resource, deserializes it, calls mutable `EMFJemData.prepare`, then conditionally publishes it into the manager cache. `EMFJemData.prepare` rewrites/mutates model-part names and animation strings, validates texture/resource paths against the current resource manager, prepares part data, and fills the animation map.

Most importantly for the measured hot stacks, `setupAnimationsFromJemToModel` does not compile an expression against an immutable symbol schema. It builds a map of the actual `EMFModelPart` instances from the new `EMFModelPartRoot`, creates animation-line records holding concrete part references, sets manager-wide `isAnimationValidationPhase`, parses/optimizes each expression with a context containing that model graph, optionally compiles ASM, then attaches the resulting handler to the actual root. The expensive parser work is therefore semantically fused to renderer/model construction in the current implementation.

### Consequence for candidate shapes

**Pre-barrier prepare:** invalid. Providers can require the new `ModelManager`, `ResourceManager`, `EntityModelSet` and resource-pack generation. Starting before ModelManager publication can mix old renderer/model state with the new reload.

**Post-ModelManager worker prepare + Render-thread commit:** still invalid generically. Current provider calls construct the actual mutable renderer/model objects and enter EMF's shared provider selector/caches. There is no provider interface for returning a detached immutable plan, nor an EMF API for compiling a provider-independent animation/JEM plan and binding it later.

**Render-thread publication split:** no leverage is demonstrated. The measured non-provider residual is ~4.427 ms; the expensive work occurs before an object exists that can merely be published.

**Defer until first world/entity render:** invalid under the requested semantics. `AddLayers` is posted synchronously only after the new renderer/player maps exist, and exact-pack Curios and Placebo positively observe those current renderer/model objects. Delaying construction either delays/reorders callbacks, requires semantically fake placeholders, or moves approximately the full provider CPU phase into first gameplay use. That violates the first-frame/no-hitch requirement.

**Construct stock renderers now, retrofit EMF later:** invalid. EMF replaces `ModelPart` roots during construction, decides model/fallback/variant identity there, and attaches animation handlers to those roots. Swapping them later changes objects already visible to renderer layers/callbacks and first render.

## `AddLayers` and publication invariant

The exact five `NORMAL` subscribers in stock mod order are Curios, Placebo, Hold My Items NF, Create Stuff 'N Additions and Create. Curios positively reads the current skins/player renderers; Placebo reads the current `EntityModelSet` plus current skins/player renderers. The remaining three have no purity contract. The whole event costs only 18.441 ms hosted, so even proving one remaining callback pure would not unlock meaningful dispatcher savings.

The lifecycle invariant for any future candidate is therefore: construct semantically identical renderer/model objects for the current resource generation, publish entity map, publish player map, post `AddLayers` exactly once in stock ordering against those exact current objects, and preserve every mutation before any renderer consumer. No current split satisfies it while reducing the measured 2.086 s phase.

## Title, world and first-frame gate

Because this audit found no behavior-changing candidate, it intentionally does not request a fake 3x A/B, first-world run, or physical-laptop run. The demonstrated safe savings for this lifecycle-split premise are **0 ms**; `2086.163 ms` is the measured cost under investigation, not an authorized savings ceiling.

If this lane is reopened with a materially different contract, the diagnostic must separately record `createEntityRenderers` wall/current-thread CPU, renderer-map publication, `AddLayers` completion, `main_menu_presented`, first world render entry, first entity-render-dispatch use, and first presented world frame. Any lazy/prepare handoff must also emit the owner-thread wait/hitch at first use as its own wall metric. A hosted world smoke must verify stock entity/player renderer key sets, AddLayers call/order, title presentation and the first world frame before a performance A/B is interpreted.

## Reopening criteria

Reopen only if at least one premise materially changes:

1. a specific exact-pack provider family is profiled as a material share of `createEntityRenderers` and supplies a positive side-effect-free immutable preparation contract, with actual renderer/`ModelPart` construction and publication retained on the owner thread in stock provider order;
2. EMF changes to expose a reload-local immutable JEM/animation preparation artifact whose creation is independent of concrete `ModelPart` instances and shared provider-order state, with explicit invalidation by resource generation and later owner-thread binding;
3. an exact-pack provider-specific profile identifies a large algorithmic subphase that can be optimized **inside the same Render-thread/lifecycle boundary** without moving callbacks or objects across threads.

A newer EMF implementation merely making one selector thread-local is not by itself sufficient; all other mutable caches, concrete-part binding, fallback/variant ordering and mod-provider contracts still need proof.

Only after a candidate has an activation marker, title + hosted world smoke, zero Mixin/semantic failures, and a coherent >=3-run hosted A/B movement in critical-path wall should the old physical laptop arbitrate a hardware-sensitive effect.

## References

- BootOptim PR #184: https://github.com/wachipayox/BootOptim/pull/184
- BootOptim PR #190: https://github.com/wachipayox/BootOptim/pull/190
- BootOptim PR #191: https://github.com/wachipayox/BootOptim/pull/191
- EMF 3.2.4 commit: https://github.com/Traben-0/Entity_Model_Features/commit/9cda2159e9fd4ef631b4dda0500fe1cfc46520f1
- EMF provider-loop mixin: https://github.com/Traben-0/Entity_Model_Features/blob/9cda2159e9fd4ef631b4dda0500fe1cfc46520f1/src/main/java/traben/entity_model_features/mixin/mixins/rendering/MixinEntityRenderers.java
- EMF `EntityModelSet.bakeLayer` mixin: https://github.com/Traben-0/Entity_Model_Features/blob/9cda2159e9fd4ef631b4dda0500fe1cfc46520f1/src/main/java/traben/entity_model_features/mixin/mixins/MixinEntityModelLoader.java
- EMF manager: https://github.com/Traben-0/Entity_Model_Features/blob/9cda2159e9fd4ef631b4dda0500fe1cfc46520f1/src/main/java/traben/entity_model_features/EMFManager.java
- EMF JEM preparation: https://github.com/Traben-0/Entity_Model_Features/blob/9cda2159e9fd4ef631b4dda0500fe1cfc46520f1/src/main/java/traben/entity_model_features/models/jem_objects/EMFJemData.java
