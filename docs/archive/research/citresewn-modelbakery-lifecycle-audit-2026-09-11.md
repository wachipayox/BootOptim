# CITResewn ModelBakery lifecycle audit — 2026-09-11

Status: **CLOSED / NO-GO FOR A NEW BOOTOPTIM CITRESEWN OPTIMIZATION**

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This Agent 123 audit is deliberately documentation-only. It adds no scheduler, cache, model reuse, reload listener, resource wrapper, Mixin or configuration change. It does not reopen the retained CITResewn base-item-model cache from #151, the side-load memoization line, or generic ModelManager parallel/lazy designs.

## Question

PR #221 left a large exclusive residual in `ModelBakery.<init>` after stock blockstate/item/parent work and known FFAPI/NeoForge hooks were removed. Source/log attribution points to CITResewn lifecycle work injected into the constructor. The question here is whether that work contains one safe, material boundary which BootOptim can cache, reorder or configure without changing resource-pack order, reload-generation state, error/callback order, unbaked-model insertion or CIT gameplay semantics.

The answer for the current pinned exact pack is **no**. The only demonstrated narrow immutable reuse boundary is already #151. The remaining work is coupled to current-generation resource resolution, mutable CIT construction/publication and pre-parent model insertion.

## Exact-pack runtime identity versus public source

The exact fixture remains release `exact-pack-2026-09-02-v1`, asset `bootoptim-exact-pack.zip`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

The deployed CITResewn binary is intentionally not identified by a public-source semantic version. In the valid hosted #221 artifact it reports `CITResewn 0 (citresewn)` and is loaded through Sinytra Connector. More importantly, the integration authority contains exactly one retained TypeItem bridge, an optional `@Pseudo` target for `schm.shsupercm.citresewn.defaults.cit.types.TypeItem`, and the valid exact-pack log proves that bridge is actually executing:

`BOOTOPTIM_CITRESEWN_BASE_MODEL_CACHE requests=3960 hits=3944 misses=16 hit_rate_percent=99 entries=16 resource_open_bypasses=3944`

Therefore the current exact-pack binary demonstrably contains the historical TypeItem shape used by #151. Public NeoForge `CancriRecoleta/CITResewn@8cca0f127f3898472e2109f6ac6a797253756893` (`0.7.22`, `com.github...`) and archived upstream `SHsuperCM/CITResewn` 1.21/1.21.1 source (`1.2.2`, `shcm.shsupercm...`) are useful semantic references, but neither is proven byte-for-byte/source-commit identity for the fixture. This audit does not retarget runtime code on the basis of a public package name.

That distinction matters: the result below relies on the exact runtime hook/marker plus source-shape invariants shared by the public lineage, not on a false claim that a public Git commit is the deployed JAR.

## Real constructor hooks and lifecycle

The CITResewn lineage has two relevant synchronous constructor hooks.

### 1. Active-CIT reload at the front of ModelBakery

The core ModelLoader/ModelBakery mixin enters `ActiveCITs.load(resourceManager, profiler)` at the beginning of model-loader construction. `ActiveCITs.load` is not just parsing. In stock order it:

1. enumerates `CITDisposable` entrypoints and invokes `dispose()`;
2. unloads every registered CIT type container;
3. clears the previous active global-property sets, calls their handlers, then publishes `active = null`;
4. loads and merges global properties from the current `ResourceManager`, then calls global-property handlers;
5. enumerates/reads/parses CIT property resources through `PackParser.parseCITs`;
6. constructs conditions/types, resolves type-specific resources against the current resource stack and applies fallback processing;
7. groups CITs by type, applies weight ordering and loads each type container;
8. publishes the new `ActiveCITs.active` only after the load is complete and only when CITs exist.

Those operations establish observable reload-generation state and callback/error order before vanilla model construction proceeds.

### 2. Item-CIT unbaked models before vanilla item/parent completion

The item type mixin runs later in the same constructor, at the transition into the item-model portion. For every loaded item CIT it calls `TypeItem.loadUnbakedAssets(currentResourceManager)` and immediately inserts each resulting mutable unbaked model into the loader's unbaked/model-to-bake maps before parent resolution.

