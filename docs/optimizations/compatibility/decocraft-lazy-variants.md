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

## PC measurement plan (ready to run, needs the shared PC)

Question to answer: does the deferred path lower **post-GC retained heap** at the menu and in a world, and does it add **first-use hitches** when Decocraft blocks are first seen or placed? The hosted A/B cannot answer either (instantaneous `heap_used_mb`, no in-world step).

Setup: Wachiland Launcher Beta instance, revision pinned (`PACK.md`) and `--no-update` for the whole series; the BootOptim CI JAR of this PR in `mods/` (exactly one); `-Xmx6G` and the pack's JVM flags unchanged; nothing else heavy running. Arms differ only in `-Dboot_optim.decocraftLazyRotations=false` (A, control) / `=true` (B, candidate) in the instance `jvm_flags`. Order: one discarded primer, then **A B B A A B B A** (ABBA ×2). Every arm is a fresh process.

Per run:
1. Menu heap: add `-Dboot_optim.benchmark.exitOnTitle=true -Dboot_optim.benchmark.exitOnTitleDelayMs=120000` so the game stays on the menu. 40 s after `main_menu` run `jcmd <pid> GC.run` twice, then `jcmd <pid> GC.heap_info`; record "used" (post-GC). Save `jcmd <pid> GC.class_histogram | head -30` once per arm (look at `BakedQuad`, `int[]`). Control and candidate must report the same `calls=14108 ... derived_quads=2892138` in `BOOTOPTIM_DECOCRAFT_QUAD_REUSE`; the candidate also prints `lazy_rotation_plans=` lines.
2. World heap and hitches (manual, without `exitOnTitle`): `jcmd <pid> JFR.start name=lazy settings=profile filename=<run>.jfr` before opening the fixed creative test world (same seed/save for every run). At spawn run `GC.run` twice + `GC.heap_info`. Then, in this order and the same path every run: place 20 different Decocraft blocks, each rotated all four ways (80 placements); look at them from 3 sides; walk 30 s through a pre-built area with 200 mixed Decocraft blocks; stop. `jcmd <pid> GC.run` + `GC.heap_info` again; `JFR.dump`.
3. First-use cost: the candidate log prints `BOOTOPTIM_DECOCRAFT_LAZY_ROTATIONS first_use ... realized_variants= total_first_use_ns= max_first_use_ns=` at the first realized variant and every 250 afterwards (cumulative; read the last line). Hitches: in the JFR compare `jdk.GarbageCollection`/`jdk.GCPhasePause` and the render-thread stack samples of `DecocraftRotatedQuadReuse.rotateQuads`; or watch the F3 + Shift frame-time graph while placing and keep a screen recording.

Decision rule (suggested): promote to default only if, over the ABBA pairs, the post-GC menu **and** in-world retained heap of B are lower than A in every pair by more than the A–A spread, `total_first_use_ns` stays small next to the saved bake CPU and no render-thread hitch above one frame budget (≈ 16 ms) is attributable to `rotateQuads`; otherwise keep it as an opt-in switch with this measurement recorded in the research ledger. Do not compare against the hosted runs (different origin, JVM and storage).
