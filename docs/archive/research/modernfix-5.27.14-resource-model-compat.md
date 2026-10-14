# ModernFix 5.27.14 resource/model compatibility research — MC 1.21.1 / NeoForge

Status: **RESEARCH COMPLETE / DIAGNOSTIC FOLLOW-UP REQUIRED FOR THREE PACK VALUES**

Scope:

- exact target environment: Minecraft 1.21.1, NeoForge 21.1.248, ModernFix `5.27.14+mc1.21.1`;
- BootOptim integration base: `agent/integration-current` at `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`;
- exact-pack evidence source: diagnostic PR #69 and the 2026-09-01 slow-laptop run;
- ModernFix source snapshot used below: `embeddedt/ModernFix@f8f1b092bf64d9ec29222d502d2e67f4304dc221`, the 1.21.1 first-parent snapshot corresponding to the June 16, 2026 `5.27.14+mc1.21.1` release line;
- no production ModernFix replacement is implemented by this work.

The published artifact is independently identified by Modrinth/CurseForge as `modernfix-neoforge-5.27.14+mc1.21.1.jar`, MC 1.21.1, NeoForge, released 2026-06-16.

## Executive result

PR #69 did **not** fail because `ModernFixEarlyConfig#getEffectiveOptionForMixin(String)` is absent. That method is public in 5.27.14. The failure is later in the probe: 5.27.14's `Option` is still a directly boolean object and exposes `isEnabled()`. It does **not** have the later typed-option API `asBoolean().getValue()`. The stability probe is also version-skewed: 5.27.14 has no `ModernFixMixinPlugin.activeFeatureLevel()` method; the real value is the public static field `ModernFixEarlyConfig.ACTIVE_FEATURE_LEVEL`.

The exact source defaults are:

| Option | 5.27.14 source default | Additional gate that matters |
| --- | ---: | --- |
| `mixin.perf.dynamic_resources` | **false** | client/mixin compatibility gates; no built-in mod blacklist for the category |
| `mixin.perf.resourcepacks` | **true** | `FilePackResourcesMixin` requires **BETA**, so default GA can veto that mixin even while the option is true |
| `mixin.perf.faster_texture_stitching` | **true** | built-in mod override disables it for OptiFine; per-call fallback for small atlases or abnormal loading state |
| `mixin.perf.deduplicate_wall_shapes` | **true** | built-in mod override disables it for DashLoader |

For the exact PR #69 laptop run, one result is already stronger than a configuration guess: **ModernFix's `perf.dynamic_resources.ModelManagerMixin` was not applied.** PR #69 recorded 44,103 executions of the stock `ModelManager.lambda$loadBlockModels$8` per-resource task and 11,435 stock blockstate resource-stack tasks. In 5.27.14, `ModelManagerMixin` replaces the `loadBlockModels` continuation with a lazy `Maps.asMap(...)` loader and redirects `reload` away from stock `loadBlockStates`. Merely enumerating ~44k resource keys would be compatible with dynamic resources; executing the stock per-resource load lambda for all ~44k resources is not. This is independent behavioral evidence, not the failed reflection probe.

That behavioral result proves the relevant dynamic-resources mixin was inactive, but the old run still cannot distinguish whether the category was simply at its source default `false`, was user/JVM-controlled, or failed for another launch-specific reason. The corrected diagnostic probe records that distinction directly.

The other three exact-pack effective option values remain **unproven by the old run**. Their source defaults are known, and their per-mixin compatibility gates are known, but a corrected exact-pack diagnostic run is required before calling their pack values demonstrated.

## Exact 5.27.14 configuration API

Primary source:

- `src/main/java/org/embeddedt/modernfix/core/ModernFixMixinPlugin.java`
- `src/main/java/org/embeddedt/modernfix/core/config/ModernFixEarlyConfig.java`
- `src/main/java/org/embeddedt/modernfix/core/config/Option.java`

Permanent source anchors:

