# Decocraft early primitive prepare seam — 2026-09-11

Status: **REJECTED / NO-GO BEFORE A/B**

PR: agent 119 experimental branch. This result reopens the #222 prepared-geometry idea only under the new premise established by #250: the exact-pack first/base Decocraft `BBGeometry` set with a non-null `BBModel` (3,527 instances) exists materially before its first bake consumption.

## Question

Can BootOptim 1.21.1, pinned to Decocraft 3.0.11 and default-off, start one reload-local immutable/primitive preparation as soon as the loader installs a non-null `BBModel`, then consume that product during the later bake without:

- repeating the same element/face graph traversal materially;
- storing a `BakedModel`, `BakedQuad`, sprite, GL object, persistent cache, identity cache, reflection result, or structural fingerprint;
- moving sprite/`ModelState`/render-thread work early;
- removing or reordering Decocraft callbacks/observable side effects;
- weakening stock exception/fallback behavior.

Per the experiment gate, failure to find such an integration seam is a no-go before performance A/B.

## Prior evidence

#222 proved that the pre-sprite geometry can be represented with output equivalence for the exact pack: 3,527 models, 964,046 quads, the complete model key set, and all eight raw vertex lanes. Its reflective prepare/commit implementation was rejected because prepare and commit both ran on `Worker-ResourceReload-1`, the implementation added about 5.692 s of measured prepare+commit work, and the final comparable hosted pair regressed `startup_total_ms` by 1,907 ms and `reload_to_fancymenu_finish_ms` by 3,015 ms.

#250 changed the scheduling premise: for the 3,527 first/base geometries with non-null `BBModel`, the naturally loaded graph precedes first consumption by roughly 11.23–14.85 s in hosted exact-pack traces. It explicitly excluded the full 4,056-constructor population.

#224 is a semantic constraint on any replacement: `BlockbenchBakery.applyElementRotation` cannot simply be skipped. The accepted experiment had to execute the whole stock method so its reusable quaternion/matrix/vector mutations and third-party injection surface remained observable, suppressing only the proven internal rotation math when substituting a verified result.

## Hosted bytecode inspection

A branch-only GitHub Actions inspection downloaded the exact Decocraft 3.0.11 NeoForge JAR and verified SHA-256:

`b0589eb7d03b13bbf3b9c45df7f50db556a721882e9ad2bb6be0ff23e5a64526`

Workflow run: `34550461475`, job `103112088114` (`Agent119 Decocraft seam inspect`), completed successfully on hosted Ubuntu 22.04 / Temurin 21.

The relevant bytecode is direct and version-pinnable:

1. `BBModelGeometryLoader.read` resolves `BBModel` from `ModuleBlocks.MAT_TO_BB_MODEL`, then calls `new BBGeometry(materialName, bbModel)`.
2. `BBGeometry.<init>` only stores final `materialName` and final `bbModel`. Therefore a constructor-return mixin can observe the non-null model immediately without reflection or an identity map.
3. `BBGeometry.bake` first performs the stock `particle` / `texture0` material lookups and `spriteGetter.apply` calls, checks `bbModel != null`, creates `BBUnbakedModel`, then delegates to its bake. These late material/sprite callbacks must remain in their stock order.
4. `BlockbenchModel` stores the model's `elements` and `resolution`, stores the current `ModelState`, resolves the current material sprite, initializes `faceQuads`, then invokes its private `buildQuads(spriteGetter)`.
5. The private `buildQuads` method owns both the source traversal and the stock bake calls in one control-flow body. It initializes every direction key in `faceQuads`, walks `elements` to find `root_node`, resolves the current sprite, walks `elements` again, walks each `Element.faces.keySet()`, performs `faces.get(direction)`, rejects negative texture / null UV / null sprite cases, then invokes `BAKERY.bakeQuad(...)` and appends non-null quads.
6. `BlockbenchBakery.bakeQuad` then performs the UV-area rejection, reads element bounds/inflate, calls `makeVertexData`, calculates facing, fills all eight vertex lanes, and constructs a fresh `BakedQuad` with the current sprite. `makeVertexData` calls `bakeVertex` four times; `bakeVertex` calls `applyElementRotation`, applies Decocraft scale/root translation, applies the current `ModelState` transform, and finally `fillVertex` binds UV through current `TextureAtlasSprite.getU/getV`.

