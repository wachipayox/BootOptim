# Post-preparation render-tail audit — 2026-09-08

Status: **NO-GO for a runtime candidate; one bounded Flywheel prepare/commit reopening condition**.

Base refreshed before work: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`. The task prompt named `7c2cb82cd68cd86b343b12177810a1116add5902`, but the public integration ref had advanced by 43 commits; this branch was created from the live public integration head.

Scope is intentionally limited to the post-preparation queue outside MoreCulling listener indices 25/26 and `EntityRenderDispatcher`: vanilla Shader Loader, Veil, Flywheel, `LevelRenderer`, and `ClientModLoader`. FancyMenu/MCEF are excluded. Durations below are ordered serial slots from PR #184's hosted exact-pack replay, never an additive sum of inclusive listener durations.

The requested `docs/research/five-front-replay-triage-2026-09-08.md` is not present on either the live integration ref or the prompt's stale SHA (GitHub contents API returns 404, and repository search found no public copy). The rest of the mandatory public context was read, including PR #184 and `levelrenderer-reload-split-2026-09-03.md`.

## Hosted attribution

PR #184 exact-pack run `34175380705` reached `main_menu=91.126 s` process-origin. `allPreparations` completed at `28.727523 s`; `allDone` at `42.285325 s`; the ordered post-preparation tail was `13.557802 s`. Excluding FancyMenu gives the already-established ~`8.921 s` external tail, but that value is not used as an additive budget here.

| owner | #184 ordered slot | callsite/body | thread affinity and mutable state | resource / GL dependency | title vs first world | decision |
| --- | ---: | --- | --- | --- | --- | --- |
| Vanilla Shader Loader | 425.480 ms | `GameRenderer$1` preparable listener -> `GameRenderer.reloadShaders(...)` apply | apply is render/game executor; replaces live shader instances and runs NeoForge shader registration callbacks | preparation reads shader resources; apply constructs/links GL shader programs and publishes live shaders | required for the first usable title render; title GUI render types consume core shaders | **NO-GO**: existing prepare/apply split already removes resource preparation; remaining apply is GL + mutable publication + arbitrary mod callback |
| Veil 4.1.4 | 293.468 ms | `ShaderManager` for `pinwheel/shaders/program`; `reload()` calls `prepare(...) -> barrier.wait -> apply(...)` | prepare worker tasks parse/preprocess GLSL; it first obtains GL capabilities on Veil's render-thread executor. apply compiles programs, mutates `shaders`, closes/replaces old programs, then posts Veil compile callbacks | `ResourceManager` and shader definitions in prepare; OpenGL compilation/link/publication in apply | individual Veil programs may be world-oriented, but shader-compile event order/compat state is observable before title; exact pack includes Iris Veil Compat and it intercepts shader sources during this apply | **NO-GO**: Veil already has the desired prepare/commit split. Remaining serial slot is GL/program publication + callback, not GL-free preparation |
| ClientModLoader | 572.029 ms | `ClientModLoader.begin()` registers `ClientModLoader::onResourceReload`; `startModLoading(...) -> barrier.wait -> finishModLoading(...)` | start/finish use `ModWorkManager.parallelExecutor`; lifecycle work can enqueue sync/main-thread tasks. `finishModLoading` mutates `loading/loadingComplete` and schedules `mc.options.load(true)` | outer method receives ResourceManager but does not do a pure resource transform; mod lifecycle callbacks are arbitrary and may observe client/render resources/state | required before normal initial screen/event-bus completion | **NO-GO**: post-barrier work is mod lifecycle completion, not a barrier-only wait; deferral/reorder changes lifecycle and title/error/warning behavior |
| LevelRenderer | 32.981 ms hosted | `LevelRenderer.onResourceManagerReload -> initOutline -> PostChain.load(entity_outline) -> resize`; optional transparency path | render thread; publishes `entityEffect` / post-chain targets | `PostChain.load` consumes resources and creates render targets/program state; GL-sensitive | vanilla entity outline is first-world/render-only, not title-required | **NO-GO now**: hosted ceiling is only ~33 ms. #85 physical evidence remains ~2.061 s listener / ~1.458 s `entity_outline`, but that path is hardware-sensitive and compatibility-safe first-use forcing was not proven. Do not reopen the old ~6.5 s number |
| Flywheel 1.0.6 | 166.733 ms | `FlwProgramsReloader.onResourceManagerReload -> FlwPrograms.reload(manager); NoiseTextures.reload(manager)` | current reloader is synchronous/apply-only on render thread. `FlwPrograms.reload` clears/replaces static program holders and `FlwPrograms.SOURCES`; `NoiseTextures.reload` replaces static `BLUE_NOISE` | `ShaderSources(ResourceManager)` is GL-free enumeration/read/parse; `NoiseTextures.reload` creates/binds a `DynamicTexture` and calls `RenderSystem`/GL | Flywheel program sources/noise are world-render data, not needed to paint the vanilla title, but deferring them to first world would shift startup cost into gameplay | **NO runtime candidate yet**; see bounded reopening below |

## The one bounded prepare/commit possibility: Flywheel ShaderSources

Flywheel 1.0.6 is the only audited owner with a locally clean GL-free subphase still executed after the global preparation barrier:

- `FlwProgramsReloader` is only a `ResourceManagerReloadListener`, so its work is apply-only.
- `FlwPrograms.reload` resets `InstancingPrograms`/`IndirectPrograms`, constructs `new ShaderSources(resourceManager)`, stores it in `FlwPrograms.SOURCES`, then builds new lazy program compiler state.
- `ShaderSources` calls `ResourceManager.listResources("flywheel", ...)`, opens source resources, reads UTF-8 text, recursively parses `#include` relationships, and stores `LoadResult` objects. `SourceFile` is explicitly an immutable parsed representation. There are no GL calls in that constructor/parser.
- In PR #184's exact-pack log, Flywheel itself reports `Loaded 106 shader sources in 105.736 ms` on the Render thread, inside the 166.733 ms ordered Flywheel slot. This is an attribution marker, not a TTMM saving claim.
- The same listener subsequently runs `NoiseTextures.reload`, which closes/replaces a `DynamicTexture`, activates/binds texture state and calls `RenderSystem.texParameter`. That portion must remain render-thread commit work.