- <https://github.com/embeddedt/ModernFix/blob/f8f1b092bf64d9ec29222d502d2e67f4304dc221/src/main/java/org/embeddedt/modernfix/core/ModernFixMixinPlugin.java>
- <https://github.com/embeddedt/ModernFix/blob/f8f1b092bf64d9ec29222d502d2e67f4304dc221/src/main/java/org/embeddedt/modernfix/core/config/ModernFixEarlyConfig.java>
- <https://github.com/embeddedt/ModernFix/blob/f8f1b092bf64d9ec29222d502d2e67f4304dc221/src/main/java/org/embeddedt/modernfix/core/config/Option.java>

At mixin-plugin construction time ModernFix does:

```java
config = ModernFixEarlyConfig.load(new File("./config/modernfix-mixins.properties"));
```

The effective rule API is:

```java
public Option getEffectiveOptionForMixin(String mixinClassName)
```

The argument is a sanitized mixin path without the leading `mixin.` configuration prefix, for example:

```text
perf.dynamic_resources.ModelManagerMixin
perf.resourcepacks.FilePackResourcesMixin
perf.faster_texture_stitching.StitcherMixin
perf.deduplicate_wall_shapes.WallBlockMixin
```

`Option` in this release exposes the relevant state directly:

```java
boolean isEnabled()
boolean isUserDefined()
boolean isModDefined()
boolean isOverridden()
String getName()
Collection<String> getDefiningMods()
```

There is no `Option.asBoolean()` and no boolean wrapper `getValue()`.

### Why PR #69 emitted `NoSuchMethodException`

PR #69 successfully looked up `getEffectiveOptionForMixin(String)`, invoked it, and then attempted:

```java
option.getClass().getMethod("asBoolean")
booleanOption.getClass().getMethod("getValue")
```

Those methods belong to a later typed-option design, not to ModernFix 5.27.14. Therefore every per-option probe failed with `NoSuchMethodException` after resolving the `Option` object.

The stability-level lookup similarly attempted `ModernFixMixinPlugin.activeFeatureLevel()`, which does not exist in this version. The exact API is:

```java
public static final FeatureLevel ACTIVE_FEATURE_LEVEL
```

on `org.embeddedt.modernfix.core.config.ModernFixEarlyConfig`.

## Resolution order: default + mod compat + user + global + JVM

The exact order in `ModernFixEarlyConfig.load(...)` is important.

### 1. Build option universe and source defaults

ModernFix scans its mixin classes/package metadata, creates category options, then applies `DEFAULT_SETTING_OVERRIDES`. Options absent from that override map default to `true`.

The override map explicitly contains:

```text
mixin.perf.dynamic_resources=false
```

It does not contain `resourcepacks`, `faster_texture_stitching`, or `deduplicate_wall_shapes`, so those category defaults are `true`.

Options are linked to parent options. `getEffectiveOptionForMixin(...)` walks the package path from outer to inner. The first disabled matching ancestor wins immediately; if all matched rules are enabled, the deepest matched rule controls.

### 2. Built-in mod compatibility overrides

The constructor calls `disableIfModPresent(...)`. This writes a mod-defined `false` into the relevant `Option` when the named mod is present. It also does so when ModernFix reports abnormal early loading, as a conservative fail-safe.

Relevant exact rules in 5.27.14:

```text
mixin.perf.deduplicate_wall_shapes -> disabled with dashloader
mixin.perf.faster_texture_stitching -> disabled with optifine
```

There is no corresponding built-in `disableIfModPresent(...)` entry for `mixin.perf.dynamic_resources` or `mixin.perf.resourcepacks` in this snapshot.

This does **not** mean dynamic resources is broadly compatible; it is source-default false and has a much larger semantic compatibility surface described below.

### 3. Local user config

`config/modernfix-mixins.properties` is read through `readProperties(...)`.

A normal user property cannot override an option already marked mod-defined. ModernFix logs that the user configuration is ignored. The unsupported system flag `modernfix.unsupported.allowOverriding=true` changes this, and ModernFix itself marks that mode as unsupported for bug reports.

### 4. Save normalized config

ModernFix rewrites its config comments/default inventory and preserves explicit user entries.

### 5. Global user properties

ModernFix then reads the platform-specific global file, e.g. on Linux:

```text
~/.minecraft/global/modernfix-global-mixins.properties
```

It uses the same `readProperties(...)` rules as the local file.

### 6. JVM properties

Finally it reads:

```text
-Dmodernfix.config.<optionKey>=true|false
```

