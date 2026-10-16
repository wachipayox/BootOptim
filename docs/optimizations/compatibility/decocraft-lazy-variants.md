# Decocraft deferred quarter-turn variants with compact snapshots

Status: **candidate in PR, default OFF** (`-Dboot_optim.decocraftLazyRotations=true` enables it) until the PC A/B in the PR gives a decision. Migrated from Android Support `824b37d` (`experimental.lazyDecocraftRotations`, default false there as well). Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`. Builds on [decocraft-quarter-turn-reuse](decocraft-quarter-turn-reuse.md).

## Integration with the quarter-turn path (no wrapper, no double application)

Support wrapped `DecocraftRotatedQuadReuse.bake`'s `rotateQuads` call from outside. In BootOptim the call is made directly: `DecocraftRotatedQuadReuse.bake` calls `DecocraftLazyRotations.defer(cached.quads, relative, DecocraftRotatedQuadReuse::rotateQuads)`. Identity, UV-lock, AO, normal padding, ModelData/render-type semantics, the `DerivedBakedModel` wrapper and every safety guard of the quarter-turn path are untouched; the deferred list is only what `DerivedBakedModel.getQuads` returns. If an old Support build with its lazy mixin runs next to this JAR, its contract on `bake`/`rotateQuads` bodies no longer matches and it falls back on its own side, so the variant is never deferred twice.

## Mechanism

- The rotation of a derived orientation is `original(snapshot, matrix)` computed **once, on the first access** of any element, under a lock, by whichever thread reads the quads (no OpenGL, no worker threads). Size is known without evaluating. Same code, same output as the eager path; only when the CPU is spent moves.
- Inputs are frozen at bake time in `FrozenQuadInputs`: 512-quad chunks, vertex words in a palette-encoded immutable chunk (`PalettedIntChunk`), UV words split off so texture-only variants share one geometry chunk (`SplitQuadGeometry`, weak index ≤ 8,192 chunks), flags/tint/sprite packed. Exact bits, no quantization, NaN and signed-zero preserved. A chunk is palette-encoded only with ≥ 12.5 % payload benefit after a 96-byte allowance; at most four primitive workspaces are pooled.
- Only lists of ≤ 16,384 exact `BakedQuad`s with 32 ints are deferred; anything else runs eagerly. The decode cursor is private to one realization and is safe only because `rotateQuads` clones the vertex array and copies metadata before asking for the next input; it is never returned as a model quad.
- Observability: `[WBOMBO-COMPAT] ACTIVE mechanism=decocraft-lazy-rotations` and `BOOTOPTIM_DECOCRAFT_LAZY_ROTATIONS lazy_rotation_plans= planned_quads= materialized_quads= cumulative_snapshot_payload= max_first_use_ns= geometry_chunks= geometry_shared_chunks= palette_chunks= ...` (cumulative counters, not live heap).

## Evidence and limits

- Mobile (Vivo/Java 21, see the Support registry): retained snapshot memory is smaller than eager variants, with mixed whole-process results (one compact reload stayed incomplete through its 300 s deadline; Support48/49 were invalid measurements by a fading overlay, not a rejection). No FPS or process-memory claim; none of it is PC evidence.
- Host (`./gradlew check` → `decocraftLazyRotationsContract`): palette boundaries (1–10 bits, 255/256/257, raw fallback, NaN bits), 10,000 raw-bit pack/unpack round trips, texture-only variants share geometry with independent UVs, once-only concurrent realization, size-drift rejection, and lazy == eager (vertices, tint, direction, sprite, shade, AO) on 6 random lists with source arrays untouched.
- PC: see the PR for the hosted A/B (candidate `true` vs control `false`, same fixture revision). The hosted job reports quarter-turn reuse and wall-clock markers; it does not report retained heap, so a memory claim needs a heap measurement on the PC.
