# Decocraft model archive batch — 2026-09-23

Status: **DEFAULT-OFF EXPERIMENT; NOT PRODUCTION**. Base is
`agent/integration-current` at `b3f0c5f`. The valid laptop
`resource-deep-20260923b` trace shows Decocraft `Resource.openAsReader`
task-sums of 1.305 → 27.011 → 55.846 s for 7,280 models and
0.896 → 8.351 → 32.097 s for 3,529 blockstates across initial and two
manual reload generations. These overlap other work and are not a savings
ceiling. The last-generation ModelManager futures take 191.037 s for models
and 93.639 s for states, while parse sums stay much smaller.

This changes the premise of the rejected full-JAR read-ahead (#76) and the
two-permit open cap (#283). Instead of touching all 93 MiB of Decocraft or
reducing concurrency when average active model tasks are near one, the
experiment reads only Decocraft's model/state JSON in archive order and
supplies fresh UTF-8 readers to stock parsing. It targets both the first
load and later reloads, with a 3.13 MiB retained-byte ceiling. No model,
blockstate, sprite, baked result, callback, task, barrier, pack selection,
executor or render-thread work is reused or reordered.

The exact fixture Decocraft JAR contains 10,809 eligible JSON entries:
7,280 models, 3,529 blockstates, 3,129,313 uncompressed bytes. An offline
Java replay using SecureJarHandler 3.0.8 compared every direct `ZipFile`
entry to `Files.readAllBytes(SecureJar.from(jar).getRootPath().resolve(entry))`:
10,809/10,809 matched, with the same 3,129,313 bytes. Their archive offsets
form two contiguous clusters separated by non-JSON assets. Warm fast-PC
replay times were 223.750/127.687 ms for two UnionFS passes and
111.331/125.515 ms for two direct ZIP passes; this is not HDD or startup
evidence, and the second pair is effectively tied.

The runtime guard requires the current winning `Resource` to come from
`mod/decocraft`, from `PathPackResources` whose root equals the loaded
Decocraft `IModFile` SecureJar root, and from a Decocraft model or blockstate
JSON path. The snapshot has exact archive-size, entry-count, total-byte,
per-entry size and SHA-256 corpus checks; any mismatch uses stock. A changed
archive file identity/size/mtime at reload start invalidates the snapshot.
Unknown resource packs, edited external ZIPs, overlays, absent Decocraft and
other mod versions fail open. The loaded mod archive is assumed immutable
within a JVM, as its classes and SecureJar filesystem are already mounted.
The feature is off unless
`-Dboot_optim.experimentDecocraftModelArchiveBatch=true` is set.

A separate verification mode
`-Dboot_optim.experimentDecocraftModelArchiveBatchVerify=true` additionally
opens every eligible winning resource through stock `openAsReader`, compares
the decoded text, then gives the parser that stock text. It is a semantic
smoke gate only and must be **off in timed A/B**. The diagnostic marker
reports snapshot readiness, eligible hits, fallbacks, verification count and
retained bytes. Build and startup CI must pass, then hosted exact-pack smoke
must show 10,809 verified calls, zero mismatch/Mixin errors, unchanged
selection/atlas/menu. Only then run same-branch 3×3 hosted A/B without
verification. A small hosted gain remains physically unresolved because this
is a storage-order mechanism. The laptop must not be accessed until the user
explicitly signals it.

Hosted exact-pack [smoke #35882066158](https://github.com/wachipayox/BootOptim/actions/runs/35882066158)
passed on commit `7bacb3f`: build/startup reached menu, resource selection
check was valid with exactly one reload, block atlas remained 8192×8192×2,
and the result reported zero BootOptim Mixin errors. The runtime batch marker
reported `status=ready entries=10809 bytes=3129313`, then
`success=true hits=10809 fallbacks=0 verified=10809`. This proves that the
current exact-pack winning resources matched stock decoded text for the whole
guarded corpus. The smoke's 89.757 s menu time is **not** performance evidence:
verification deliberately reads the original resource as well as the batch.
The next gate is same-branch hosted A/B with verification disabled.

Fresh-VM [A/B #35886235877](https://github.com/wachipayox/BootOptim/actions/runs/35886235877)
finished all six exact-pack runs with valid pack selection, the expected
8192×8192×2 block atlas, zero BootOptim Mixin errors and main-menu endpoints.
Each candidate activated the batch with exactly 10,809 hits, zero fallbacks,
verification off and 3,129,313 retained bytes. Per-iteration results are:

| Iteration | Control menu ms | Candidate menu ms | Candidate-control ms | Control reload→FancyMenu ms | Candidate reload→FancyMenu ms |
|---:|---:|---:|---:|---:|---:|
| 1 | 87,985 | 95,630 | +7,645 | 40,735 | 43,555 |
| 2 | 90,101 | 84,656 | -5,445 | 41,143 | 39,316 |
| 3 | 90,319 | 59,783 | -30,536 | 41,593 | 27,013 |

The median candidate-control delta is -5.445 s to menu and -1.827 s over
reload→FancyMenu, but the signs are mixed and candidate #3 is a large
outlier. Its mod-entrypoint occurs at 20.606 s versus control #3 at
30.364 s, so about 9.758 s of its apparent TTMM advantage precedes this
resource mechanism. Across the three candidate runs, both menu and reload
times span far more than the median delta. This fresh-VM A/B is **inconclusive**
for performance; its green status establishes runtime health, not a win. A
same-VM alternating-order paired diagnostic is the next variance gate. Its
warm second process is not a cold-start result and cannot replace the
physical HDD gate.

The alternating-order [paired run #35899353203](https://github.com/wachipayox/BootOptim/actions/runs/35899353203)
completed on the same unchanged candidate head. Every process reached menu
with valid pack selection, block atlas 8192×8192×2 and zero BootOptim Mixin
errors; every candidate again recorded 10,809 batch hits and zero fallbacks.
Within-VM candidate-minus-control deltas were:

| Pair | Order | Menu ms | Post-entrypoint ms | Reload→FancyMenu ms |
|---:|---|---:|---:|---:|
| 1 | control→candidate | -488 | -940 | -583 |
| 2 | candidate→control | -2,226 | -1,265 | -1,138 |
| 3 | control→candidate | -667 | -733 | -328 |

All three reload intervals move in the expected direction, including the
pair where the candidate ran first and the control inherited the warm VM/page
cache. Paired median reload movement is -583 ms, post-entrypoint -940 ms and
menu -667 ms. This is a **small coherent hosted signal**, not evidence of a
5.445 s cold-start gain from the mixed fresh-VM median. The first physical
laptop generation's Decocraft model/state open task sums were only 1.305/
0.896 s; a large first-load speedup is not established. The much larger
second/third physical reload open sums make manual reload the main hardware
hypothesis, but task sums still cannot be converted into savings.

Decision: keep the experiment default-off and unpromoted. A controlled
physical laptop candidate run with the exact original resource-pack selection
and two manual pack reloads is justified only when the user makes the laptop
available. Compare against the valid earlier diagnostic with matching origin,
endpoint and phase markers, and request a new physical control if the delta
is within normal run variation. Record both reload critical-path wall and
late-bake GC; a faster archive input with worse GC or no menu/reload wall
gain is a reject. No laptop use is authorized yet.

This branch is not mergeable as production solely because it builds or
passes smoke. A material ModelManager barrier or time-to-menu win, no
late-bake GC regression and final physical validation are required.

## First physical combined diagnostic — 2026-09-23

The user performed the initial menu and two manual pack reloads with the
combined diagnostic/candidate JAR from [PR #286](https://github.com/wachipayox/BootOptim/pull/286),
removing only `Ashen_Custom_Font (1).zip` and then restoring its original
priority. The transaction recorded a fresh Java process (Oracle 21.0.9,
6 GiB G1), the expected candidate SHA, and a clean exit. It copied the logs
and 42.6 MB JFR to `C:/BootOptimBench/results/resource-batch-physical-20260923/`
and restored the original Prism configuration and BootOptim wrapper. All
three reloads completed 70/70, 71/71 and 71/71 listeners; the effective
external-pack lists were initial / font removed / initial again. All three
batch generations reported 10,809 hits, zero fallbacks, verification off,
and the exact 3,129,313-byte corpus. Model/state source fingerprints stayed
identical to the earlier physical control across all generations.

**Overall startup validity is rejected.** The first transformation-service
marker occurs at JVM uptime 109.134 s, above the 60 s stale-prefix limit,
and `(wall_epoch_ms - jvm_start_epoch_ms)` disagrees with recorded uptime by
about 9.015 s. The previous valid control's first marker was at 41.617 s.
The candidate's time-to-menu must not be compared to the control or cited
as a win. The isolated reload intervals and phase scopes use monotonic clocks
and are still useful diagnostic observations, but this is not a valid
end-to-end A/B. The physical run also had a longer pre-marker/JFR-start
interval than the control, so uncontrolled machine state is plausible. In
the recorded pre-marker window, JFR shows ForgeWrapper installer activity
and rolling-log compression. These belong to the launcher/boot prefix, not
the resource candidate; they do not explain the entire origin discrepancy.

| Phase, seconds | Control g1/g2/g3 | Candidate g1/g2/g3 |
|---|---:|---:|
| `resource_reload` wall | 164.950 / 281.209 / 428.399 | 163.047 / 332.537 / 518.459 |
| `block_models` future wall | 26.427 / 84.138 / 191.037 | 23.301 / 61.786 / 183.143 |
| `block_states` future wall | 8.441 / 21.072 / 93.639 | 8.045 / 16.336 / 85.197 |
| `atlas_schedule_load` wall | 39.6 / 110.9 / 218.9 | 46.8 / 108.9 / 283.4 |
| `bake_models` wall | 32.502 / 42.203 / 74.216 | 25.842 / 44.494 / 65.464 |

Within the exact guarded Decocraft pack, model `openAsReader` task-sum fell
from 1.305 / 27.011 / 55.846 s to 0.084 / 0.084 / 0.295 s. State open
task-sum went from 0.896 / 8.351 / 32.097 s to 1.837 / 0.052 / 0.053 s;
the candidate first generation includes the one-time corpus load. These
task-sums overlap and **must not** be subtracted from reload wall. They prove
the intended input-open mechanism works, but the two manual reloads were
51.328 and 90.060 s slower in this run. Model futures were shorter by
22.352 and 7.894 s, while the third atlas schedule was about 64.5 s longer.
At third generation the atlas ends at the bake start and is a likely gate;
this is an observation, not proof that the batch caused the atlas delay.
The third reload accumulated almost the same JVM process CPU as control
(884.5 vs 892.5 s) over substantially more wall time (518.5 vs 428.4 s),
so average process CPU utilization fell from about 2.08 to 1.71 cores.
OS available-memory snapshots fell from 4,655 to 879 MiB during that
candidate reload, while used JVM heap changed only from 5,183 to 5,292 MiB
and committed heap stayed at 6,144 MiB. Those snapshots cannot identify the
owner of non-JVM memory or page-cache changes, but they further constrain any
claim that the Decocraft JSON mechanism caused the whole reload delta.

The candidate JFR has about 43.1 s and 78.3 s of clipped `GCPhasePause`
event-duration sums inside manual reload 2/3, versus about 21.3 s and
89.6 s in the control. The candidate second bake has about 22.4 s of pause
overlap (control about 5.8 s); the third has about 48.3 s (control about
60.2 s). Thus GC pressure moved between generations; it did not vanish.
Candidate slow `FileRead` events remain dominated by texture input and many
other archives. Their duration sums include overlap and are not disk-busy
time or additive savings.

Decision: **do not promote or claim a physical end-to-end win**. The batch
is a successful narrow mechanism with no demonstrated reduction in reload
completion time under this physical run. A matched repeat with a valid early
origin and comparable machine conditions would be needed to distinguish a
small positive effect from the large storage/GC/atlas variability. The
longer-term architectural target is broader resource-input locality and the
atlas/late-bake critical path, not multiplying pack-specific caches on the
basis of overlapping `openAsReader` task sums.