through `readJVMProperties()`. In 5.27.14 this path calls `Option.setEnabled(...)` directly and therefore has the last word at the option object layer.

### Separate per-mixin permanent gate

Option value alone is not sufficient proof that a mixin will apply.

During early class scanning ModernFix records mixins that cannot apply because of:

- `@RequiresMod` / missing required mod;
- `@ClientOnlyMixin` on the wrong side;
- `@RequiresFeatureLevel` above the active stability level;
- package-level versions of the same constraints.

These are exposed by:

```java
public Map<String, String> getPermanentlyDisabledMixins()
```

`ModernFixMixinPlugin.shouldApplyMixin(...)` first tests `isOptionEnabled(mixin)` and then checks this permanent-disable map. A diagnostic that reports only `Option.isEnabled()` can therefore be misleading.

The clearest example here is `resourcepacks`: `FilePackResourcesMixin` is annotated `@RequiresFeatureLevel(FeatureLevel.BETA)`. At default `modernfix.stabilityLevel=ga`, the category option can be `true` while this important ZIP-pack mixin is still rejected by the feature-level gate.

## Safe diagnostic method

For diagnostic-only code pinned to this environment, the least fragile useful sequence is:

1. obtain `ModernFixMixinPlugin.instance`;
2. read its public `config` field;
3. call public `getEffectiveOptionForMixin(String)`;
4. call `Option.isEnabled()`, `getName()`, `isUserDefined()`, `isModDefined()`, `getDefiningMods()`;
5. read `getPermanentlyDisabledMixins().get(mixinPath)`;
6. read `ModernFixEarlyConfig.ACTIVE_FEATURE_LEVEL`;
7. independently check an exact-version structural marker on the transformed Minecraft target.

PR #69's diagnostic branch was corrected accordingly in commit `833b854eddf21e96b03bcef2d8eea4657cfac8b5`. This remains diagnostic-only and must not be promoted to production.

The corrected probe emits both:

```text
effective=<Option.isEnabled>
permanent_disable=<reason|none>
selected_by_modernfix=<bool>
applied_structural=<bool|probe_failed>
```

For `resourcepacks` it checks both the BETA-gated `FilePackResourcesMixin` and `PathPackResourcesMixin` rather than pretending one category boolean proves the whole package.

## Mixins/classes that prove application

These are exact-version structural or behavioral markers, not general ModernFix API promises.

| Feature | Exact mixin | Target / structural proof | Behavioral proof |
| --- | --- | --- | --- |
| dynamic resources | `perf.dynamic_resources.ModelManagerMixin` | `ModelManager` implements `IExtendedModelManager` | stock eager block-model/blockstate resource-task path is replaced |
| dynamic resources | `perf.dynamic_resources.ModelBakeryMixin` | `ModelBakery` implements `IExtendedModelBakery` | `getBakedTopLevelModels()` returns the dynamic registry; item/block preload loops are skipped |
| dynamic resources | `perf.dynamic_resources.BlockStateModelLoaderMixin` | `BlockStateModelLoader` implements `IBlockStateModelLoader` | initial registry iterator is skipped; requested block is loaded on demand |
| resource packs | `perf.resourcepacks.FilePackResourcesMixin` | `FilePackResources` gains unique field `mf$packIndex` | `getNamespaces/listResources` use `ZipPackIndex`; `close()` drops it |
| resource packs | `perf.resourcepacks.PathPackResourcesMixin` | `PathPackResources` gains `cacheEngine` | in 5.27.14 the substantial path-pack acceleration bodies are commented out, so this is not equivalent to a live ZIP optimization |
| texture stitching | `perf.faster_texture_stitching.StitcherMixin` | `Stitcher` gains `loadableSpriteInfos` | atlases >=100 sprites use `StbStitcher` when loading state is normal |
| wall shapes | `perf.deduplicate_wall_shapes.WallBlockMixin` | `WallBlock` gains `CACHE_BY_SHAPE_VALS` | compatible wall shape maps reuse cached `VoxelShape` instances |

For production code, BootOptim should **not** depend on these private fields. They are suitable only as a source-pinned diagnostic confirmation.

## What the 44k eager model evidence means

The PR #69 laptop profile recorded:

- 44,103 stock block-model resource tasks;
- 11,435 stock blockstate resource-stack tasks.

