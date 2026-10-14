# Initial laptop resource diagnostic — 2026-09-22

Status: **INVALID as certified startup benchmark; partial resource attribution**.

Run `resource-initial-20260922a`, source `c1f4fb62`, integration base `b3f0c5f`.
Packaged wrapper SHA-256:
`412da6737fe8bb38f94f7dc8efa5c074c65c8f6a51792bbb4f5c36aea714fffc`.
User reported automatic exit and subsequently rebooted the laptop. Logs survived.
No second game launch was made during analysis.

## Validity and recovery

The transaction had **already been restored** when inspected after the user's
message. It retained `valid=false`, reason `no target java/javaw appeared within
600 s`, `javaPid=0`, no effective command-line digest and no verified JVM args.
The restoration timestamp was `2026-09-22T12:46:18.9655765Z`; this analysis did
not perform that restoration and does not infer who initiated it.

Read-only checks confirmed the live config and wrapper exactly match their
pre-stage hashes, and the diagnostic wrapper is absent:

- config: `A9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC`;
- original wrapper: `379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989`.

The game log identifies Oracle Java 21.0.9, the staged wrapper name, one initial
reload, the expected external pack order, blocks atlas `8192x8192x2`, and
70/70 listener rows with one successful barrier call and completion each.
`check_resource_selection.py` passes. These facts support limited phase
attribution but cannot retroactively supply missing process ownership.

The variance validator correctly fails. Earliest retained probe in latest.log
is vanilla Bootstrap at uptime 95.289 s. Transformation-service/discovery
markers and FancyMenu preload markers are missing. Bootstrap producer writes
to System.out; latest.log is therefore not a complete early-stream capture.
The deployed FancyMenu fork does not produce the expected legacy preload
scope in this run; inactive optional hook must not be mistaken for zero cost.

Do not classify this as a 372.397 s verified TTMM result. That is the recorded
TitleScreen-opening JVM uptime, with first subsequent display at 376.430 s.
The display marker does not certify a usable title screen or absence of modal.
Cache state was uncontrolled; staging/archive inspection occurred before
launch. The reboot happened afterward and cannot make this a cold-start run.

## Detector defect and repair

Both transaction and interactive runner searched raw command-line substrings
using Windows-normalized backslash paths. The retained Prism launch diagnostic
uses forward-slash paths, including its instance `natives` directory; the
Minecraft log also uses forward-slash gameDir. The old predicate returns false
for this form. This is a demonstrated detector defect consistent with the
timeout, although no live process command line was retained to prove exclusive
causality. Increasing the timeout would not repair slash mismatch.

Both detectors now normalize slash style and preserve case-insensitive
matching, with a trailing path boundary to reject similarly named sibling
instances. A focused PowerShell test extracts the real functions and feeds
mock process records for forward/backslashes, mixed case, Prism native path,
sibling instance, null command line and unrelated path. No live Java is launched
by the test. #268's reviewed QSettings quoting and separate launch grace are
also retained in these local tooling changes.

Because Java was never accepted, the observer could have kept its discovery
poll active during loading. Thus this run is not even assumed to meet the
intended no-polling-after-acceptance observer envelope.

## Phase observations (all provisional, same recorded reload)

| Scope | Wall seconds | Interpretation |
| --- | ---: | --- |
| Initial reload | 189.547 | Own start/end monotonic interval |
| All preparations ready | 136.885 | Elapsed from listener census origin |
| ModelManager preparation arrival | 136.880 | Only 4.885 ms before global gate |
| Block models | 20.254 | Async input future |
| Blockstates | 6.430 | Async input future |
| Aggregate atlas | 46.763 | Async future, overlapping model preparation |
| ModelBakery construction | 76.360 | Same-thread lexical scope |
| loadModels | 39.596 | Contains bakeModels |
| bakeModels | 32.649 | Nested inside loadModels |
| ModelManager listener | 144.658 | Includes preparation and ordered wait/apply |

Atlas is ready at uptime 227.810 s, whereas bakery finishes at 277.650 s.
The model branch therefore reaches the bake join about 49.840 s after atlas
readiness. This is not an atlas-first direct gate in this sample. Contention
from atlas CPU/allocation/I/O remains possible; early readiness does not make
its work free.

Bakery's owner-thread CPU is 34.813 s during 76.360 s wall; whole-JVM CPU in
that interval is 261.750 s (~3.43 cores), GC collector-time delta 2.449 s.
These do **not** assign the remainder to disk, locks or GPU. They motivate
source-level constructor attribution and bounded contention investigation,
not an assumed 41.5 s HDD wait.

Post-ModelManager listener completion to allDone is approximately 44.778 s
using the common listener-census timestamps. Largest ordered slots:

| Owner | Slot seconds |
| --- | ---: |
| EntityRenderDispatcher | 18.658 |
| Minecraft lambda index 25 (historically MoreCulling shape listener) | 11.774 |
| Minecraft lambda index 26 (historically MoreCulling translucency listener) | 4.805 |
| GameRenderer shader listener | 4.631 |
| BlockEntityRenderDispatcher | 1.408 |
| Final anonymous listener / FancyMenu-correlated tail | 0.825 |

Runtime lambda ownership should be revalidated on exact deployed versions
before a change, not inferred permanently from historical indices. The first
four slots account for about 39.870 s of the recorded 44.778 s tail; these are
ordered post-turn intervals, not summed inclusive listener lifetimes.

CIT base-model cache remains active: 3960 requests, 3944 hits/open bypasses,
16 misses. EMF's repeated-model warnings occur in this run **and** the saved
pre-run log; their presence alone is not a new BootOptim regression. The
current FancyMenu log reports one slideshow and seven suppliers, so old
multi-panorama timing ceilings are not this run's workload.

## Disposition

Preserve the result; do not rerun merely to get a prettier total. Before another
physical launch, validate corrected process detection and capture early stdout
durably. First-generation data points to two bounded attribution fronts:
ModelBakery constructor (76.360 s, historically CIT/blockstate work) and
EntityRenderDispatcher (18.658 s ordered slot). Revisit #190/#194's semantic
constraints before proposing any renderer deferral or concurrency. This does
not reopen a generic baked-model cache, pool cap or eager parallel bake.

Later reloads remain unmeasured; the initial-only probe does not close G2–G4.
Raw evidence and parsed JSON are kept locally at
`C:/BootOptimBench/results/resource-initial-20260922a/`, outside Git. Logs may
contain account/instance context and are not published with this report.
