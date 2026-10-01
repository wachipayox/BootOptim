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

## Decision and residual risks

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