This is stronger than saying “44k model IDs were discovered.” `ModelManagerMixin` still discovers the complete block-model key universe because it constructs a lazy `Maps.asMap(...)` over all matching resource keys. Therefore enumeration count alone cannot tell whether dynamic resources is active.

However, with the mixin active, ModernFix substitutes the `loadBlockModels` continuation so values are loaded by its lazy cache when demanded. The stock per-resource task lambda instrumented by PR #69 should not execute once for every discovered model. Likewise, the mixin redirects `reload` away from stock `loadBlockStates`.

So the observed stock task execution is **incompatible with `ModelManagerMixin` being applied in that run**. It should be retained as independent behavioral evidence and paired with the corrected config/structural probe, not used as the only proof of an option file value.

## Dynamic-resources architecture in 5.27.14

The implementation is much broader than “make ModelBakery lazy.” It changes the semantic surface exposed to Minecraft and mods.

Primary files:

- `common/mixin/perf/dynamic_resources/ModelManagerMixin.java`
- `common/mixin/perf/dynamic_resources/ModelBakeryMixin.java`
- `common/mixin/perf/dynamic_resources/ModelBakerImplMixin.java`
- `common/mixin/perf/dynamic_resources/BlockStateModelLoaderMixin.java`
- `common/mixin/perf/dynamic_resources/BlockModelShaperMixin.java`
- `common/mixin/perf/dynamic_resources/ItemModelShaperMixin.java`
- `common/mixin/perf/dynamic_resources/ItemModelMesherForgeMixin.java`
- `common/mixin/perf/dynamic_resources/ForgeHooksClientMixin.java`
- `neoforge/dynresources/ModelBakeEventHelper.java`
- `util/DynamicMap.java`
- `util/DynamicOverridableMap.java`

### Initial resource/model load

`ModelManagerMixin` makes block-model values lazy, blockstate stacks lazy, and suppresses the initial state collection in `loadModels`.

`ModelBakeryMixin` replaces major model maps with LRU-backed maps, skips eager item-registry iteration, and provides `mfix$loadUnbakedModelDynamic(...)` / dynamic bake-on-get behavior.

`BlockStateModelLoaderMixin` skips the initial `BuiltInRegistries.BLOCK` iteration. When a block model is first requested it calls `loadBlockStateDefinitions` for that block. Importantly, state-level filtering is explicitly disabled in the source as inefficient, so this is **block-granular lazy loading**, not “one exact state JSON branch at a time.”

This distinction matters for a future BootOptim hybrid-lazy design: block-granular lazy loading is already part of ModernFix's design and should not be rediscovered as a novel BootOptim optimization.

### Dynamic baked registry

`ModelBakeryMixin#getBakedTopLevelModels()` is overwritten to return `DynamicOverridableMap`.

The underlying `DynamicMap` deliberately does not behave like a normal materialized map:

- `get(validKey)` computes/loads dynamically;
- `containsKey(...)` returns `true`;
- `size()` returns `0`;
- `isEmpty()` returns `false`;
- `keySet()`, `entrySet()`, and `values()` are empty;
- mutation operations are unsupported except where `DynamicOverridableMap` adds `put`/`putAll` overrides.

This is why direct internal map access is a major compatibility boundary. A mod using `get`/`put` can work; a mod inferring the universe through views/size/iteration can observe intentionally non-vanilla semantics.

The NeoForge item-model location map is similarly replaced by `ItemMesherMap`, where `get` works but `keySet()`, `values()`, and `entrySet()` throw `UnsupportedOperationException`.

## NeoForge ModelEvent compatibility

`ForgeHooksClientMixin` intercepts NeoForge's `ClientHooks.onModifyBakingResult` event post. Instead of posting the single vanilla-style registry event directly, ModernFix constructs a `ModelBakeEventHelper` and posts a separate wrapped `ModelEvent.ModifyBakingResult` to each mod container.

The helper precomputes a universe containing:

- all block-state `ModelResourceLocation`s;
- all item inventory model locations;
- already present model-registry keys;
- standard `models/item` resources discovered from the resource manager.

This lets `keySet()` expose a realistic model universe without forcing every model value to bake.