`TypeItem.loadUnbakedAssets` and helpers perform current-generation resource lookups/opens, JSON model parsing, override enumeration, texture and relative-path resolution, mutable texture/parent/override rewriting and synthetic generated identifiers. A process-global `GENERATED_SUB_CITS_SEEN` set participates in duplicate/synthetic identifier selection during that pass and is cleared after the insertion loop. Per-CIT failures are caught/logged in iteration order and model loading continues.

At bake return, a separate CITResewn hook links the freshly baked models back into each TypeItem (`bakedModel` / submodel override list) and then releases `unbakedAssets`. This post-bake link is gameplay state publication and is not part of the constructor residual.

## Valid exact-pack evidence and non-inclusive accounting

The reusable hosted evidence is #221 run `34295167385`, head `138f45a18529693dafb4e2ce939de07ad84e2426`. Its artifact reached the menu with the exact 14/14 selected resource packs in order, `reload_count=1`, block atlas `8192x8192x2`, and zero BootOptim Mixin errors.

Its structured trace measures `model_bakery_prepare` from `52,197,732,740 ns` to `62,992,929,510 ns`, or `10,795.197 ms`. The constructor-local profiler independently reports `10,794.186 ms`; the ~1 ms difference is boundary/observer placement and is not added to anything.

The constructor-local scopes are disjoint:

- blockstate registration: `4,189.317 ms`;
- vanilla item/dependency registration: `149.605 ms`;
- parent resolution: `621.302 ms`;
- missing model: `1.143 ms`;
- model-groups getter: `0.005 ms`;
- two vanilla special models: `0.037 ms`;
- FFAPI plugin init: `0.012 ms`, extra models `0.000 ms`;
- NeoForge `RegisterAdditionalModels`: `198.096 ms` for 1,069 IDs;
- additional-model `getModel`: `5.275 ms`;
- additional-model dependency/register: `1.511 ms`.

After subtracting only those disjoint scopes, #221 leaves **`5,627.884 ms` exclusive constructor residual**. That value is the ceiling for all uninstrumented constructor work combined; it is not itself an optimization estimate.

The exact log gives two useful causal bounds without pretending they are internal timers:

- 7,920 legacy-name diagnostics emitted inside CIT parsing run from `00:31:55.212` through `00:31:58.098`, an observed **2.886 s lower-bound span** inside the front-of-constructor CIT lifecycle. Logging is interleaved with property parsing; the span is neither the complete parser time nor recoverable wall.
- `Loading item CIT models...` appears at `00:32:02.346`; the constructor summary/cache report is emitted at `00:32:05.898/05.899`, a **3.552 s gross tail window**. Source order places vanilla item/dependency registration, NeoForge additional-model work and parent resolution after this injection. Subtracting those known disjoint later scopes (`149.605 + 198.096 + 5.275 + 1.511 + 621.302 = 975.789 ms`) gives **at most ~2.576 s** for item-CIT unbaked-model loading plus any other still-uninstrumented tail work. It is deliberately reported as an upper bound, not an exact TypeItem timer.

Correspondingly, at least ~`3.052 s` of the `5.628 s` residual lies outside that maximum item-CIT tail. Source placement and the 2.886 s diagnostic span identify `ActiveCITs.load`/pack parsing as the dominant owner there, but this audit does not fabricate a split between enumeration, byte reads, property decoding, condition normalization, fallback/weight work and callbacks without an internal timer.

The important negative measurement is already exact: the repeated direct base-item JSON reads are **not** the remaining unknown. #151 is active in this very run and reports 3,944/3,960 hits plus 3,944 resource-open bypasses. Extending that cache to custom CIT models or across reload generations would cross a different semantic boundary.

Finally, the post-bake publication boundary is visible separately: `Linking baked models to item CITs...` appears at `00:32:14.490`, after the roughly 8.586 s model-bake task. It must remain linked to the current baked registry generation; it is neither constructor residual nor a candidate for prepublication.

## Phase ownership: what may and may not move

### Enumeration and reads

`PackParser.parseCITs` discovers properties through the current `ResourceManager`. Resource-pack precedence and selected-pack order determine which resources and paths are visible. The read/parse loop also determines diagnostic/error order. A persistent result keyed only by CIT paths, timestamps or individual file hashes would be insufficient identity because visibility depends on the ordered resource-pack stack and global properties.

