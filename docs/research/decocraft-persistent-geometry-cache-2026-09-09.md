# Decocraft persistent geometry cache audit — 2026-09-09

**Status: REJECTED / NO-GO (no runtime prototype).**

## Scope

This audit evaluates an external, cross-launch cache owned by BootOptim for Decocraft 3.0.11 geometry. It does **not** modify the Decocraft JAR, does not cache transformed bytecode/JARs, and does not repeat the retained reload-local quarter-turn reuse from PR #50. Integration authority for the audit is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

The promotion gate is stricter than a CPU/count reduction: a persistable boundary must be semantically independent of live sprites/materials/`ModelState`/callbacks and must leave enough avoidable work to justify fingerprinting, file I/O and deserialization before an exact-pack TTMM A/B is warranted.

## Established measurements

- Production quarter-turn reuse handles 14,108 Decocraft bake calls as 3,527 bases plus 10,581 derived quarter-turn variants. It removes about 2.7 s of repeated geometry CPU, but historical critical-path leverage was only about 1 s. See PR #50 and `docs/optimizations/compatibility/decocraft-quarter-turn-reuse.md`.
- PR #217 isolated the remaining first/base geometry work: 3,527 first identities cost about **3.227 s exclusive** in the hosted exact-pack profile. This is the absolute ceiling for *all* work in those first bakes, not the ceiling for a safe persistent subset.
- PR #222 proved a semantically equivalent BootOptim-side prepare/commit split, but its reflective duplicate traversal cost about **4.133 s prepare + 1.559 s commit**, and the comparable hosted run regressed menu TTMM by **+1.907 s**. It therefore proves the conceptual boundary, not an economical implementation.
- PR #224 is a materially different in-mod V2 experiment that avoids already-verified internal rotations. Its hosted 3x3 median was about **-1.092 s TTMM** with an outlier. The physical P0.2 evidence is order/confound sensitive and does not validate persistence. This audit performs no new physical testing and remains **sin evidencia física** for a persistent cache.

## Exact Decocraft 3.0.11 boundary

The released 3.0.11 `BlockbenchBakery` bytecode used by the existing public inspection workflows has this relevant shape:

- `bake(BBModel, BakedQuadBuilder, ModelBaker, Function<Material, TextureAtlasSprite>, ModelState, BlockbenchSetting, Map<String, Material>, Map<String, TextureAtlasSprite>, ...)`
- private traversal through `bakeBones`, `bakeBone` and `bakeElement`
- private `makeVertexData(BBElement, Vector3f)`
- private `bakeVertex(float[], BBDirection.Element, Vector3f, Matrix4f, BlockbenchSetting, ..., BBRotation, Matrix4f, ModelState, Matrix4f)`
- private `fillVertex(..., TextureAtlasSprite, Direction, ..., Matrix4f, Tag, Matrix4f, ...)`

`bakeVertex` performs element-origin rotation, group/ancestor matrix transforms, model rotation, Blockbench scale and optional locator transforms before calling `fillVertex`. `fillVertex` already consumes the live `TextureAtlasSprite` and other bake-time state. Consequently, final vertex arrays/quads are not a process-persistent pure value unless resource- and `ModelState`-dependent work is incorrectly frozen.

The only defensible persistent cut is therefore **primitive geometry before live `ModelState`/sprite/material binding**. PR #222 represented essentially this cut with prepared faces/vertices while resolving live resources at commit time. However, Decocraft does not expose that prepared collection or a stable model/resource identifier at `bakeVertex`; the data is synthesized inside private hot-loop methods.

## Candidate matrix

| Candidate unit | Purity / invalidation | Size / I/O expectation | Rehydrate cost | Semantic safety | Maximum useful saving | BootOptim hook | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Final `BakedModel` / `BakedQuad` | Impure: captures sprite atlas, material resolution, `ModelState`, tint/culling/resource-reload state | Largest; duplicates fully baked output | Low-to-medium, but must rebuild live references anyway | **Unsafe** across reloads/processes | Superficially high, but invalid because it freezes live state | `BBModel#bake` return | **Reject** |
| Parsed/live `BBModel` tree | Mutable Decocraft objects; must invalidate on resource/config changes | Medium | Still runs target geometry bake | Low: object lifecycle/callback ownership remains live | Near zero for the measured geometry target | model load/deserialization | **Reject** |
| PR #222-style prepared faces/vertices | Can be made primitive-only, but exact graph/resource fingerprint required; live commit still mandatory | At least per-face XYZ/UV plus metadata, strings, framing/checksums; actual pack size unmeasured | Deserialize + material/sprite/`ModelState` commit | Medium only with exhaustive fingerprint | <3.227 s and necessarily less after live commit | duplicate BB graph traversal + commit | **Reject**: same architecture already lost +1.907 s TTMM before disk costs |
| **3,527-base pre-live primitive geometry** | Best theoretical unit. Pure only before live resource/`ModelState` binding and only if every geometry-affecting input is fingerprinted | Lower than caching all 14,108 variants; minimum grows with every face/vertex plus framing; actual pack footprint unmeasured | File read + decode + per-face live commit | Potentially safe in principle | **<3.227 s exclusive absolute ceiling**; safe subset is smaller | private `bakeElement`/`bakeVertex`, version-pinned `@Pseudo` mixin | **Reject for current 3.0.11**: no stable asset key is carried to this boundary; safe lookup requires duplicating traversal or proving deterministic cross-process ordering |
| All 14,108 quarter-turn outputs | Same purity problem, plus redundant derived variants | ~4x base population before metadata | More I/O than base-only | No advantage over base-only | Does not expand first-base ceiling; repeats #50 territory | geometry helper / Decocraft bake | **Reject** |
| Transformed Decocraft JAR/bytecode | Not the preferred data boundary; update/signature/permission/AV/multi-instance/rollback concerns | Artifact-scale | Classloader/transform integration complexity | High operational risk | No demonstrated superiority to in-memory transforms | launcher/JAR layer | **Reject** |