The wrapper supports `get`, `put`, `containsKey`, `keySet`, and a dynamic `entrySet`. It also contains a special `replaceAll` heuristic: call the mod function with `null` first, and only load the real model when the function appears to care. ModernFix explicitly warns that this hack may not be 100% compatible.

A few mods are restricted to seeing only their namespace plus dependency/dependent namespaces in this exact snapshot (`eternal_starlight`, `alexscaves`, `refinedstorage`, `cabletiers`) as targeted compatibility mitigations.

### Important event/view caveats

The event wrapper is better behaved than the raw dynamic bakery map, but it is still an emulation layer:

- `keySet()` is a synthetic universe;
- `entrySet()` iterates synthetic keys and loads a value when `Map.Entry#getValue()` is called;
- its `size()` delegates to the underlying model registry rather than necessarily matching the synthetic iterator universe;
- `values()` is not explicitly rebuilt as a synthetic universe in `EmulatedModelRegistry`;
- `replaceAll` uses a null-probe heuristic and warns about compatibility;
- direct access outside this event wrapper can hit the much more nonstandard `DynamicMap` semantics above.

A BootOptim hybrid design must not assume “NeoForge model event fired successfully” implies all map semantics are preserved.

## Custom models / geometry / loaders

Compatibility is conditional on using public/standard model-baker entrypoints.

`ModelBakerImplMixin` overwrites NeoForge-facing `getTopLevelModel(ModelResourceLocation)` to route through `mfix$loadUnbakedModelDynamic(...)`, and wraps bake calls with the ModelBakery lock. Standard requested models and dependencies can therefore be materialized on demand.

`ModelBakeryMixin` has explicit handling for variants `standalone` and `fabric_resource`: it verifies that the underlying model resource exists, resolves the unbaked model through the bakery, and registers dependencies dynamically. This is a compatibility path for models that are not normal inventory/blockstate variants.

But ModernFix's own API documentation warns integration authors not to touch `ModelBakery` internals because they do not behave normally with dynamic resources enabled. Upstream issues around mods that inject directly into ModelBakery/model maps are therefore architecturally consistent with the source.

For a hybrid-lazy agent, classify custom model consumers into at least:

1. standard `ModelBaker` / `getTopLevelModel` consumers — likely compatible with deferred materialization;
2. custom loaders reached from normal model parsing/dependency traversal — potentially compatible, requires exact-pack proof;
3. mods that enumerate/read internal bakery maps, rely on concrete map size/views, or inject into load loops — high risk and should remain eager or get an explicit compatibility adapter;
4. mods that mutate all models via event-wide `entrySet/values/replaceAll` — potentially turns laziness back into eager work or observes emulation differences.

## Missing models

ModernFix deliberately distinguishes several missing cases:

- its missing model is baked eagerly and retained permanently;
- a missing standalone/fabric-resource file can return `null` to mean “legitimately absent”;
- dynamic block/item lookup falls back to the baked missing model when appropriate;
- the NeoForge event wrapper can return the missing model when a mod requests a model in its visible namespace that is not actually present, and logs a warning.

A BootOptim design must preserve the distinction between “not registered / allow caller fallback,” “resource missing,” and “return Minecraft missing model.” Collapsing these into one sentinel is a likely compatibility bug.

## Resource reload and invalidation

ModernFix's lazy caches are reload-scoped rather than treated as immutable across resource generations.

Examples:

- each model reload constructs/replaces the ModelBakery-side lazy structures;
- `ModelManagerMixin` freezes/marks loading completion only after apply;
- `BlockModelShaperMixin` replaces its dynamic map on construction/`replaceCache` and clears cached model references stored on all block states;
- item model caches clear on `rebuildCache`;
- `FilePackResourcesMixin` drops `mf$packIndex` on `close()` so an opened pack can be indexed again cleanly.

Any BootOptim persistent resource/model cache must therefore carry an explicit resource-pack generation/invalidation key. Keeping a lazily loaded `Resource`, `BlockModel`, baked model, or stream across reload without generation ownership is unsafe.

## Connector / Fabric API

The exact target artifact is NeoForge, so no claim should be made that Fabric Model Loading API semantics are automatically covered when Fabric mods are introduced through Connector.