This confirms the conceptual #222 split, but also exposes the missing integration seam.

## Integration-seam decision

A primitive preparation can traverse the non-null `BBModel` at `BBGeometry` construction and copy ordered scalar geometry before sprites and `ModelState` exist. The problem is consumption.

For Decocraft 3.0.11 there is no stock method that accepts an already prepared ordered face/element sequence. The only method owning the element/face traversal is private `BlockbenchModel.buildQuads`, and its traversal is interleaved with the calls whose stock ordering/side effects must be retained.

There are only two BootOptim-side choices:

- **Cancel/replace `buildQuads` and iterate the prepared product.** This avoids the late source traversal, but it suppresses the stock `buildQuads` body and its injection/callback surface. #222 used this class of seam. Under the agent-119 contract this is not acceptable, even if `BAKERY.bakeQuad` were re-invoked manually, because callbacks/injections targeting the stock traversal body are no longer guaranteed once/in-order and stock local exception ordering changes.
- **Allow `buildQuads` to run and use the prepared product inside/under it.** This preserves stock callbacks and exception order, but the method necessarily executes the same `elements` and `faces.keySet/get` traversal after the early preparation. That is precisely the materially repeated traversal the reopened experiment forbids. Redirecting iterators/maps or replacing model fields to fake a prepared sequence would mutate observable Decocraft collection behavior and still create a second traversal layer.

Trying to precompute deeper `bakeQuad` math does not solve the seam either. Preserving #224 requires the stock `applyElementRotation` body to execute; current sprite UV binding and current `ModelState` transforms are intentionally late. Skipping those calls would violate the semantic contract, while executing them and also computing their equivalent early repeats the work instead of moving it.

Therefore **no agent-119 runtime candidate is implemented**. The new scheduling window is real, but BootOptim cannot consume a primitive immutable product through Decocraft 3.0.11's current API without either materially re-traversing the graph or replacing/mutating observable stock control flow.

## Validation disposition

The structural gate failed before runtime candidate creation, so the required 3×3 candidate/control A/B is deliberately **not run**. Running it would measure no conforming mechanism and would violate the instruction to close as no-go before A/B when the product cannot be integrated safely.

Likewise, the #222 equivalence probe is not rerun as if it validated a new runtime candidate. Its 3,527-model / 964,046-quad / eight-lane evidence remains the representation proof only. There is no new code path whose resources 14/14, reload=1, callback ordering, sprite generation, or menu endpoint could honestly be claimed validated.

The hosted bytecode-inspection job itself passed and pins the exact Decocraft artifact. The repository's normal build check is allowed to run for the research branch, but a green compile cannot convert this architectural no-go into a candidate.

## Risks avoided

Rejecting here avoids four concrete risks: suppressing third-party mixins/callbacks on `buildQuads`; changing material/sprite callback and exception order in `BBGeometry.bake`; moving `ModelState` or sprite-dependent work before the current reload generation is ready; and relying on an identity/fingerprint cache to reconnect early data to late models.

## Reopening criterion

Do not reopen this as another BootOptim `buildQuads` cancellation, identity map, fingerprint, or pre-baked quad cache.

A materially new premise would be a Decocraft-side, version-controlled API/fork that exposes a pure `BBModel -> primitive prepared sequence` stage and a stock-owned late consumer that accepts that sequence while retaining the same material/sprite/`ModelState` binding, fresh `BakedQuad` construction, callback order, side effects and failure behavior. That would remove rather than duplicate the source traversal and would make #250's 11–15 s scheduling window usable. Such a change would still require the full #222 equivalence gate and an interleaved hosted >=3×3 TTMM + causal-ModelManager A/B before any promotion.
