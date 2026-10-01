# Decocraft 3.0.11 direct-fork viability — 2026-09-11

Status: **NO-GO FOR A DISTRIBUTABLE FORK WITHOUT AUTHOR PERMISSION / SOURCE**

Agent: 121 (architecture)

Authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This entry follows #217, #222, #224, #250 and #253. It does not implement or redistribute Decocraft source. It evaluates whether the only remaining reopening criterion from #253 — a direct Decocraft-side prepared-sequence API/fork — is legally and technically reproducible from public material.

## Decision

Do **not** create or publish a Decocraft 3.0.11 fork from the publicly distributed JAR.

The current public project pages identify Decocraft as **All Rights Reserved / ARR**:

- CurseForge project/license page: https://www.curseforge.com/minecraft/mc-mods/decocraft/license
- Modrinth project page: https://modrinth.com/mod/decocraft

Both pages identify the current 1.21.1 NeoForge release as `decocraft-3.0.11-1.21.1-neoforge.jar`. The exact artifact already pinned by #224/#253 has SHA-256:

`b0589eb7d03b13bbf3b9c45df7f50db556a721882e9ad2bb6be0ff23e5a64526`

Public GitHub repository/code searches performed for this investigation did not locate an author-controlled source repository containing the 3.0.11 classes under `com.razz.decocraft.models.bbmodel`. Searches for the exact package/class names returned external compatibility code, notably `HoYin1600p/VH-Accelerator`, rather than Decocraft source. The public GitHub repository historically associated with the team is `ProfMobius/DecocraftTranslate`, which contains translation material, not the mod implementation. A 2016 Minecraft Forum discussion likewise points users to that translation repository when asking about GitHub/source progress.

Therefore there is no public, source-level commit that can be pinned as the reproducible origin of 3.0.11, and ARR does not provide permission to publish a derivative source fork. The fact that the binary can be downloaded, inspected, or used in a modpack does not supply a redistribution/fork license.

A direct fork becomes eligible only if one of these occurs:

1. the Decocraft authors publish the relevant 3.0.11 source under a license that permits modification and redistribution; or
2. the authors provide explicit permission to this project/user to maintain and distribute the derivative build, together with a source snapshot or repository revision that can be pinned reproducibly.

Until then, the direct-fork lane is closed. Do not reconstruct a source tree from decompiled 3.0.11 bytecode for publication.

## Why this was the last architectural reopening

#217 measured the addressable first/base Decocraft lane: 3,527 first `BBGeometry` identities accounted for 3,227.384 ms exclusive in its hosted causal profile. This is a historical exclusive-cost ceiling, not a savings estimate.

#222 demonstrated that the conceptual split is representable: a reload-local pre-sprite geometry form can preserve the final Decocraft output. Its exact-pack output probe matched 3,527 models / 964,046 quads and all raw vertex lanes after UV normalization. That implementation was rejected because reflective prepare plus commit cost about 5.692 s, prepare and commit remained on the same reload worker, and the final comparable pair regressed the relevant reload interval by 3.015 s and menu by 1.907 s.

#224 established an additional semantic invariant: internal Decocraft methods cannot be treated as pure arithmetic merely because their result can be reproduced. Skipping the complete `applyElementRotation` body changed final representation even though the repeated-corner XYZ value itself was reproducible. The corrected experiment had to execute the stock method body so receiver state and third-party injection surfaces remained observable and ordered.

#250 changed the scheduling premise. The useful non-null parsed `BBModel` domain exists materially before first consumption: 3,527 eligible models, with roughly 11.23–14.85 s between source availability and first/base consume in the single hosted causal trace. It explicitly rejected preparing all 4,056 `BBGeometry` constructors and made no speedup claim.

#253 then closed the BootOptim-external implementation route. In pinned 3.0.11 bytecode, `BlockbenchModel.buildQuads` owns both the `elements -> faces.keySet/get -> filters` traversal and the `BAKERY.bakeQuad` calls. Replacing/cancelling that body avoids late traversal but changes its callback/injection/failure surface; letting it execute preserves those observables but repeats the traversal. The only clean reopening is therefore a Decocraft-owned API that changes the implementation from the inside.

## Minimal source-level API that would justify reopening

The following is a design contract, not copied/decompiled source and not an implementation proposal for BootOptim. Names are illustrative but deliberately exact enough to gate a future author-controlled fork.

### Producer

Add a package-private immutable preparation product owned by the same reload generation as the parsed `BBModel`:

```text
PreparedBakeSequence BBModel.prepareBakeSequence()
```

with a representation equivalent in shape to:

```text
PreparedBakeSequence(
    int rootElementIndex,
    ImmutableList<PreparedFace> faces
)

PreparedFace(
    int elementIndex,
    Direction direction,
    int textureIndex,
    immutable raw UV values,
    immutable element bounds/inflate/shade values needed before sprite binding
)
```

The prepared form must preserve source iteration order exactly and must not contain a `BakedModel`, `BakedQuad`, `TextureAtlasSprite`, `ModelState`, GL/render object, persistent-cache value, or cross-reload identity key. `elementIndex` is an ordinal into the original reload-local `BBModel.elements` collection, not an identity cache; the original element remains available for the late stock side-effectful calls.

