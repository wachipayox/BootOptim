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

The first iteration synchronously read the exact PNG corpus in physical
archive order on the first eligible open. Snapshot construction requires the expected physical JAR size,
entry count, uncompressed/compressed totals, per-entry size bound, no
duplicate names and SHA-256 corpus digest. The original implementation
invalidated a retained snapshot when the archive fingerprint changed. In
that iteration, the retained encoded-byte ceiling was 20.7 MB
plus maps/objects across reload generations; decoded pixels and GL textures
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

Hosted exact-pack [smoke #35911955908](https://github.com/wachipayox/BootOptim/actions/runs/35911955908)
passed on commit `3505010`: the path-bound snapshot reached `status=ready`
with 5,773 entries/20,700,997 bytes. The stock supplier verified **5,771 of
5,771 runtime hits byte for byte**, with zero fallbacks and a successful
ModelManager generation. The run reached main menu at 92.483 s with valid
resource selection, block atlas 8192×8192×2 and zero BootOptim Mixin errors.
Its verification mode deliberately opened the original supplier as well, so
the 92.483 s is a semantic-health observation, **not** performance evidence.
The next gate was same-branch A/B with verification off.

Hosted exact-pack [A/B #35912720180](https://github.com/wachipayox/BootOptim/actions/runs/35912720180)
passed its six fresh-VM jobs and aggregate on `ab626ea`. Candidate and
control used the same process-start/BootOptim origin and main-menu endpoint;
each selected the expected resource packs exactly once, reached menu with
8192×8192×2 block atlas and zero BootOptim Mixin errors. Each candidate
reported `status=ready` for all 5,773 PNGs and `status=complete` with
5,771 hits, zero fallbacks, verification off and 20,700,997 retained bytes.

| Run | Control menu | Candidate menu | Candidate − control | Control reload→FancyMenu | Candidate reload→FancyMenu | Delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 89.867 s | 76.822 s | −13.045 s | 40.931 s | 35.966 s | −4.965 s |
| 2 | 92.253 s | 87.632 s | −4.621 s | 41.188 s | 40.227 s | −0.961 s |
| 3 | 88.504 s | 94.773 s | +6.269 s | 40.444 s | 42.826 s | +2.382 s |

The difference of group medians is −2.235 s (−2.49%) to menu and −0.704 s
(−1.72%) from reload to FancyMenu finish. These are critical-wall intervals,
not sums of overlapping sprite tasks. The candidate's menu range is 17.951 s
and its reload interval range is 6.860 s; both effects reverse in run 3.
The hosted result therefore establishes compatibility and correct activation,
but **does not establish a reproducible end-to-end win**. Keep the feature
default-off and the PR unmerged. There is no justification yet to spend a
manual laptop run on this candidate; reopen if a revised mechanism or more
controlled paired evidence yields a consistent critical-wall improvement.

Hosted exact-pack [same-VM paired run #35928516325](https://github.com/wachipayox/BootOptim/actions/runs/35928516325)
then completed four control/candidate pairs on `61fcfcd`. Odd pairs ran
control first, even pairs candidate first. This shares each pair's VM and OS
cache, but makes the second process warm; the order alternation exposes that
bias. All eight processes reached menu with the expected pack selection, one
initial reload, block atlas 8192×8192×2 and zero BootOptim Mixin errors.
All four candidates reported 5,771 hits, zero fallbacks, verification off
and 20,700,997 retained bytes. BootOptim's startup report supplied the
process-uptime origin and main-menu endpoint for each process.

| Pair | Order | Candidate − control to menu | Reload→FancyMenu | FancyMenu panorama |
| --- | --- | ---: | ---: | ---: |
| 1 | control→candidate | −1.413 s | −1.808 s | −0.528 s |
| 2 | candidate→control | −3.378 s | −0.854 s | +0.031 s |
| 3 | control→candidate | +0.037 s | −0.527 s | −0.415 s |
| 4 | candidate→control | −1.904 s | −0.258 s | −0.437 s |

The median within-pair delta is −1.659 s to menu and −0.691 s from initial
reload start to FancyMenu finish. All four reload intervals favor the
candidate, in both orders, giving a **small directional hosted signal**.
However, the FancyMenu panorama interval accounts for much of that median:
subtracting its duration from the inclusive reload→FancyMenu interval leaves
within-pair deltas of −1.280, −0.885, −0.112 and +0.179 s (median −0.499 s).
That subtraction is an approximate disjoint-stage comparison, not a direct
atlas measurement. Menu time also includes large unrelated pre-entrypoint
variation; the four entrypoint deltas are +0.389, −1.602, −0.283 and
−2.073 s. Thus neither the −1.659 s menu median nor the −0.691 s reload
median can be attributed confidently to the encoded-sprite mechanism.

Decision remains **default-off, unmerged**. The paired run makes the
compatibility case stronger and suggests a subsecond hosted reload effect,
but it does not meet the critical-path/physical evidence gate for a
20.7 MB retained cache. A future design should measure the atlas preparation
barrier directly or produce a larger, stable critical-wall effect before
another laptop run.

## Revision: asynchronous, generation-scoped preparation

The next default-off revision starts a single daemon preparation worker at
`ModelManager.reload` entry rather than blocking the first eligible PNG open.
It still reads and validates the same exact 5,773-entry corpus and publishes
the immutable snapshot only after all guards pass. Until then, every eligible
open uses its original stock supplier; readiness is never a reload barrier.
The snapshot is cleared at completion of that ModelManager future, so its
20.7 MB encoded-byte map is bounded to the generation instead of retained
across manual reloads. Open streams hold their own byte-array references and
remain valid after the map is cleared. A later reload rebuilds the snapshot.
The one-worker queue cancels stale preparation on a new generation or on
completion, and the archive scan checks interruption between entries.

This changes the premise from synchronous first-use batching to overlapping
preparation plus fail-open stock reads. The tradeoff is that the worker may
compete for HDD bandwidth or CPU with stock reload workers, and early PNGs
may miss the snapshot. New markers include `prepare_ms` and
`pending_fallbacks`; both are essential to determine whether the preparation
finishes before useful sprite opens. The next hosted gates must verify menu,
pack selection, atlas, errors, snapshot readiness, hit/fallback counts and
critical-wall deltas. A hosted win alone will not establish a physical HDD
benefit. This revision is still **not production**.