There is one useful exact-source fact: shared dynamic-model code recognizes `fabric_resource` and `standalone` model variants and can load them on demand. That is evidence of deliberate cross-loader model compatibility work, but it is **not** proof that Sinytra Connector's translated Fabric model events, map views, or custom loader lifecycle are equivalent to native NeoForge behavior.

For an exact-pack hybrid-lazy implementation, Connector/Fabric API should therefore be treated as a separate compatibility cell:

- identify whether Connector/Fabric model-loading APIs are present in the pack;
- record which event/API callbacks are registered;
- test their model requests and reload lifecycle under a diagnostic wrapper;
- keep those consumers eager until proven safe to defer.

## Compatibility matrix for a BootOptim hybrid-lazy agent

| Surface | ModernFix 5.27.14 behavior | Risk if BootOptim defers work | Hybrid-lazy recommendation |
| --- | --- | --- | --- |
| NeoForge `ModelEvent.ModifyBakingResult` | per-mod wrapped event with synthetic model universe | medium | preserve event timing; classify handlers by `get/put` vs global iteration/mutation |
| event `get/put` | dynamic get; overrides supported | low/medium | good lazy candidate when caller requests known MRLs |
| event `keySet` | synthetic full/filtered universe without value load | medium | preserve universe eagerly even if values stay lazy |
| event `entrySet` | synthetic keys; values demand-load | medium/high | instrument exact handlers; iteration can eagerly materialize everything |
| event `values` | not a fully synthetic equivalent of key universe | high | do not assume vanilla semantics; keep incompatible handlers eager |
| event `replaceAll` | null-probe heuristic, warning, possible selective loads | high | treat global transformations as eager/compat boundary unless proven selective |
| direct `ModelBakery#getBakedTopLevelModels` map | `DynamicMap`: get works; size/views intentionally nonstandard | high | never expose a BootOptim lazy map with weaker semantics to unknown consumers |
| direct item mesher location map | `get` works; iteration views unsupported | high | classify direct-map consumers before deferral |
| standard `ModelBaker` request | dynamically loads/locks/resolves parents | medium | candidate for on-demand path after custom loader/event audit |
| custom geometry/model loaders | works when reached through standard requested-model path; internals are not vanilla | medium/high | prove loader-by-loader; do not assume |
| blockstate loading | lazy per requested block, then all states for that block | medium | do not duplicate ModernFix; if designing independent hybrid mode, block is minimum proven granularity |
| missing standalone/resource model | explicit null-vs-missing handling | high if simplified | preserve three-way missing semantics |
| resource reload | lazy caches rebuilt/cleared by reload lifecycle | high | generation-key every deferred/persistent object |
| Connector/Fabric API on NeoForge | `fabric_resource` path exists; full Connector semantics unproven | high | separate diagnostic compatibility lane |
| raw resource key enumeration | still largely eager enough to know universe | low | strong BootOptim complement target: make enumeration/open cheaper without changing universe semantics |
| resource open/decompression | not solved by dynamic model map itself | low/medium | primary complementary optimization lane after exact source attribution |

## Why `dynamic_resources` may legitimately be disabled in this pack

### Source default

The decisive first fact is simple: ModernFix 5.27.14 deliberately defaults `mixin.perf.dynamic_resources` to `false`. It is opt-in in this version.

### Trimmable Tools is a version-specific blocker

BootOptim's exact-pack work already has Trimmable Tools-specific semantic validation in the generated-item path, so this is not a hypothetical mod family for the project.

ModernFix issue #559 documents broken trim rendering with dynamic resources. The ModernFix fix tracker states that the MC 1.21.1 fix shipped in **5.27.20+mc1.21.1**. The target pack is pinned to **5.27.14**, so it predates that fix.

This is a concrete reason not to turn `dynamic_resources=true` into a BootOptim prerequisite or “quick win” for the current pack.

Source issue:

- <https://github.com/embeddedt/ModernFix/issues/559>

### Other compatibility classes

ModernFix's issue history contains further failures involving model-baking mods, direct bakery access, custom menus/reloads, and Create add-ons. Some were fixed in later releases, some were not reproducible, and not all affected mods are known to be in this exact pack. They should be treated as evidence that the semantic surface is genuinely fragile, not as proof that each named issue affects the pack.