A whole-stack strong fingerprint could in principle identify bytes, but computing it does not by itself make replay safe: parsing constructs type/condition objects that bind registry/resource-derived state and emits errors while callbacks and unload/load transitions surround it. Caching those live objects across generations is rejected.

### Parsing and normalization

Property parsing feeds condition/type construction, `TypeItem.load` path resolution, fallback processing, weight ordering and type-container population. These are not an immutable byte-to-byte transform in the existing API. They capture current registries, resource resolution and mutable per-CIT fields; changing when they run also changes warning/error order relative to type disposal/load callbacks.

A future direct CITResewn fork/API could expose a deliberately immutable syntax IR before resource resolution, but the current exact binary provides no proven versioned contract for that product and no evidence that such a pure subphase owns enough of the 5.628 s residual to justify a fork.

### Publication and callbacks

`ActiveCITs.load` explicitly disposes/unloads old state, calls old-global handlers, clears `active`, calls new-global handlers, loads type containers and finally publishes the new manager. Delaying publication alone does not isolate work: moving parse earlier would make callbacks/error generation occur under a different active generation; keeping callbacks at commit would require replaying a currently interleaved state machine.

Therefore `prepare immutable data -> commit active state` is not available as a safe external BootOptim transformation without first changing CITResewn itself to define that boundary.

### Item models

Item CIT model creation is generation-local and mutates JSON model objects before inserting them into ModelBakery maps ahead of parent resolution. Synthetic identifiers depend on traversal/duplicate history. Reordering, parallelizing or deferring this loop can change identifier assignment, error order, map insertion order and parent/override resolution. Persisting its `BlockModel` objects would also violate the no-live-model-across-reloads rule.

The only narrow exception is #151: direct base vanilla item models are parsed only to inspect override metadata and are reused reload-locally. That mechanism is already present and measured; this audit does not clone or broaden it.

## Candidate evaluation

**Persistent parsed-CIT/condition cache — reject.** Even with a strong ordered-pack fingerprint, the cached result would contain or replace construction of generation-sensitive type/condition state and would skip/reorder parse errors and lifecycle callbacks. Reconstructing fresh live state from a new immutable serialization would amount to a CITResewn internal redesign, not a safe BootOptim cache.

**Persistent/custom unbaked-model cache — reject.** Custom CIT models are mutated for textures, parents, overrides and synthetic identifiers, then inserted into the current ModelBakery generation. Reuse crosses resource and model generations and is explicitly outside #151.

**Move `ActiveCITs.load` earlier / parse on another worker — reject.** The method begins with disposal/unload and changes active/global state. Running it earlier or concurrently exposes a different generation to callers and changes callback/error timing. Splitting it externally would require a versioned immutable prepare API that the exact binary does not expose.

**Delay ActiveCITs publication while parsing earlier — reject on current API.** Publication is only the final assignment; the observable state transition starts much earlier at disposal/unload, global handler invocation and `active = null`. Moving the assignment alone does not make the preceding work pure.

**Disable CITResewn or its item type — reject.** That changes resource-pack/CIT gameplay semantics.

**Suppress legacy diagnostics / rewrite the resource pack as a performance fix — reject for this assignment.** Prior #97/#104 experiments already isolated that lane; resource migration was mechanically viable but did not validate a BootOptim performance win. It is not a replacement for the model-lifecycle work measured here.

## Decision

**NO-GO: close the current CITResewn lifecycle residual as a BootOptim optimization target. Keep #151 as the sole CITResewn model-read optimization and do not add another cache, reordering or configuration workaround.**

This decision is based on the exact pinned workload, not on a belief that CIT parsing can never be optimized. Reopen only if one of two facts changes: (1) the exact-pack CITResewn JAR is upgraded and fingerprinted to a public/source-controlled implementation that exposes a versioned immutable prepare product, or (2) a direct CITResewn source change introduces that product while explicitly preserving ordered resource resolution, callbacks/errors, reload generations and pre-parent model insertion. At that point a new opt-in exact-pack profile should time the internal prepare/commit phases non-inclusively before any A/B candidate is implemented.

No new hosted run is started by this PR because it contains no executable change. The semantic/resource gate is the already-valid #221 exact-pack run described above; timings are attribution evidence only and no new savings claim is made.
