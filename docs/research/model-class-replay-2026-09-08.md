# Real BlockModel class replay — 2026-09-08

Status: **DIAGNOSTIC HARNESS / NO RUNTIME CHANGE / CI EVIDENCE REQUIRED**

Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This is the class-level reopening step required by PR #181. It does not implement a model optimization. It replaces none of Minecraft's runtime code, adds no mixin, and never runs on the physical laptop.

## Historical boundary

This harness exists because the remaining ModelManager work cannot be justified by another approximate representation:

- #36 removed 64.57% of exact top-level identities but only about 0.413 s / 4.7% from `bakeModels` and did not improve end-to-end startup.
- #66 proved 5,448/5,448 repeated ordinary geometries equivalent with zero mismatches/fallbacks, yet the safe candidate was slower (`189.190 ms` vs `179.709 ms`) and even its unsafe trusted ceiling was slower (`102.394 ms` vs `91.267 ms`).
- #152/#177 cover material lookup memo/list-allocation families; #170/#171 cover direct/thresholded reload-local material plans; #153 covers cull/ModelState precompute. Their results do not justify repackaging the same work as another IR.
- #178 is useful for fixture/dependency/scheduling experiments but its Python graph is explicitly not Minecraft deserialization, parent resolution, material lookup, or bake semantics.
- #181 therefore requires new class-level evidence before reopening a structural model candidate.

`docs/research/next-optimization-campaign-2026-09-08.md` was requested as a prerequisite but is not present at the authoritative integration SHA and is not present in the currently visible #178–#182 PRs. This harness does not invent its contents; #181's explicit reopening criterion is used instead.

## Exact fixture and provenance

Workflow `.github/workflows/model-class-replay.yml` downloads the same public fixture defined by `exact-pack-ci.md`:

- tag `exact-pack-2026-09-02-v1`;
- asset `bootoptim-exact-pack.zip`;
- SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

`scripts/exact-pack/prepare_fixture.py` remains authoritative for SHA verification/extraction and for requiring `options.txt`.

`tools/model-class-replay/prepare_fixture.py` is an **enumeration/provenance helper only**. It never supplies model semantics to the measured replay. It:

1. reads the selected external `file/...` resource packs from `options.txt`;
2. preserves their low-to-high selected order and treats later selected packs as higher priority;
3. records every shadowed model candidate and the winning source/entry/hash;
4. materializes only the deterministic selected roots plus their raw-parent byte closure by default;
5. supports `--full` for all discovered winning model resources;
6. records Minecraft builtin parents as a runtime-classpath fallback.

NeoForge mod resources are below user-selected resource packs, but this standalone helper does not reconstruct NeoForge's exact mod-to-mod pack order. Mod-archive winners are explicitly marked `bounded_helper_mod_filename_order`; the bounded selector strongly prefers external-resource-pack winners whose precedence follows `options.txt`. A future full-pack oracle must replace that weak tier with a captured/real NeoForge resource stack before making global precedence claims.

The helper's JSON parser is used only to select representative shapes and bound the parent byte closure. Parent resolution itself is never taken from that parser.

## Real classes and phases

The Java runner lives only in the `modelReplay` source set injected with `-I tools/model-class-replay/model-replay.init.gradle`; it is not packaged into BootOptim.

Every repetition creates fresh model instances from the same input bytes and measures non-overlapping scopes on the current replay thread:

1. **`real_blockmodel_parse`** — invokes the runtime `BlockModel.fromStream(Reader)` entry point. This is the Minecraft/NeoForge parser path; runtime metadata also records any discoverable BlockModel Gson adapter / extended-deserializer class.
2. **`real_parent_resolution`** — invokes the runtime `BlockModel.resolveParents(Function)` method. The resolver returns real `BlockModel` instances parsed through the same entry point. Missing lookups, thrown errors and final parent-chain state are recorded.
3. **`real_texture_chain_material_lookup`** — traverses the real resolved element/face objects and calls `BlockModel#getMaterial(face.texture())`, preserving the runtime texture-reference/parent-chain algorithm.
4. **`real_elementsmodel_construction`** — constructs NeoForge's real `ElementsModel(List<BlockElement>)` for ordinary resolved element lists. This is the headless ordinary-elements boundary that can run without sprite/renderer state.

Each phase records wall nanoseconds, current-thread CPU nanoseconds and HotSpot thread allocated bytes when supported. Allocation measurement is a VM counter, not JFR, and is enabled before a phase starts. These metrics are class-replay measurements only; they are not summed into TTMM or a removable-startup claim.

## Canonical semantic digest

The semantic payload is deterministic and excludes timing. For every observed model it records:

- model id, winning source/entry/SHA and shadow count;
- requested parent, complete resolved parent chain and fan-out;
- GUI light / ambient occlusion where exposed;
- known item-transform vector fields where exposed;
- ordered elements and ordered face-map traversal;
- exact raw IEEE-754 float bits for `from`/`to`, rotation and UV values when exposed;
- shade, light-emission/extended element data when structurally observable;
- direction, tint, cull, raw texture reference and extended face data;
- resolved `Material` atlas/texture identifiers when exposed;
- sorted fallback/error class and cause observations.

Opaque NeoForge metadata is represented as opaque rather than silently called equal.

The digest always carries an explicit unobserved list. This first headless layer does **not** claim equivalence for:

- current `TextureAtlasSprite` identity / atlas generation;
- `FaceBakery` output;
- `BakedQuad` vertex ints;
- final render-type buckets;
- `IModelBuilder` callback output;
- `ModelState`-dependent cull rotation;
- `ModifyBakingResult` / `BakingCompleted` callbacks;
- GL/upload state.

Those omissions are a compatibility boundary, not an equivalence result.

## Harness falsification tests

`modelReplaySelfTest` uses controlled JSON but still sends every model through the real `BlockModel` parser and parent/material paths. It includes inheritance, texture references, ordinary elements, display transforms, a missing parent, and an explicit parent cycle.

The exact-pack task runs three fresh class paths per invocation: `stock-1`, `stock-2`, and `identity-candidate`. The identity candidate deliberately executes the same code and must produce the same canonical digest. A semantic fault probe then mutates one face texture reference in the candidate digest; the comparator must return an exact JSON path and fail if the corruption is not detected.

The workflow runs the entire exact-pack replay in two separate Gradle/JVM executions and requires the stock SHA-256 to match across processes. JSON, CSV, provenance and Markdown summary files are uploaded as `real-model-class-replay`.

## What this can decide

A green run establishes only that a bounded exact-pack subset can execute the real headless `BlockModel` parse/parent/material/ordinary-geometry boundary reproducibly, and that the digest can reject a deliberate semantic change. It does not prove a future candidate faster, and it does not measure Minecraft startup.

The next optimization candidate is conditional on the phase data. The only justified next implementation would target a newly measured class-level cost that is both material and outside the rejected identity/traversal/material-list/material-plan/cull families. For example, if real `BlockModel.fromStream` + parent resolution shows substantial exclusive CPU/allocation concentrated in a strict ordinary-vanilla subset, the next experiment can be a default-off **parse/parent structural reconstruction candidate** that still reconstructs fresh real `BlockModel` state and leaves current sprites, `FaceBakery`, model state and callbacks authoritative. If this replay instead shows those scopes are small, that candidate is a no-go and work should move below `FaceBakery` or to another measured critical branch.

A later smoke exact-pack client instrumented at ModelManager is the next boundary for fields unavailable headlessly. Physical laptop evidence remains a separate promotion gate and is not requested by this PR.
