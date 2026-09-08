# Real BlockModel class replay — 2026-09-08

Status: **REAL FML CLASS REPLAY / TOOLING-ONLY / NO RUNTIME CHANGE / NOT TTMM EVIDENCE**

Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This is the class-level measurement layer requested by #181 before another ModelManager IR is considered. It reuses #178's public exact-pack fixture as input/provenance, but it does not use the Python graph as a Minecraft semantic oracle and does not reopen the rejected #36/#66/#152/#153/#170/#171/#177 optimization families.

The requested `docs/research/next-optimization-campaign-2026-09-08.md` is absent at the authoritative base SHA and was not found in the visible #178–#182 changes. Its contents were not invented.

## Result

The bounded replay now runs inside the project's real NeoForge/FML `runData` lifecycle (`forgedatadev`), so `LoadingModList`, Minecraft registries and the real client model classes are initialized without starting a graphical client. The tooling-only event subscriber is injected into the dev source set exclusively by `tools/model-class-replay/model-replay.init.gradle`; normal BootOptim builds and packaged JARs do not include `src/modelReplay`.

Real paths exercised:

- `BlockModel.fromStream(Reader)` for model JSON;
- real `BlockModel`, `BlockModel.GuiLight`, `BlockElement` and `BlockElementFace` state;
- `BlockModel.resolveParents(Function)` with fixture models and runtime vanilla fallback;
- `BlockModel#getMaterial(face.texture())` for texture-chain/material lookup;
- NeoForge `ElementsModel(List<BlockElement>)` construction for ordinary elements.

The runtime also reports `net.neoforged.neoforge.client.model.ExtendedBlockModelDeserializer` as present and `BlockModel`'s Gson adapter as `com.google.gson.internal.bind.TreeTypeAdapter`. No private parser substitutes the real model deserializer.

## Why FML lifecycle is required

A clean Gradle `JavaExec` is intentionally retained as a boundary test. `BlockModel.resolveParents` reaches `ModelBakery`; calling `Bootstrap.bootStrap()` without FML state reaches `FeatureFlagLoader.loadModdedFlags()`, where `LoadingModList.get()` is null. The workflow asserts this exact blocker instead of installing a fake `LoadingModList`.

The actual replay then crosses that boundary using `runData`, whose logs show ModLauncher/FML 4.0.43, Minecraft 1.21.1 and NeoForge 21.1.248. This is still headless tooling: it is not an exact-pack client startup and does not exercise renderer/GL.

## Fixture and provenance

Pinned public fixture:

- tag `exact-pack-2026-09-02-v1`;
- asset `bootoptim-exact-pack.zip`;
- SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

The bounded enumeration observed:

- 35,384 candidate model resources;
- 32,221 logical model IDs;
- 3,163 shadowed entries retained in provenance;
- 24 deterministic roots / raw parent-closure entries;
- 25 real `BlockModel` instances after runtime classpath parent fallback (`minecraft:block/block`);
- 429 real face texture/material lookups.

Selected external packs are read from `options.txt` and kept in low-to-high order. The sample winners are primarily vanilla IDs overridden by `Redstone Tweaks 2.4.5.zip`; each materialized resource records source id, archive entry, SHA-256, winner and shadowed candidates. The helper has unit tests for selected-pack order, disabled packs, shadow provenance and raw parent-byte closure.

Important limitation: the standalone helper explicitly labels mod-to-mod precedence as `bounded_helper_mod_filename_order`. It is not claimed to reproduce NeoForge's complete mod-resource-pack stack ordering. The bounded roots are ordinary vanilla-domain models; `runData` loads BootOptim/Minecraft/NeoForge, not all 160 exact-pack mods. Therefore full-pack custom model loaders and mod callbacks remain a later exact-pack client-smoke boundary.

## Canonical digest and falsification gates

The timing-independent semantic digest includes, where exposed at this boundary:

- winning resource identity/provenance and shadow count;
- requested parent, resolved parent chain, depth/fan-out;
- GUI light and ambient occlusion;
- ordered elements and faces;
- raw IEEE-754 float bits for coordinates, rotations, UV and known transforms;
- shade, element rotation, tint, cull and face rotation;
- texture references before resolution;
- resolved `Material` atlas/texture IDs;
- sorted observed errors/fallback events.

Two separate `runData` JVM executions produced the same semantic SHA-256:

`502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`

Within each execution:

- `stock-1` == `stock-2` semantically;
- `identity-candidate` == stock semantically while traversing the same real path;
- a deliberate texture mutation is rejected with the readable diff
  `$.models[1].elements[0].faces[0].texture_ref stock="#bottom" candidate="#bootoptim_deliberate_fault"`.

GitHub Actions run #21 (`34173324041`) is green for the real class replay and published artifact `real-model-class-replay` (`10036433904`, artifact ZIP SHA-256 `fd88f59468af58fe94ab7991213cc6f64c1855b3b48dbf91bc7a3d304b2066a8`).

## Controlled error/fallback behavior

Because the 24 exact-pack roots are valid and produced no error events, the same real FML lifecycle also runs controlled probes outside the performance sample:

- missing parent `bootoptim_test:not_present`: resolver requests the missing id and `minecraft:builtin/missing`; stock logs the missing parent and terminates with `java.lang.IllegalStateException: BlockModel parent has to be a block model.`;
- cycle A→B→A: resolver requests both ids and the missing-model fallback; stock logs the parent loop and terminates with the same `IllegalStateException`;
- invalid JSON: real `BlockModel.fromStream` exposes `com.google.gson.JsonSyntaxException`.

The cyclic invalid model is deliberately **not** continued into ordinary geometry lowering. An earlier probe showed that doing so can recurse through NeoForge `BlockGeometryBakingContext#getCustomGeometry`; that is not a valid continuation after parent-resolution failure.

## Phase measurements

Measurements are exclusive scopes, never summed as overlapping startup work. Wall uses `System.nanoTime`; CPU uses current-thread `ThreadMXBean`; allocation is the HotSpot current-thread allocated-byte counter when supported.

Representative stock-first measurements from run #21:

| JVM | phase | wall ms | CPU ms | allocated bytes | count |
|---|---|---:|---:|---:|---:|
| run1 | real BlockModel parse | 33.989 | 32.958 | 8,846,824 | 24 |
| run1 | real parent resolution | 2.733 | 2.711 | 236,760 | 25 |
| run1 | real texture/material lookup | 3.442 | 3.398 | 840,704 | 429 |
| run1 | real ElementsModel construction | 0.725 | 0.696 | 175,304 | 24 |
| run2 | real BlockModel parse | 51.993 | 33.150 | 8,843,416 | 24 |
| run2 | real parent resolution | 7.517 | 2.747 | 236,328 | 25 |
| run2 | real texture/material lookup | 6.683 | 3.681 | 839,776 | 429 |
| run2 | real ElementsModel construction | 0.641 | 0.638 | 175,040 | 24 |

The first-pass parse phase is the largest CPU/allocation scope in this tiny bounded sample, but these values are diagnostic only. Warm `stock-2`/identity passes are much faster due to JIT/cache state and are not speed evidence for a candidate. None of these numbers are TTMM, resource-reload duration, HDD/page-cache behavior or physical-laptop evidence.

## Explicitly unobserved

The digest does **not** declare equivalence for:

- current `TextureAtlasSprite` identity or atlas generation;
- `FaceBakery` output;
- `BakedQuad` vertex ints;
- final render-type buckets;
- `ModelState`-dependent cull rotation;
- model-builder or baking callbacks, including `ModifyBakingResult`/`BakingCompleted`;
- GL/upload state.

The claim is only: reproducible equivalence of the observed pre-sprite/pre-bake structure for the bounded real-class sample.

## Decision and next candidate

The minimum class-level harness is now viable, so #181's measurement prerequisite is satisfied for this bounded domain. This does **not** justify another structural reload-local IR by itself.

The next concrete candidate should target the measured parse/allocation scope rather than materials or geometry plans already rejected. A narrow default-off experiment is **deserializer collection-capacity pre-sizing for ordinary `BlockModel` element/face structures**, driven only by already-known JSON array/member cardinality and preserving insertion order and exact float bits. It must first be confirmed by allocation-stack profiling to hit resize/growth work in the real deserializer; then stock/candidate must pass this digest and controlled error gates before any exact-pack client smoke. This is not a cache, persistent IR, material pool, cull-direction shortcut or identity-call optimization.

After a class-level win, the next evidence boundary is an instrumented exact-pack client smoke at the ModelManager pre-sprite/pre-bake boundary. Only after that should TTMM/exact-pack startup validation be considered. No physical laptop test is requested by this work.
