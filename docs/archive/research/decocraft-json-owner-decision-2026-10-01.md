# Decocraft JSON owner decision gate — 2026-10-01

Status: **ACTIVE DIAGNOSTIC, NOT PRODUCTION**. Started from refreshed
integration `411e17cbfe4739cdcab14bf14321c7901cb1c775`. User lifted the
low-credit restriction and requested the next strong front. No laptop run
has been dispatched, and no performance win is claimed.

## Existing evidence and changed measurement premise

This continues [PR285](https://github.com/wachipayox/BootOptim/pull/285),
not a rediscovery of archive batching. That exact-source experiment verified
all 10,809 winning JSON readers and had a small coherent hosted paired
reload effect (-583/-1,138/-328 ms). Physical Decocraft model-open task-wall
sums fell from 27.011/55.846 s to 0.084/0.295 s, while **whole reload wall
regressed** from 281.209/428.399 s to 332.537/518.459 s. State open, atlas,
GC and memory also varied. Its startup origin was invalid; these are reload
phase observations only, not a TTMM gain. See PR284/286 and PR285's research
record. Failed generic concurrency caps (PR283), full-JAR read-ahead and
sprite batching (PR287/288) are not reopened.

The current gate applies the user's segment-first two-control/two-candidate
policy, previously missing here. It measures the **actual complete stock
read/parse/close task**, including the candidate's guard and lazy corpus
fill, by current-thread CPU and monotonic task-wall. It separately reports
the nested `openAsReader` and corpus fill costs. NEVER add nested values
to the full task, nor sum overlapping tasks as critical-path wall.

## Reused candidate and safety boundary

PR285's reader mechanism is copied intact except diagnostic mode selection,
counter lifetimes and clocks: fresh UTF-8 readers over the exact 3,129,313
payload bytes, pinned 10,809 entries and digest, loaded Decocraft SecureJar
root and current winning `mod/decocraft` resource, archive fingerprint
invalidation, stock fallback on unknown data. It changes neither parser,
model/geometry results, pack selection, executor, barrier nor GL ownership.
Actual retained heap includes byte arrays, map and archive metadata; payload
bytes are **not** a total-heap cap. The game/task/source guards and stock
decoded-text comparison must pass again on current integration.

This pinned-pack content equivalence is not a proof for unknown supplier
callbacks or different resource wrappers. Do not broaden the exact domain
or promote on green CI alone. The prior root accessor never nulls a Resource
source (PR71's broken diagnostic is not reused).

## Protocol and origin

Semantic smoke first: `experimentDecocraftModelArchiveBatch=true`,
`experimentDecocraftModelArchiveBatchVerify=true`,
`profileDecocraftJsonOwner=true`; no automatic trials. Require exact corpus,
10,809 hits/verified, zero fallback/errors, full owner counts, current pack
order and atlas, main menu. Verification deliberately reads twice and is
**not timed performance evidence**.

Then `benchmark.decocraftJsonTrials=true`, same enabled batch and owner clocks,
verification off. One JVM performs an unmeasured initial stock reload, then
C primer/C observation, B primer/B observation, B primer/B observation,
C primer/C observation, with two seconds of stable title/overlay-free idle
between. It never enters a world and stops automatically. Controls release
candidate storage at ModelManager reload entry; primers condition state
separately. Only the first B primer fills the corpus; fill cost is recorded,
not hidden, and needs a separate first-use/cold-start judgment.

Owner endpoint is return/finally of actual `lambda$loadBlockModels$8` and
`lambda$loadBlockStates$12`. Only current exact Decocraft resources count;
mixed source state stacks invalidate the expected workload rather than
attributing another pack's work to Decocraft. Full model/state prerequisite
futures report separate wall endpoints. Whole reload origin is the monotonic
instant immediately before stock `reloadResourcePacks()`, endpoint its
returned future completion. Initial menu JVM uptime is separately labeled;
launcher/Gradle/fixture setup is not part of a reload contrast.

`check_decocraft_json_trial.py` requires nine owner generations, eighteen
prerequisites, all eight successful primer/observation endpoints, identical
10,809 task/open counts, C/B/B/C order, correct hit/storage lifetimes and
owner/trial agreement. Missing CPU support, failed reload, unknown workload
or duplicate endpoints reject evidence. Pack selection/origin/stale-JVM
validation remains the existing exact-pack harness's separate gate.

## Current semantic gate

Hosted exact-pack36916914835 on `ed020506` passed, as did both builds and
the no-Decocraft startup gate. All 10,809 eligible calls matched stock text:
hits=verified=tasks=opens=10,809, fallback=0, one corpus fill, CPU available,
two successful prerequisites, original 8192x8192x2 atlas and zero BootOptim
Mixin errors. The offline semantic validator was rerun against downloaded
artifacts and passed. This deliberately double-read run is not performance
evidence (its 89,366 ms menu uptime is not an A/B observation).

Before timed dispatch, collection is hardened to consume the complete console
after exit for repeated reloads, avoiding `latest.log` rollover. This changes
only harness/documentation, not any verified runtime blob. The strict result
JSON is included in hosted artifacts. Runtime mode/property gate remains
unchanged; the next run enables trials and disables verification.

## Decision and residual risks

## Timed hosted result and physical dispatch

Exact-pack36918270181 on `b4024d31` passed all nine owner generations and
eighteen prerequisites, eight primer/observation endpoints, unchanged packs,
atlas and zero BootOptim Mixin errors. The downloaded console was independently
rechecked with the strict parser. Raw artifacts:
`C:/BootOptimBench/analysis-reload-20261001/json-timed-36918270181`.

| Observation | Full task CPU ms | Full task-wall sum ms | Nested open CPU ms | Reload wall ms | GC ms | End used heap bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| C1 | 253.104062 | 1158.517420 | 168.310491 | 29855.905418 | 2114 | 5775597408 |
| B1 | 134.619029 | 807.313120 | 50.139478 | 28956.819751 | 1967 | 5738249880 |
| B2 | 127.642759 | 499.814509 | 50.232764 | 28660.870444 | 1947 | 5761258504 |
| C2 | 254.816562 | 902.031420 | 171.543142 | 28672.152012 | 2099 | 5536147976 |

Full owner CPU saves 118.485033/127.173803 ms (46.81%/49.91%); task-wall
saves 351.204300/402.216911 ms. Nested open CPU accounts for most of this
reduction and is NOT added again. Complete reload wall moves -899.085667/
-11.281568 ms, GC -147/-152 ms, end heap -37347528/+225110528 bytes. This
does not establish a stable whole-reload saving or a retained-array leak:
live end-heap snapshots include much more than the 3,129,313 payload bytes.

Model prerequisite wall C1/B1/B2/C2 is 3948.846258/3645.967671/3383.639993/
3606.377565 ms; states is 792.754810/920.149708/896.153779/813.698897 ms.
The state future regresses while complete owner CPU improves; future time
includes other packs, concurrency and unrelated tasks and is not an exclusive
Decocraft budget. No sum of these futures is interpreted as critical path.
The first B primer pays one corpus fill: 98.385738 ms CPU/240.591154 ms wall;
it is explicitly unmeasured conditioning, not a proved initial-startup win.

Physical gate uses the frozen **push-build** artifact from36918264903,
artifact11190641760, source `b4024d3183d81af35d2acb18e696f73a9c259104`, SHA-256
`E3FCBEE0D3A7AD770A363A328BD42197E124C827316CEE17B51D5A2A560C1BA1`.
Bundle is `C:/BootOptimBench/artifacts/decocraft-json-20261001-ready/bundle`.
The laptop was identity-checked and free of Java/running BootOptim tasks and
campaign lock before dispatch. Campaign `decocraft-json-20261001` is started
through `BootOptimSweep-decocraft-json-20261001`, menu-only, Oracle21.0.9
with the user's unchanged runtime/GC tuning and preserved production switches.
No worlds or laptop F3+T. The controller performs transactional ownership,
four-hour owned-JVM timeout, complete rolled-log collection after exit and
automatic original JAR/config restoration. New controller offline tests pass
both success and incomplete-run recovery/config/lock cases; the reused prior
controller fixes retain atomic state writes, normalized owned-process paths,
and explicit recovery switch binding.

Physical result is pending; do not edit its frozen bundle/JAR/scripts/config
or read timed logs while the owned JVM lives. Only metadata/status is read
until completion. Windows per-thread CPU clock quantization must be declared
when interpreting the full-task sums. Do not promote or retire until those
strict owner/workload/indirect gates and restoration hashes are reviewed.

Primary decision compares full owner CPU/task-wall in both contrasts, not
open counts alone. Then inspect model/state prerequisite wall, corpus build,
retained storage, GC and whole reload for attributable regressions. Hosted
llvmpipe is not HDD evidence: storage-sensitive conclusions require a valid
physical gate after semantic/hosted rejection checks. Laptop remains menu-only;
world/F3+T validation belongs to the fast PC.

Integrate a clean, telemetry-free mechanism only if gates establish a real
owner saving without material attributable costs; otherwise retire PR285's
active candidate and record the binary decision on integration. Do not
leave an unspecified default-off option or repeatedly run the old confounded
experiment. Existing Sodium, quarter-turn, direct generated-item and indexed
variant production changes remain untouched.
# Physical final evidence — 2026-10-02

Campaign decocraft-json-20261001 finished successfully at2026-10-01T22:01:17Z
and was collected only after owned process exit. Strict JSON-owner parser and
ordered-resource check both accept nine generations/four measured observations,
stock initial and four separate same-mode primers. Frozen b4024d31 remains
the timed source, not later documentation/controller commits. Physical laptop,
Oracle21.0.9, configured6G/user tuning, menu-only. Origin is stock
reloadResourcePacks request to returned-future completion; task CPU and summed
task wall are separate from that critical-path interval. Raw evidence:
`C:/BootOptimBench/artifacts/decocraft-json-20261001-ready/results`.

| Observation | Full read/parse/close task CPU ms | Summed task wall ms | Whole reload ms | GC ms |
| --- | ---: | ---: | ---: | ---: |
| C1 | 1375.000 | 53704.6777 | 539435.5509 | 184228 |
| B1 | 421.875 | 613.8284 | 550999.0167 | 186727 |
| B2 | 421.875 | 582.5781 | 391311.3377 | 61016 |
| C2 | 1500.000 | 57981.8927 | 391118.4186 | 54405 |

Every observation has10809tasks/opens. Both candidates hit10809 batch inputs,
retain3129313encoded payload bytes (not total heap cost), and perform no fill
inside the measured observation. Primers/cold fill are not counted as savings.
Actual task CPU saves953.125/1078.125ms (~69.3/71.9%); Windows resolution15.625ms.
Nested open CPU saves906.25/968.75ms and MUST NOT be added again. Task-wall sum
saves53.091/57.399seconds, but concurrent/waiting task sums are NOT seconds
removed from the game critical path. Whole reload instead is+11.563466/+0.192919s;
GC+2499/+6611ms, endheap-702356968/+140985904bytes. These do not establish total
startup/F3+T improvement or causal attribution of all global drift to this cache.

Disposition: **retain the guarded exact-input batching mechanism for a clean
production promotion on demonstrated CPU removal**, with the explicit limitation
that current tests do not prove faster end-to-end loading. Hosted full-task CPU
already saved118.485/127.174ms, coherently confirmed here on physical hardware.
Unlike retired capacity retention, this evidence attacks current repeated input
reads with a small bounded payload and does not justify a global table policy.
This is a deliberate CPU-retention product decision, NOT a claim that existing
diagnostic code is integrated or that all performance gates can be skipped.

No production merge yet: strip diagnostic instrumentation/controller/replay code,
preserve exact archive/source-root guards, stock parser/callbacks and fail-open,
then package/absent-mod startup/hosted semantic gate on the clean artifact.
No new physical run is requested here and the user explicitly asked not to
start another optimization front. Original JAR/config transaction reports
restored; independent current remote hashes on collection exactly match original
cfgA9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC and
JAR379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989.
All earlier "physical pending" paragraphs below are historical, superseded
by this final completed gate and clean-promotion disposition.

