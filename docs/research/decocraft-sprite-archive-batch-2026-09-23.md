# Decocraft encoded sprite archive batch — 2026-09-23

Status: **DEFAULT-OFF EXPERIMENT; NOT PRODUCTION**. Branch starts from
`agent/integration-current` at `b3f0c5f`. No laptop use is requested.

The first physical [model-JSON archive-batch result](https://github.com/wachipayox/BootOptim/pull/285)
proved a narrow `openAsReader` task-sum win but did not reduce whole manual
reload wall. Its third generation instead ended atlas preparation immediately
before `bake_models`: `atlas_schedule_load` lasted 283.4 s, versus 183.1 s
for block-model input. Earlier [PR #72](https://github.com/wachipayox/BootOptim/pull/72)
found that almost all 20,054 sprite loads were unique; `Resource.open()`
accounted for ~76.1 s of ~83.9 s inclusive sprite task-sum, while STB PNG
decode was ~3.1 s. Those are overlapping worker sums, **not** a critical-wall
savings ceiling. This experiment tests a different archive-locality premise
for the atlas branch, without changing stitch, PNG decode or GL work.

The pinned Decocraft JAR contains 5,773 `assets/decocraft/textures/*.png`
entries, 20,700,997 uncompressed bytes and 20,537,997 outer-ZIP-compressed
bytes. The largest entry is 76,876 bytes. An offline Java replay using
SecureJarHandler 3.0.8 matched every direct `ZipFile` PNG byte array against
`Files.readAllBytes(SecureJar.from(jar).getRootPath().resolve(entry))`:
5,773/5,773 equal. The archive-order name/byte digest is
`82abe8adc40ee92d8d917c81d7db701db937cc0f26c597d511ea76508c7b4c0d`.
This proves local source-byte parity, not that every entry wins pack selection
or is needed by the atlas; the previous physical runtime loaded 5,771
Decocraft sprites.

The candidate wraps only the lazy `IoSupplier.create(Path)` produced by
`PathPackResources` for an exact Decocraft texture PNG path. It retains the
**actual selected path** rather than reconstructing one from a sprite ID;
custom atlas aliases therefore still receive the bytes of their selected
PNG. The guard requires the path to reside on the loaded Decocraft SecureJar
filesystem under `assets/decocraft/textures/`. Stock resource-pack
precedence, resource listing, metadata and atlas source callbacks still
choose the resource. External overlays, edited packs, other mod roots and
unknown resources keep their stock suppliers. Stock `NativeImage.read`,
animation handling and the authoritative NeoForge
`SpriteContentsConstructor` still consume the stream; custom sprite loaders
are not replaced, though a loader reading the same guarded Decocraft PNG
also sees equivalent encoded bytes.

On first eligible open, one worker reads the exact PNG corpus in physical
archive order. Snapshot construction requires the expected physical JAR size,
entry count, uncompressed/compressed totals, per-entry size bound, no
duplicate names and SHA-256 corpus digest. A changed archive fingerprint
invalidates it at reload start. The retained encoded-byte ceiling is 20.7 MB
plus maps/objects, across reload generations; decoded pixels and GL textures
are **not** cached. This is a measured memory/GC tradeoff on the 6 GiB HDD
laptop, not automatically safe because the prior third bake had severe G1
pressure. Any guard failure retains the original `IoSupplier` and its
`Resource.open()` behavior.

The feature requires
`-Dboot_optim.experimentDecocraftSpriteArchiveBatch=true`.
`-Dboot_optim.experimentDecocraftSpriteArchiveBatchVerify=true` is a
semantic smoke mode: it opens each eligible original path supplier through
stock, compares exact bytes, then gives those stock bytes to the consumer. This must
be **off for timed A/B** because it duplicates the read. The marker
`BOOTOPTIM_DECOCRAFT_SPRITE_BATCH` reports snapshot readiness, per-generation
hits/fallbacks/verification and retained bytes.

Gates: compile/package and ordinary startup CI; hosted exact-pack verification
with the expected ~5,771 Decocraft stock-loader sprite calls, zero byte
mismatches/fallbacks/Mixin errors and unchanged pack selection, atlas and
main menu; then same-branch hosted A/B with verification disabled. Only a
coherent critical-wall signal justifies another physical run. A faster
`Resource.open()` task-sum alone does not promote this experiment. The
earlier full-93-MB read-ahead [PR #76](https://github.com/wachipayox/BootOptim/pull/76)
failed its end-to-end gate, so the smaller encoded corpus is a distinct
premise, not a reason to assume a win.