Notable examples to keep in the agent's compatibility search set:

- Additional Placements model baking: <https://github.com/embeddedt/ModernFix/issues/563>
- Create: Teleporters startup/title-screen incompatibility report: <https://github.com/embeddedt/ModernFix/issues/651>
- historical FancyMenu dynamic-resource reload issue: <https://github.com/embeddedt/ModernFix/issues/625> — upstream later stated a fix had been released, so this issue alone should **not** be used to claim FancyMenu is broken in 5.27.14 without pinning the fix version.

## `resourcepacks` is not one simple on/off optimization

In 5.27.14 the most interesting live ZIP path is `FilePackResourcesMixin`:

- builds `ZipPackIndex` lazily;
- uses it for namespaces/resource listing;
- retrieves resources from the already opened `ZipFile`;
- drops the index on close.

But that mixin is BETA-gated.

`PathPackResourcesMixin`, despite living under the same option, has its substantial namespace/existence/listing acceleration code commented out in this exact snapshot. The active code mainly manages a `PackResourcesCacheEngine` field that is not used by those commented hooks.

Consequences for BootOptim:

1. do not log only `mixin.perf.resourcepacks=true` and conclude that ZIP indexing is active;
2. log the active feature level and `FilePackResourcesMixin` permanent-disable reason;
3. if the exact pack is GA and the FilePack mixin is inactive, resource/JAR indexing remains a legitimate complementary research lane;
4. if BETA is active and the mixin is applied, benchmark any BootOptim resource-index idea against it to avoid duplicate indexes/memory and no-op work.

## `faster_texture_stitching` scope

`StitcherMixin` only replaces final rectangle packing when:

- ModernFix is loading normally; and
- the atlas has at least 100 entries.

For small atlases it intentionally uses vanilla, partly for mods such as JEI that depend on precise alignments.

Therefore PR #69's finding that the blocks atlas spends only ~876 ms in actual `stitch(...)` does not make this ModernFix option irrelevant, but it does confirm that **final packing is not the dominant current laptop atlas bottleneck**. BootOptim should continue to focus on sprite discovery/resource open/PNG metadata+decode rather than duplicating `StbStitcher`.

## `deduplicate_wall_shapes` scope

`WallBlockMixin` caches wall `VoxelShape` maps by the six float shape parameters and requires identical state-definition properties before reuse. It only seeds the cache from exact vanilla `WallBlock` instances, then compatible walls can reuse those immutable shapes.

This is a narrow source-level deduplication, not a generic solution to the laptop's voxel-shape hot path. If the exact pack has DashLoader, ModernFix disables it by mod override. Otherwise the corrected probe should establish whether it is selected/applied before BootOptim investigates any overlapping wall-shape canonicalization.

The laptop JFR still justifies a broader caller-attribution campaign because its hottest shape work includes joins and constructors well beyond this one wall cache.

## What BootOptim can complement without duplicating ModernFix

Recommended boundaries:

### Good complementary lanes

1. **Resource open/read/decompression/materialization attribution and optimization.** PR #69 shows resource opening dominates blockstate task time and most block-model task time is outside JSON parse. This remains valuable whether models are eager or selectively lazy.
2. **Exact ZIP/JAR path optimization only after proving `FilePackResourcesMixin` inactive.** If ModernFix is GA-gated here, BootOptim can investigate a semantics-preserving index or faster stream-opening path; if the ModernFix mixin is active, avoid a second index.
3. **Sprite PNG/metadata/decode path, especially Decocraft.** `faster_texture_stitching` accelerates rectangle packing, not the dominant sprite-load work measured by #69.
4. **Consumer classification for a hybrid lazy boundary.** Instrument which exact-pack mods enumerate/mutate model maps or depend on eager event values before designing a lazy subset.
5. **Post-event/use-time deferral for demonstrably local consumers.** Keep the model-key universe and incompatible global event transforms eager, defer only model values whose consumers are request-based.

### Do not duplicate

- generic dynamic `ModelBakery#getBakedTopLevelModels` map;
- block-granular dynamic blockstate loading;
- dynamic item model shaper/mesher;
- NeoForge `ModifyBakingResult` synthetic-universe wrapper;
- STB rectangle packing;
- wall-shape tuple cache.

