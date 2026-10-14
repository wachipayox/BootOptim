# Optional-resource / early-menu ownership audit — 2026-09-05

Status: **LIMITED / NO-GO with a precise architectural reopening condition**

Base: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

Scope: agent 21 audit of exact-pack resources that might look optional before a usable title screen. This does **not** add another post-FancyMenu timestamp profiler (#122 owns that), does not repeat renderer defer (#95/#102), does not bypass FancyMenu `preLoadAll` (#116), and does not revisit the rejected cooperative wait (#117) or shader/Voxy probes (#114/#118).

## Decision

There is no safe BootOptim-side production candidate in the currently exposed lifecycle.

The strongest new structural opportunity is inside FancyMenu itself: the exact pack has a large `randomonlyfirsttime` universal-background group, while FancyMenu 3.9.0 globally preloads resources before it chooses the single active random layout. Exact fixture evidence shows 22 enabled `bg_*` random layouts in group 0: 19 panoramas, 2 slideshows, and 1 MCEF-video layout. Only one member is later selected for the menu/session, yet `ResourcePreLoader` owns a flat source list with no layout provenance. The hosted exact-pack run also reports 20 panorama registrations / 120 panorama waits: this includes the 19 enabled panorama backgrounds plus the disabled panorama layout `title_screen_layout_2.txt`, confirming that preload membership is not equivalent to active-layout membership.

That is a genuine avoidable-work shape, but **not a safe current hook**. Selecting/pruning before stock `preLoadAll` would move FancyMenu's random-layout decision earlier, consume/alter random state or cache semantics, bypass condition/enablement resolution, and leave no correct lifecycle for a source whose layout becomes active after mod/resource reload or other invalidation. `ResourcePreLoader` has no owner mapping or prepare/commit token with which BootOptim could prove equivalence.

Reopen only if FancyMenu itself, or an explicitly controlled fork, gains a provenance-aware selection + preload API described below. No hosted A/B or physical run is justified before that architecture exists.

## Exact evidence used

### Fixture / runtime

Public exact-pack smoke artifact from workflow run `33976030376`, artifact `9972386153` (`exact-pack-result-smoke-1`) was inspected. Its resource-selection check is valid and contains the exact enabled external ZIP state. Relevant observed initial-reload sequence:

- Veil 4.1.4 shader/reload work executes before FancyMenu.
- FancyMenu `preLoadAll` starts after the other reload listeners have reached their turns.
- `BOOTOPTIM_FANCYMENU_WAIT_CPU`: `wait_calls=132`, `ordinary_calls=2`, `slideshow_calls=10`, `panorama_calls=120`, `preload_wall_ms=4015.712`, `preload_cpu_ms=3293.221`, `slideshow_wall_ms=719.885`, `panorama_wall_ms=3193.165`.
- The retained six-face production optimization reports 20 panoramas / 120 suppliers.
- Immediately after preload, FancyMenu's native-video reset reports `backgroundsReset: 30, stoppedPlayers: 0, videoResourcesReleased: 0`.
- FancyMenu reload finishes and hosted `after_preload_to_title` is about 119.5 ms.

These are hosted Linux/llvmpipe measurements. They establish software ownership/counts, **not** a physical-Windows savings ceiling and not the cause of the older 4–37 s physical tail.

Public PR #89 artifact `9887316618` (`fancymenu-mcef-static-audit`, run `33739976202`) contains the exact FancyMenu 3.9.0 JAR and fixture customization files. Exact FancyMenu JAR SHA-256 is `8e1c68f2c91aed02057209252bbe221bf3b019c4e82fb20fe35809bac2c08db8`, matching #116/#109.

### Exact menu-background inventory

Inspection of the exact `fancymenu_data/customization` files finds 22 enabled `bg_*` universal random backgrounds in the same `randommode=true`, `randomgroup=0`, `randomonlyfirsttime=true` family:

- 19 local panorama layouts;
- 2 slideshow layouts (`bg_slide_portas`, `bg_slide_raidarea`);
- 1 MCEF-video layout (`bg_arbol_carton`).

There is also `title_screen_layout_2.txt`, disabled in the exact fixture, containing another panorama. The runtime preloader reports 20 panoramas total, consistent with the preload registry retaining that disabled panorama too.

This matters because the expensive preload registry is broader than the set of resources required by the one active random background.

### FancyMenu random selection occurs after preload and chooses one member

Exact 3.9.0 bytecode for `ScreenCustomizationLayer.RandomLayoutContainer` shows:

- one static `CACHED_PICKS` map keyed by a group identifier derived from sorted `runtimeLayoutIdentifier`s;
- `isOnlyFirstTime()` is driven by layout `randomOnlyFirstTime`;
- `getRandomLayout()` first reuses the cached runtime-layout identifier when valid;
- for a new uncached group it chooses one index with `MathUtils.getRandomNumberInRange(0, size - 1)`, stores that layout identifier in `CACHED_PICKS`, and returns that one layout;
- `ScreenCustomizationLayer.onInitOrResizeScreenPre` builds random groups and adds only `container.getRandomLayout()` to `activeLayouts`.

Therefore the title path does not compose all 22 background layouts. It chooses one group member, and the exact group's `randomonlyfirsttime=true` policy retains that choice through its normal session/cache lifetime.

### ResourcePreLoader has no provenance

Exact `ResourcePreLoader.preLoadAll(long)` starts from `getRegisteredResourceSources(null)`, which reads the flat serialized `FancyMenu.getOptions().preLoadResources` value. It then processes each registered source as panorama, slideshow, or ordinary resource, interleaving creation/wait/error handling as documented by #116.

The exact class exposes `addResourceSource/removeResourceSource`, but no runtime relation from a source back to `Layout`, random group, layout requirements, or `activeLayouts`. The source registry is editor/options-managed state, not an ownership graph.

This is the key blocker. BootOptim cannot infer that an arbitrary preloaded file is dispensable merely because a customization file is inactive today; it cannot prove which source belongs exclusively to which layout or how to react when layout eligibility changes.

## Inventory and lifecycle decisions

### 1. FancyMenu panoramas / slideshows — real mutually-exclusive ownership opportunity, current API NO-GO

**Owner:** FancyMenu resource handler / preloader; eventual consumer is the selected `MenuBackground` renderer.

**Current lifecycle:** resource sources are globally registered and `preLoadAll` starts/waits/validates them during resource reload; only later does `ScreenCustomizationLayer` resolve enabled layouts/requirements and select one random group member.

**Why apparently optional:** 19 panorama + 2 slideshow members are mutually exclusive with the other members of the same exact random-only-first-time group for a single title composition. The runtime additionally includes one disabled panorama in the flat preload set.

**Why not safe to prune from BootOptim:** no source→layout ownership metadata; selection happens later; early selection can change RNG/cache ordering; conditions and enable/disable state can change; manual/mod reload invalidates layout state; skipping a source changes FancyMenu's stock timeout/failure lifecycle and can move a later first load into interactive use.

**Critical-path evidence:** hosted preload owns ~4.016 s wall / ~3.293 s current-thread CPU, of which panorama waits are ~3.193 s wall and slideshow waits ~0.720 s. Those inclusive family totals are not equivalent to the recoverable cost of unselected layouts and are not a claim about the older physical tail.

### 2. FancyMenu native video / MCEF-video background — no startup resource to defer in current fixture

**Owner:** FancyMenu native/MCEF background lifecycle plus MCEF for browser video.

Exact hosted reset after initial preload reports 30 background objects but `stoppedPlayers=0` and `videoResourcesReleased=0`. This is positive evidence that there is no already-created native-video player/resource payload to harvest at that boundary in the exact startup. #116 also establishes fallback/thread-affinity constraints. Do not build a startup defer around this family without new evidence of an actual initialized resource on the critical path.

### 3. FancyMenu title audio — readiness is not a widget gate, creation affinity prevents generic worker defer

#116 exact bytecode already proves title audio retries when not ready, while OGG/OpenAL creation has render-thread affinity. The consumer-side retry is not a producer-side continuation API. No new safe boundary exists here.

### 4. Entity/player renderers, item models, textures/atlases — NOT world-only in this exact menu

Exact active `Elmejormenu_prueba.txt` contains 12 `fancymenu_customization_player_entity` elements and 6 item elements, in addition to 14 vanilla-button bindings. Exact FancyMenu bytecode builds/renders `WrappedFancyPlayerWidget` for player-entity elements, and `ItemElement.renderScaledItem` calls Minecraft `GuiGraphics.renderItem(ItemStack, ...)`.

Therefore entity/player rendering and item/model/texture infrastructure has a real **title-screen consumer** in this pack. A resource is not safely deferrable to first world merely because vanilla title would not use it. This exact content closes broad atlas/model/renderer “unused before world” arguments and reinforces the physical rejection of #95/#102.

### 5. Veil shaders/framebuffers/post pipelines/render types — explicit ordering, attribution belongs to #122

Exact upstream Veil 4.1.4 commit is `9a6d4a3e884b3cecad716d1005ae24c0cd19db18`. `VeilRenderer` injects its shader modification listener at the front because it **must finish before the game renderer**, puts `ShaderManager` before vanilla shaders so replacement works, then registers framebuffers, post-processing, dynamic render types, flare shells, and renderer reload handling.

The exact hosted log has a multi-second interval around shader/reload output and later reports 3 framebuffers / 6 post pipelines / 4 render types, but a log interval is neither exclusive CPU nor recoverable critical-path wall. #122 is already instrumenting barrier/listener/apply/title/present boundaries; duplicating timing probes here would violate the non-overlap rule. The source ordering alone is enough to reject blind post-title deferral.

Reopen a specific Veil resource only if #122 or later source evidence identifies a named listener/resource on the actual apply/turn critical path **and** its exact consumer graph proves it is not needed by Iris Veil Compat, FancyMenu's active player/item/background composition, or another pre-title renderer. Any GL object creation/commit remains on the render thread.

## Required architecture to reopen the FancyMenu opportunity

This is materially different from #116's rejected “return early from `preLoadAll`” and #83's broader overlap. The necessary change belongs in FancyMenu (or a confirmed editable fork), not as a BootOptim guess over private state.

A viable API/state machine must provide all of the following:

1. **Deterministic selection token before preload.** Resolve enabled layouts, requirements, screen/universal applicability, random group membership and the exact random pick once. The same token is consumed by `ScreenCustomizationLayer`; screen init must not reroll. Selection must preserve FancyMenu's existing RNG/cache semantics rather than merely produce the same distribution.
2. **Source provenance.** Every preloaded source carries owner metadata: global/non-layout, layout ID(s), and random group. Shared sources must remain selected if any selected/global owner requires them.
3. **Prepare only the selected closure.** `prepare(token)` starts/waits/validates selected-layout sources plus all global/shared sources in stock relative order and with the same timeout/failure semantics. Unselected sources are not silently marked successful.
4. **Explicit state for unselected resources.** A later eligibility/invalidation event either rebuilds a token through the normal reload lifecycle or performs a defined lazy prepare before consumer use. It must not inject uncontrolled disk/decode work into gameplay.
5. **Invalidation/reentry.** Clear/rebuild selection and provenance on FancyMenu mod reload, layout enable/disable/requirements changes, resource-pack reload, and any operation that currently clears `CACHED_PICKS`. Reentrant callbacks see a coherent token/state, not partially-mutated ordinary maps.
6. **Thread affinity.** Decode may use FancyMenu's existing workers; texture registration/upload, player creation and OpenAL-affine creation remain on their existing owner threads. No OpenGL work moves to workers.
7. **Fail-open.** Version/provenance mismatch, ambiguous ownership, token invalidation or prepare failure before reload commit executes the complete stock preload path for that reload. A JVM/config kill switch restores stock behavior.

Only after those invariants exist would a hosted exact-pack gate be meaningful. It should assert: exact resource-pack selection, same chosen random layout identity under a controlled deterministic selection test, all 14 button bindings, 12 player entities and 6 items visible/renderable, selected background eventually complete, no unselected source loss after a forced reload/invalidation, first-world entry and disconnect/re-entry smoke, zero new Mixin/FancyMenu/renderer failures, and mechanism counters proving the source closure was actually reduced. A physical Windows visual/gameplay gate follows only after hosted success.

## Rejected shortcuts

Do not:

- derive “unused” sources from filename/path conventions or the current customization files and delete them from `preLoadResources`;
- call `getRandomLayout()` early from BootOptim and assume identical random behavior;
- skip all non-selected sources without a provenance graph and invalidation contract;
- treat the 20th disabled panorama as permission for a hard-coded pack cleanup in production code;
- defer entity/player/item renderer or model/atlas work to world entry, because the exact title layout directly consumes it;
- move Veil/OpenGL work off the render thread or reorder its shader listeners around vanilla;
- infer that the hosted ~4 s FancyMenu preload or the Veil log gap explains the older 4–37 s physical tail.

## Reopening condition

**NO-GO until there is a FancyMenu-owned (or controlled-fork) provenance-aware selection/preload API that binds one stable random-layout token to a source-ownership closure and defines invalidation/fail-open semantics.**

That API is the precise condition that would turn the exact pack's 22-way mutually-exclusive background group from an observed waste shape into a testable candidate. Without it, the structural lifecycle risks are larger than the evidence for recoverable TTMM.

No physical run is requested. No production code is implemented.

## Related evidence

- PR #95 / #102 — renderer defer rejected by physical black/frozen-menu behavior.
- PR #112 — MCEF pre-gameplay boundary/reentry audit.
- PR #114 / #118 — shader fallback and Voxy save reduced to milliseconds, not a tens-of-seconds cause.
- PR #116 — exact FancyMenu 3.9.0 consumer fallbacks but monolithic `preLoadAll`; generic runtime defer rejected.
- PR #120 — older variable post-preload physical tail remains unattributed.
- PR #122 — current diagnostic owner for reload barrier/turn/listener/title/first-present attribution; deliberately not duplicated here.
- Exact-pack smoke run `33976030376`, artifact `9972386153`.
- Exact FancyMenu static audit run `33739976202`, artifact `9887316618`.
- Veil 4.1.4 upstream commit `9a6d4a3e884b3cecad716d1005ae24c0cd19db18`.