A technically plausible future candidate is therefore: prepare a detached `ShaderSources` from the reload's `ResourceManager` on the stock preparation executor, wait on the stock barrier, then preserve Flywheel's exact current apply order while substituting the prepared object at the `new ShaderSources(resourceManager)` point. **Do not** defer the source load to first world.

I am not opening that runtime PR from this audit because source-level purity alone is not enough to call the BootOptim interposition safe. Flywheel owns the reloader and does not expose a supported prepared-state API. A BootOptim implementation would need to override/interpose an external listener/default `reload` method and redirect an internal constructor. Before accepting that maintenance/compatibility surface for an observed ~105.736 ms hosted subphase, it must prove all of the following:

1. exact Flywheel 1.0.6 class/method fingerprints match before activation and fail open on drift;
2. disabled mode is byte-for-byte equivalent in listener timing/order and does not duplicate `onResourceManagerReload`;
3. prepared `ShaderSources` is keyed to the exact reload/`ResourceManager` and cannot leak across initial or in-world reloads;
4. no Flywheel static program holder is cleared during preparation; all current `setInstance(null)`, replacement and `NoiseTextures.reload` publication remains on the original apply/render thread in original order;
5. resource-pack reload while a world is active produces the same source identities/errors and render result;
6. exact-pack markers show `flywheel_sources_prepare_start/end`, `flywheel_commit_start/end`, global preparation/all-done, title-open/presented, and first-world render/use, with no first-world latency regression;
7. hosted A/B shows a reduction in ordered Flywheel post-turn/`allDone`/menu critical wall, not merely the disappearance of the Render-thread log duration.

Only if those conditions hold is a physical laptop run justified, and only after hosted exact-pack establishes a coherent critical-path movement. The current ~106 ms hosted ceiling alone does not justify physical escalation.

## Reopening conditions for the other owners

- **Shader Loader:** reopen only if a source-level change isolates a material, immutable, callback-free preparation step that is currently still after the barrier. Do not move `reloadShaders` GL creation or `RegisterShadersEvent`-style callbacks off the render thread and do not defer shaders used by the first title frame.
- **Veil:** reopen only if Veil upstream exposes or moves additional CPU-only work out of `apply`; its 4.1.4 `ShaderManager` already prepares parsed/preprocessed GLSL before the barrier. Any candidate must leave GL compilation/link, mutable shader-map replacement and compile callbacks on the render thread.
- **ClientModLoader:** reopen only with a named, owner-approved lifecycle subtask proven pure and independent of mod callback/event/options ordering. The current 572 ms post-turn interval is not a generic scheduler target.
- **LevelRenderer:** reopen only after current repeated evidence again shows >1–2 s critical `entity_outline` wall and a complete consumer/mixin audit proves a render-thread first-use force boundary with no black/frozen title, no in-world reload semantic change and no first-world regression.

## Decision

**No PR is opened from this branch.** The five audited owners do not currently expose a BootOptim-owned, compatibility-proven boundary with enough evidence for a runtime candidate. Shader Loader and Veil are already split around GL-bound apply work; ClientModLoader is arbitrary lifecycle completion; LevelRenderer's current hosted slot is tiny and its physical wall is hardware-sensitive; Flywheel contains a real GL-free source-parse subphase, but extracting it requires unsupported third-party listener interposition for an observed ~106 ms hosted ceiling. The Flywheel condition above is the only concrete reopening path from this audit.

No laptop request. No improvement claim by task sum, source count, inclusive listener duration, or CPU count.