Those are already ModernFix mechanisms. Reimplementing them in BootOptim would inherit the same semantic risks while creating two competing compatibility layers.

## Recommendations for the hybrid-lazy agent

1. **Start from the corrected ModernFix proof, not from the 44k count alone.** Capture effective option, controlling rule, user/mod source, feature level, permanent-disable reason, and structural application markers in one exact-pack run.
2. **Treat `dynamic_resources=false` as the compatibility baseline for the current ModernFix pin.** Do not enable it merely to obtain a ceiling number unless the run is explicitly experimental and Trimmable Tools rendering is verified.
3. **Build a consumer inventory before code.** For each model event/consumer, record whether it performs individual `get`, `containsKey`, key iteration, entry/value iteration, `replaceAll`, direct bakery-map access, custom loader calls, or internal mixins.
4. **Partition eager universe from lazy value materialization.** It is often safe/useful to know all MRL/resource keys before title screen while avoiding expensive parse/bake of values. ModernFix demonstrates this architecture but also demonstrates where map-view semantics become dangerous.
5. **Keep global transforms eager.** A mod that iterates or transforms every baked model defeats lazy value loading by definition; trying to fake it with `null` probes is a compatibility heuristic, not a general semantic guarantee.
6. **Preserve custom-loader and missing-model semantics.** Any deferred path needs the same model-resolution entrypoints, parent/dependency resolution, texture getter, missing/null distinction, and event wrapping as the eager path.
7. **Use reload generations.** Deferred resources/models must belong to a specific resource reload generation and be dropped or invalidated together.
8. **Separate Connector/Fabric API validation.** Shared `fabric_resource` handling is encouraging but insufficient proof for Connector-translated model APIs.
9. **Prefer resource-layer wins first on the laptop.** The slow-hardware data scales resource-facing phases 9–20x while bake arithmetic scales ~3–4x. A semantics-preserving reduction in archive traversal/open/decode work is a better first complement than another bakery-level lazy map.
10. **Use stripped A/B builds for startup claims.** PR #69's heavy attribution probes have observer effect; use them only to locate ceilings/consumers, then validate candidates without the profiler.

## Required follow-up evidence

One exact-pack diagnostic run with PR #69 at or after `833b854eddf21e96b03bcef2d8eea4657cfac8b5` should be enough to close the remaining configuration uncertainty. Preserve the `BOOTOPTIM_RESOURCE_CONFIG` lines for:

- ModernFix implementation version;
- stability level;
- all three dynamic-resource structural markers;
- both resourcepack representative mixins;
- faster texture stitching;
- wall-shape deduplication.

Expected interpretation examples:

```text
option=true + permanent_disable=[feature level: requires BETA] + applied_structural=false
```

means the category is enabled but that mixin is not active.

```text
option=false + controlling_rule=mixin.perf.dynamic_resources + applied_structural=false
```

would directly confirm the source-default/pack value explanation for the 44k eager resource tasks.

A disagreement such as `selected_by_modernfix=true` but `applied_structural=false` must be treated as a transformation/application problem and investigated before any performance conclusion.

## Bottom line

The failed PR #69 reflection probe is fixable and was version-skew, not an inaccessible ModernFix configuration system.

For 5.27.14:

- `dynamic_resources` is source-default **off**, and the PR #69 execution path independently proves its core ModelManager mixin was not applied;
- `resourcepacks`, `faster_texture_stitching`, and `deduplicate_wall_shapes` are source-default **on**, but source default is not the same as exact-pack effective/applied state;
- `resourcepacks` especially requires per-mixin proof because its useful ZIP mixin is BETA-gated;
- dynamic resources already contains a sophisticated NeoForge compatibility layer, including synthetic event registries, dynamic baking, block-granular lazy states, item shaper replacements, and reload-aware caches;
- those mechanisms intentionally weaken or emulate normal map semantics and have real mod-compat history;
- Trimmable Tools is a concrete current-pack reason not to force dynamic resources on with ModernFix 5.27.14, because the MC 1.21.1 fix only shipped in 5.27.20;
- BootOptim should complement ModernFix at the resource/open/decode layer and, if pursuing hybrid laziness, only after profiling exact consumers and retaining eager semantics for global/event-driven users.
