# Decocraft 3D item / item-sprite elision experiment — 2026-09-02

Status: **ACTIVE / exact-pack runtime validation required**

Branch: `agent/experiment-decocraft-3d-items`

## Premise

PR #72 showed that Decocraft contributes a very large part of the laptop atlas workload. In the corrected exact-pack diagnostic run, Decocraft produced 5,771 sprite loads with 51,402.057 ms inclusive task-sum, including 47,697.878 ms inside `Resource.open()`. Those are concurrent task sums, not recoverable wall time, but they make eliminating work before supplier creation preferable to optimizing PNG decode in isolation.

The exact Decocraft 3.0.11 artifact also contains thousands of generated 2D item models whose corresponding decoration already has a same-name 3D block BBModel. The project explicitly accepts the visual tradeoff of rendering those items with the 3D block geometry instead of the prerendered 2D item icon, and requested the feature enabled by default.

## Static exact-artifact audit

`tools/audit_decocraft_item_sprites.py` downloads Modrinth version `Z8xm2POI` (`decocraft-3.0.11-1.21.1-neoforge.jar`) and audits only metadata/JSON/PNG dimensions.

Current CI-proven counts:

- 3,224 item model JSONs;
- 3,224 generated-parent item models;
- 3,218 pure generated item models with a same-name block model;
- 3,192 candidates whose item texture has no other asset-JSON reference;
- 63 item textures retained by the compact keep-list;
- all 3,192 final candidates use the same item-model path, item-texture path and block-model path;
- candidate encoded PNG bytes: 12,047,323;
- candidate decoded RGBA footprint: 52,199,424 bytes.

The audit workflow additionally requires the generated 63-entry keep-list to be byte-identical to the list packaged by BootOptim. Any Decocraft artifact change that alters these invariants fails the audit rather than silently widening the optimization.

## Runtime mechanism

The experiment has two coordinated halves.

### Model remap

After stock `ModelManager.loadBlockModels` has parsed the selected model JSONs, BootOptim considers only `decocraft:models/item/*.json` entries not represented in the audited keep-list.

A candidate is remapped only when:

- Decocraft reports exact version `3.0.11`;
- the selected item model resource is from `mod/decocraft`;
- the selected same-name block model resource is from `mod/decocraft`;
- the selected same-name item texture is from `mod/decocraft`;
- the parsed item model still has the generated parent, no overrides/elements, and layer0 points at the audited same-name Decocraft item texture;
- the parsed same-name block model exists.

The replacement is a fresh parent-only `BlockModel` pointing at `decocraft:block/<id>`. NeoForge 1.21.1's `BlockGeometryBakingContext#getCustomGeometry()` inherits custom geometry through the parent chain, so Decocraft's BBModel loader remains authoritative; BootOptim does not copy or mutate Decocraft geometry objects.

Any mismatch is fail-open and leaves the stock item model untouched.

### Atlas supplier elision

Only the `SpriteSourceList` created for `minecraft:blocks` is wrapped. BootOptim wraps each `SpriteSource`'s output and intercepts only the `Output.add(ResourceLocation, Resource)` overload, where the selected backing `Resource` and its pack provenance are still available.

For an audited `decocraft:item/*` candidate, BootOptim omits the call to the downstream output only when the texture plus matching item/block models are still selected from `mod/decocraft`. This happens before the stock output converts the resource into a `SpriteSupplier`, so a successfully removed candidate should incur no later `Resource.open()`, ZipFS entry materialization or PNG decode for that item texture.

Custom sprite sources that provide only a `SpriteSupplier` rather than a `Resource` are deliberately delegated unchanged because their provenance cannot be proven. Resource-pack overrides therefore fail open instead of being inferred.

## Configuration

Default in `config/boot_optim.properties`:

```properties
decocraft3dItems=true
```

JVM override / A/B kill switch:

```text
-Dboot_optim.decocraft3dItems=false
```

The bootstrap resolves the effective value before the regular mod runs. Existing configs missing the property get the default-on entry appended for discoverability.

## Expected marker shape

Exact-pack validation should show markers similar to:

```text
BOOTOPTIM_DECOCRAFT_3D_ITEMS status=active ... version=3.0.11 keep_list=63
BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=models ... remapped=3192 ...
BOOTOPTIM_DECOCRAFT_3D_ITEMS stage=atlas ... removed=3192 ...
```

Counts lower than 3,192 are not automatically bugs: resource-pack overrides or unverified supplier-only atlas sources intentionally retain stock behavior and are reported separately.

## Validation / stop conditions

Before promotion:

1. Build CI green.
2. Vanilla Startup CI reaches main menu with Decocraft absent and no BootOptim/Mixin failure.
3. Exact-pack startup reaches title with no missing-texture/model errors attributable to the feature.
4. Inspect inventory/JEI/hand rendering for representative Decocraft decorations; the deliberate 2D-to-3D appearance change is accepted, but missing geometry, transforms or particles are not.
5. Compare enabled vs disabled on the same BootOptim JAR. Judge time-to-main-menu and ModelManager/atlas critical wall; do not translate the removed PR #72 task-sum directly into wall savings.
6. Check inventory/JEI rendering cost after startup. A startup win that creates unacceptable steady-state rendering cost is not a production win.

Stop / reject if the exact pack does not remove a substantial number of suppliers, if startup wall does not move enough to justify the behavior change, or if common item views regress functionally.

## Relationship to existing Decocraft optimization

This does **not** replace or weaken production quarter-turn BBModel quad reuse. The 3D item models still use Decocraft's custom geometry path, so the existing guarded quarter-turn reuse remains independently applicable where its invariants match.
