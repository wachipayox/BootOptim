# Decocraft 3D item / item-sprite elision experiment — 2026-09-02

Status: **REJECTED / do not repeat without a materially different premise**

Experiment PR: #79 (`agent/experiment-decocraft-3d-items`)

Final tested head: `679523fb8ffbde071d53c09387af10e8e21195d8`

## Premise

PR #72 showed that Decocraft contributes a very large part of the laptop atlas workload. In the corrected exact-pack diagnostic run, Decocraft produced 5,771 sprite loads with 51,402.057 ms inclusive task-sum, including 47,697.878 ms inside `Resource.open()`. Those are concurrent task sums, not recoverable wall time, but they made eliminating work before supplier creation preferable to optimizing PNG decode in isolation.

The exact Decocraft 3.0.11 artifact also contains thousands of generated 2D item models whose corresponding decoration already has a same-name 3D block BBModel. The project explicitly accepted the visual tradeoff of rendering those items with the 3D block geometry instead of the prerendered 2D item icon for this experiment.

## Static exact-artifact audit

`tools/audit_decocraft_item_sprites.py` on the experiment branch targeted Modrinth version `Z8xm2POI` (`decocraft-3.0.11-1.21.1-neoforge.jar`) and audited metadata/JSON/PNG dimensions.

CI-proven counts:

- 3,224 item model JSONs;
- 3,224 generated-parent item models;
- 3,218 pure generated item models with a same-name block model;
- 3,192 candidates whose item texture has no other asset-JSON reference;
- 63 item textures retained by the compact keep-list;
- all 3,192 final candidates use the same item-model path, item-texture path and block-model path;
- candidate encoded PNG bytes: 12,047,323;
- candidate decoded RGBA footprint: 52,199,424 bytes.

The audit also required the generated 63-entry keep-list to be byte-identical to the list packaged by the experiment.

## Runtime mechanism that was tested

The experiment had two coordinated halves.

### Model remap

After stock `ModelManager.loadBlockModels` parsed the selected model JSONs, BootOptim considered only `decocraft:models/item/*.json` entries outside the audited keep-list.

A candidate was remapped only when:

- Decocraft reported exact version `3.0.11`;
- selected item model, same-name block model and same-name item texture all came from `mod/decocraft`;
- the parsed item model still had the generated parent, no overrides/elements, and `layer0` pointed at the audited same-name Decocraft item texture;
- the parsed same-name block model existed.

The replacement was a fresh parent-only `BlockModel` pointing at `decocraft:block/<id>`. NeoForge parent custom-geometry inheritance kept Decocraft's BBModel loader authoritative. Any mismatch failed open.

### Atlas supplier elision

Only the `SpriteSourceList` for `minecraft:blocks` was wrapped. The experiment intercepted only `SpriteSource.Output.add(ResourceLocation, Resource)`, where selected-resource provenance was known. A verified `decocraft:item/*` candidate was omitted before the stock output created its `SpriteSupplier`, so that texture avoided later stock `Resource.open()`, ZipFS entry materialization and PNG decode.

Supplier-only/custom-source additions were delegated unchanged.

## Exact-pack mechanism validation

The mechanism itself passed strongly.

Across all three hosted candidate runs:

- `status=active`, exact Decocraft `3.0.11`;
- `item_models=3224`;
- `remapped=3192`;
- `keep=15`;
- `shape_rejected=16`;
- `guard_rejected=1` in model remapping;
- `resource_candidates=3255`;
- `removed=3192`;
- atlas `keep=63`;
- `overridden_texture=0`;
- atlas `guard_rejected=0`;
- `unverified_supplier_candidates=0`;
- 0 BootOptim Mixin failures.

All three hosted controls reported `status=disabled` and 0 remaps / 0 sprite removals.

Thus the rejection is **not** because the intended PNG/model work failed to disappear.

## Laptop same-JAR A/B