## Why the best theoretical candidate still fails the gate

A cross-process hit needs a key that is stable before doing the work it intends to avoid. At the safe private vertex/element boundary, Decocraft 3.0.11 receives live `BBElement`/`BBGroup` objects and matrices, not a durable asset `ResourceLocation` or content key. Java identity cannot survive a process. A traversal ordinal would only be valid if construction and map/list iteration order were proved deterministic across processes and across all resource/callback paths; that proof does not exist. Hashing the complete mutable BB graph at the hot point traverses the geometry again and must include every field that #222 had to reflect (`from`, `to`, `origin`, rotations, inflate, faces, UVs, texture references, bone hierarchy, settings), eroding the already-small ceiling.

Capturing a durable resource key earlier and carrying it to the private bake would require a new cross-lifecycle context from model/resource loading into Decocraft's private traversal. That is no longer a local cache hook: it expands the semantic surface to generated resources, resource-pack ordering and callbacks, and there is no evidence in 3.0.11 that the resulting key uniquely identifies the mutable geometry seen at bake time.

Even assuming a perfect key, persistence cannot skip the live commit. The only measured whole-base ceiling is 3.227 s exclusive. PR #222's equivalent external IR already made the hosted TTMM worse before adding cache-file fingerprinting, disk read, decoding, checksums, corruption handling and cleanup. PR #224 shows that the geometry front can have wall-clock leverage, but it does not show that cross-launch serialization can retain that leverage.

Therefore a runtime prototype would currently test an implementation whose semantic key is unproven and whose economic ceiling is below the full 3.227 s base interval. Per the project escalation rule, that is insufficient to justify code or hosted A/B churn.

## Persistent-format contract if this front is reopened

A future implementation must use a separate namespace such as `.bootoptim/decocraft-geometry/`, not the mod-scan cache format. The cache format must have its own magic/schema version and fingerprint at least:

- SHA-256 of the exact Decocraft artifact and Decocraft version;
- Minecraft, NeoForge and BootOptim versions/build identities;
- exact active resource-pack stack/order and content hashes for every Decocraft/Blockbench/model asset that can affect the cached geometry, including generated resources where applicable;
- every geometry-affecting Decocraft config / `BlockbenchSetting` field;
- hook/class-layout signature guard for the pinned Decocraft implementation.

Only primitive values and stable textual identifiers may be stored; sprites, `BakedModel`, `BakedQuad`, GL/native handles, callbacks, mod objects and live `ResourceLocation` objects must be resolved/rebuilt in the current reload. Writes must be temp-file + atomic replacement, with magic/version/checksum verification, bounded entry/total sizes, corruption or mismatch => ignore and run stock, and cleanup restricted to BootOptim's own cache directory. Concurrent instances must not observe partial files.

## Reopening criteria

Reopen only if at least one material premise changes:

1. a Decocraft version exposes a stable model/asset identifier at or immediately above a primitive pre-live geometry boundary; or
2. a BootOptim/NeoForge hook can prove an immutable content-addressed model key is propagated to that boundary without traversing/recomputing the BB graph and without relying on process-local order; and
3. instrumentation isolates a persistable subset with enough exclusive/critical-path wall to beat measured fingerprint + read + decode + live-commit cost.

If those conditions are met, the first experiment should be default-off/fail-open and should measure cache hit/miss, fingerprint time, bytes read/written, decode time, stock work actually omitted, and TTMM in same-origin/same-endpoint hosted exact-pack 3x3 A/B before any promotion claim.

## Decision

**NO-GO for a Decocraft 3.0.11 persistent geometry cache from current BootOptim integration.** No runtime code or A/B campaign is warranted because the only semantically plausible cut lacks a proven durable lookup key, and its real savings ceiling is a strict subset of the 3.227 s first-base interval while disk/fingerprint/rehydration costs are additive. Keep the front reopenable under the criteria above; do not infer a persistent-cache win from #224 or from CPU/count reductions.

References: PR #37 (historical), PR #50 (retained reload-local reuse), PR #217 (first-base attribution), PR #222 (prepare/commit experiment), PR #224 (in-mod V2 experiment), `docs/research/README.md`, and `docs/research/exact-pack-ci.md`.