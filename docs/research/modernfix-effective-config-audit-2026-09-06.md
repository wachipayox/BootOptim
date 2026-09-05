# ModernFix 5.27.14 effective compatibility audit — 2026-09-06

Status: **DIAGNOSTIC / DO NOT MERGE RUNTIME PROBE AS PRODUCTION**

## Scope

This audit is intentionally separate from the ModernFix reload-executor scheduling lane in PR #126. It only answers which of these ModernFix `5.27.14+mc1.21.1` features are effectively selected and structurally applied in the exact pack:

- `mixin.perf.dynamic_resources`
- `mixin.perf.resourcepacks`
- `mixin.perf.faster_texture_stitching`
- `mixin.perf.deduplicate_wall_shapes`

No ModernFix option is changed. In particular, dynamic resources remains untouched.

Base was refreshed before changes. Live `agent/integration-current` was `145c10c2f8132b21e7b7be067c56513b394ccb5a`; the task-provided `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c` is an ancestor 15 commits behind it.

## Historical correction

PR #69's original reflection probe is not evidence that the ModernFix configuration API is inaccessible. ModernFix 5.27.14 exposes `ModernFixEarlyConfig#getEffectiveOptionForMixin(String)` and returns a boolean `Option` with `isEnabled()`. The old probe failed only after resolving the option because it attempted the later `asBoolean().getValue()` API. Stability is the public static `ModernFixEarlyConfig.ACTIVE_FEATURE_LEVEL`, not a plugin method.

The corrected diagnostic logic originally existed at PR #69 commit `833b854eddf21e96b03bcef2d8eea4657cfac8b5`, while PR #74 recorded the durable source audit. This branch ports only the minimal effective-config/structural census onto current integration and executes it after the TTMM marker.

## Exact upstream source

ModernFix snapshot: `embeddedt/ModernFix@f8f1b092bf64d9ec29222d502d2e67f4304dc221`, corresponding to the 1.21.1 5.27.14 release line.

Configuration resolution is:

1. source defaults (all discovered options true unless listed in `DEFAULT_SETTING_OVERRIDES`);
2. built-in mod compatibility overrides;
3. local `config/modernfix-mixins.properties` user values;
4. global ModernFix properties;
5. JVM `-Dmodernfix.config.<key>=...` values;
6. separately, `shouldApplyMixin` rejects entries in `getPermanentlyDisabledMixins()` (missing mod, wrong side, feature-level gate).

Relevant source defaults and gates:

| Category | Source default | Additional exact-5.27.14 gate |
| --- | --- | --- |
| `mixin.perf.dynamic_resources` | false | client/per-mixin compatibility surface; no category mod blacklist |
| `mixin.perf.resourcepacks` | true | `FilePackResourcesMixin` requires BETA |
| `mixin.perf.faster_texture_stitching` | true | disabled for OptiFine; runtime fallback below 100 sprites or abnormal loading |
| `mixin.perf.deduplicate_wall_shapes` | true | disabled for DashLoader |

Exact-pack logs from hosted run `33988643908` identify ModernFix `5.27.14+mc1.21.1`, Trimmable Tools `2.0.5`, and five unrelated ModernFix mod overrides (C2ME/Litematica). They contain no OptiFine or DashLoader entry. The same log shows no non-GA stability warning, which is consistent with GA but is not used as the final proof; the probe reads `ACTIVE_FEATURE_LEVEL` directly.

## Structural meaning of each representative mixin

- `dynamic_resources/ModelManagerMixin`: `ModelManager implements IExtendedModelManager`; changes block-model/blockstate value loading to lazy maps.
- `dynamic_resources/ModelBakeryMixin`: `ModelBakery implements IExtendedModelBakery`; replaces major baked/unbaked registries with dynamic behavior.
- `dynamic_resources/BlockStateModelLoaderMixin`: `BlockStateModelLoader implements IBlockStateModelLoader`; block-granular on-demand state loading.
- `resourcepacks/FilePackResourcesMixin`: target gains `mf$packIndex`; live ZIP optimization indexes namespaces/listing and invalidates on close, but the mixin is BETA-gated.
- `resourcepacks/PathPackResourcesMixin`: target implements `ICachingResourcePack`; in this exact snapshot the substantive lookup/listing acceleration bodies are commented out, so structural application is not proof of useful path-pack acceleration.
- `faster_texture_stitching/StitcherMixin`: target gains `loadableSpriteInfos`; atlases with at least 100 entries use STB packing while smaller atlases deliberately retain vanilla alignment behavior.
- `deduplicate_wall_shapes/WallBlockMixin`: target gains `CACHE_BY_SHAPE_VALS`; exact vanilla wall shape maps seed reuse keyed by six shape floats with property-set equality guards.