Preparation is eligible only for the already parsed, non-null `BBModel` first/base domain. Null/unsupported/custom cases keep the current path unchanged. The product is stored directly on the owning reload-local Decocraft object or returned through an author-owned future; no BootOptim identity map or structural fingerprint reconnects producer and consumer.

### Consumer

Keep `BlockbenchModel.buildQuads(...)` as the stock-owned late consumer and add an internal overload/path that accepts the prepared sequence:

```text
buildQuads(spriteGetter, PreparedBakeSequence prepared)
```

The method must still perform, in current-generation order:

- Decocraft settings/material resolution;
- `spriteGetter` lookup and sprite-null handling;
- all late filters whose observable result/failure depends on current state;
- calls into the existing stock `BAKERY.bakeQuad` path;
- `ModelState` transform access;
- facing calculation;
- `applyElementRotation` and any other side-effectful stock method exactly once and in the same local order;
- fresh `BakedQuad` construction and publication.

The only removed work is the second walk of `BBModel.elements` / each face map that the producer has already flattened. The late consumer uses `elementIndex` to recover the original reload-local element for stock side-effectful bakery calls without graph traversal.

### Injection/callback compatibility

A source fork cannot merely replace `buildQuads` with unrelated control flow and declare equivalence. The compatibility goal is:

- preserve the public/private method names and descriptors used by 3.0.11 where practical;
- preserve `buildQuads` HEAD/RETURN and stock `BAKERY.bakeQuad` / `applyElementRotation` invocation surfaces;
- preserve invocation count and face order for every stock late callback;
- avoid moving sprite/material/ModelState callbacks into the producer;
- treat any mixin that targets the exact old iterator/local bytecode inside `buildQuads` as a known compatibility risk requiring explicit test coverage rather than silently claiming binary-injection equivalence.

This is the principal residual risk even for an author-controlled fork: source-level refactoring can preserve semantic callbacks while changing bytecode-local injection points. A future fork therefore needs a small compatibility inventory of exact-pack mixins targeting Decocraft, not only output fingerprints.

### Failure ordering

The producer must not make malformed-model failures observable earlier merely because preparation runs earlier. The safe contract is that preparation only snapshots data whose access is proven side-effect-free for the supported 3.0.11 internal model, while state-dependent validation and exceptions remain in `buildQuads`/bakery order.

If exact failure timing cannot be preserved for a malformed/custom graph, the producer must mark that model as `stock_required` and let the original traversal execute. Do not catch arbitrary `Throwable` early and replay it later as though exception identity/timing were automatically equivalent.

## Reproducibility requirements if permission/source appears

A source-authorized fork is versionable only if all of the following are recorded:

- authoritative upstream repository URL;
- exact upstream commit/tag corresponding to the 3.0.11 binary, or a documented source snapshot supplied by the author;
- license text or explicit permission covering modification and redistribution of the derivative build;
- deterministic build instructions and resulting JAR SHA-256;
- a narrowly scoped patch containing only the prepared-sequence API/consumer change plus required tests;
- no vendored decompiled source in BootOptim.

The fork should live in its own repository, not be silently copied into BootOptim.

## Validation gate after — and only after — legal/source reopening

Do not start with a laptop run. First prove the source patch itself:

1. Build the fork reproducibly and confirm the exact pack selects that artifact and no stock Decocraft JAR survives beside it.
2. Re-run the #222 sprite-local/raw-lane equivalence gate for the full 3,527-model / 964,046-quad domain, plus resources 14/14 in order, `reload_count=1`, unchanged normalized error set and zero BootOptim Mixin errors.
3. Add callback/side-effect probes sufficient to prove `applyElementRotation` and other stock late boundaries execute once/in-order; audit exact-pack mixins that inject into Decocraft methods touched by the refactor.
4. Trace actual prepare begin/end/blocked/consume edges against the #214/#250 ModelManager DAG. Reject if prepare blocks the critical worker, runs on the same worker immediately before consume, or causes duplicate traversal.
5. Only then run interleaved hosted exact-pack >=3x3 A/B and require movement in both the causal ModelManager dependency interval and `main_menu` wall. Counts, prepared CPU, or the 11–15 s eligibility window alone are not savings.
6. A physical-laptop validation is a later promotion gate only if hosted causality and end-to-end benefit are coherent.

## Cost / risk assessment

Technical patch size could be small in an author-controlled 3.0.11 tree — one immutable sequence type, one producer, one field/future handoff and one alternate internal traversal input — but semantic risk is not small. The main risks are bytecode-local mixin compatibility, malformed/custom model failure ordering, mutation of the parsed graph between prepare and consume, executor contention, and accidentally shifting current-generation sprite/ModelState work early.

Legal/reproducibility risk is currently decisive: with ARR and no public source commit, there is no acceptable path to publish the fork. That blocks implementation before performance work.

## Final disposition

**NO-GO now.** #253 remains closed from outside BootOptim, and a direct source fork is also closed under the currently public licensing/source situation.

The next decision is external, not technical: obtain an author-published source repository/license or explicit fork/redistribution permission plus a reproducible 3.0.11 source snapshot. If that arrives, reopen exactly the minimal prepared-sequence API above; otherwise do not spend more profiling/A-B/laptop time on this lane.