The first exact-pack laptop pair used the same experiment JAR with the JVM kill switch for control.

- ON: `mod_entrypoint=144799 ms`, `main_menu=374898 ms`;
- OFF: `mod_entrypoint=124185 ms`, `main_menu=375581 ms`;
- main-menu delta: **ON -683 ms (-0.18%)**.

That pair was effectively tied end-to-end. ON's post-entrypoint interval was substantially shorter, but ON had already entered the mod about 20.6 s later, and unrelated tail variance was large. It therefore did not establish a production win.

On that laptop run, the blocks atlas changed from `8192x8192x2` OFF to `8192x4096x2` ON.

## Hosted exact-pack 3x3 A/B

After hosted exact-pack infrastructure was merged, #79 was refreshed onto integration `417b1406b0018f35aaa278e7ce2ec98688b6538a` and tested as three fresh-VM controls versus three fresh-VM candidates on Oracle Java 25.0.4 with the project's 4-CPU/6-GiB surrogate settings.

Candidate main-menu runs:

- 94,671 ms;
- 93,945 ms;
- 103,683 ms.

Control main-menu runs:

- 92,696 ms;
- 96,285 ms;
- 91,517 ms.

Median comparison:

| Metric | Candidate | Control | Candidate - control |
| --- | ---: | ---: | ---: |
| main menu | 94,671 ms | 92,696 ms | **+1,975 ms (+2.13%)** |
| mod entrypoint | 31,600 ms | 31,211 ms | **+389 ms (+1.25%)** |
| post-entrypoint | 63,384 ms | 62,038 ms | **+1,346 ms (+2.17%)** |
| reload -> FancyMenu finished | 44,969 ms | 42,874 ms | **+2,095 ms (+4.89%)** |
| FancyMenu panorama | 4,197.329 ms | 4,162.901 ms | +34.428 ms (+0.83%) |
| MCEF init | 617 ms | 1,882 ms | **-1,265 ms** |

The candidate was therefore slower in the directly relevant reload/post-entrypoint boundaries even though unrelated native MCEF variance favored the candidate by about 1.265 s. A slower MCEF cannot explain the candidate regression.

### Hosted atlas boundary

The hosted candidate removed all 3,192 audited sprites but the blocks atlas remained `8192x8192x2` in all candidate runs, whereas the earlier laptop candidate crossed to `8192x4096x2`.

This is an environment/fixture boundary, not a mechanism failure: the pre-supplier removals are independently proven by the runtime counters. No cause for the different packing threshold is asserted from the available evidence.

The hosted A/B therefore measures the cost effect of eliminating the resource/model work, but does not include the laptop run's atlas-dimension reduction benefit.

## Decision

**NO-GO for production.**

The same mechanism now failed the performance bar in two complementary ways:

1. laptop same-JAR A/B: end-to-end effectively tied (`-0.683 s / -0.18%`) amid large variance;
2. hosted exact-pack 3x3: candidate median **+1.975 s worse**, with reload -> FancyMenu **+2.095 s worse**.

The optimization substantially reduces a counted workload but does not produce a reliable time-to-main-menu win. It also deliberately changes item appearance, so there is no justification for retaining that behavior change without startup benefit.

Do **not** request more repetitive laptop runs for this exact design and do not merge #79.

## Reopening criteria

Reopen only if a materially different premise is demonstrated, for example:

- a separately proven downstream atlas-size/upload bottleneck where crossing the exact pack from 8192x8192 to 8192x4096 has measurable critical-wall leverage independent of this model remap; or
- a different item-representation mechanism that removes materially more critical work without the current behavior/cost tradeoff.

Merely re-observing that Decocraft has thousands of PNGs or that 3,192 suppliers can be removed is not sufficient; those facts were already tested.

## Relationship to retained production optimization

This rejection does **not** affect the existing guarded Decocraft quarter-turn BBModel quad reuse. That production optimization remains retained independently.