## Exact-pack diagnostic

Property:

```text
-Dboot_optim.probeModernFixCompat=true
```

The existing title hook records `BOOTOPTIM_STARTUP phase=main_menu` first, then runs the probe. Therefore reflection and any structural class loading happen after the TTMM boundary and cannot be claimed as startup savings/cost.

For each representative mixin the probe reports:

- exact mod version from `ModList`;
- `ACTIVE_FEATURE_LEVEL`;
- effective boolean;
- controlling option rule;
- user/mod override flags and defining mods;
- permanent-disable reason;
- ModernFix selection (`effective && no permanent-disable`);
- an exact-version transformed-target structural marker.

A disagreement between `selected_by_modernfix=true` and `applied_structural=false` is a diagnostic failure requiring investigation, not an optimization opportunity.

## Existing performance context (not savings claims)

PR #69's slow-laptop attribution showed:

- 44,103 stock block-model resource tasks and 11,435 stock blockstate stack tasks; this behavior is incompatible with the core dynamic-resource ModelManager mixin being applied in that run;
- block-state `Resource.openAsReader`: 7,961.276 ms task-sum versus 549.682 ms Gson task-sum;
- 20,054 sprite loads: 65,326.153 ms task-sum;
- blocks-atlas `loadAndStitch`: 55,377.455 ms wall scope, while final `stitch(...)` itself was only 875.884 ms wall scope.

These are attribution/overlapping task metrics from a heavy profiler, not TTMM savings ceilings. They justify resource/open/decode research but do not establish an end-to-end gain.

PR #105 later showed the broader `minecraft:block` registration CPU hotspot is dominated by Create/Ponder/Lithium voxel-shape joins, while vanilla wall construction is only one sampled branch. Therefore ModernFix wall-shape deduplication does not cover that broader algorithmic front.

## Compatibility boundary

Do not enable `dynamic_resources` in this pack. Trimmable Tools 2.0.5 is present, and the historical ModernFix compatibility audit records that the MC 1.21.1 Trimmable Tools dynamic-resource fix shipped only in ModernFix 5.27.20, later than the pinned 5.27.14.

Do not duplicate ModernFix's dynamic model maps, block-granular lazy states, STB stitcher, or wall-shape tuple cache in BootOptim.

If the exact smoke proves the BETA-gated `FilePackResourcesMixin` inactive, that leaves only a **research boundary**, not an automatic implementation approval: a BootOptim archive/open/index candidate would need independent source attribution, stock-equivalent ordering/resource-stack semantics, close/reload invalidation, no duplicate index with ModernFix, and hosted TTMM/critical-path evidence before promotion.

## Reopening / decision rules

- `dynamic_resources`: closed for the pinned pack unless ModernFix is upgraded past the known Trimmable Tools fix and full model-event/custom-loader/Connector semantics are revalidated.
- `resourcepacks`: only reopen archive/index work if the FilePack mixin is structurally inactive **and** current critical-path attribution shows repeated archive traversal/open work with a material ceiling. Do not copy the BETA ModernFix implementation simply to bypass its feature-level gate.
- `faster_texture_stitching`: do not duplicate if selected/applied. Reopen only for a distinct sprite decode/open mechanism; small-atlas vanilla fallback is intentional compatibility behavior.
- `deduplicate_wall_shapes`: do not duplicate if selected/applied. Broader VoxelShaper/Create algorithms are a separate subsystem and require their own equivalence proof.

No TTMM improvement is claimed by this diagnostic.
