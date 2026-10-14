# Physical model/renderer split — 2026-09-22

Status: **PHYSICAL PHASE ATTRIBUTION COMPLETE; startup clock validation failed**.

Integration refreshed: `b3f0c5f`. Follows partial run
`resource-initial-20260922a`: ModelBakery 76.360 s, renderer slot 18.658 s,
but process ownership failed and early stdout markers were missing.

This reuses #217/#221 constructor callsites and #190 renderer callsites.
Read #190 and #194 bodies, results and lifecycle audit before implementation:
EMF's shared mutable provider context and AddLayers current-object semantics
still prohibit generic concurrency/deferral. The new premise is physical
attribution inside stock order, not another renderer optimization candidate.

## Seven added scopes

- `cit_active_load`: exact `schm.shsupercm.citresewn.cit.ActiveCITs.load`;
- `bakery_blockstate_registration`: stock `loadAllBlockStates`;
- `bakery_parent_resolution`: stock constructor parent-resolution forEach;
- `bakery_additional_model_event`: stock NeoForge additional-model event;
- `entity_provider_create`: stock entity-provider factory aggregate;
- `player_provider_create`: stock player-provider factory aggregate;
- `entity_add_layers_post`: stock dispatcher ModLoader event call.

Each records coarse wall/process CPU/current-thread CPU/GC snapshots using the
existing variance probe. There are no per-item/model/provider clocks, stack
walks, resource copies, caches or extra executors. All original calls execute
once in place; futures and listener order remain unchanged. The optional CIT
HEAD/RETURN probe leaves failed calls unmatched rather than fabricating success.
Every probe remains disabled without `profileStartupVariance=true`.

The current laptop CIT JAR was fetched solely for signature inspection and
SHA-256 confirmed as `315b46f2a78d5298426fb594558873ac19abbc499f77578f6cd4f98e6809e6a9`.
It matches the previously audited historical fixture class lineage. No CIT
source or JAR is republished or changed.

Constructor residual is **not automatically CIT cost**. Determine whether
the observed CIT interval falls inside the constructor's actual timestamps;
mixed constructor injections can affect nesting. Subtract interval unions,
not sums of possibly overlapping scopes. Unmeasured item-CIT/model work and
ordinary constructor work remain residual until attributed.

## Early evidence and validator contract

Bootstrap coarse stdout records are additionally retained in a bounded
128-row in-memory buffer. `boot_optim.varianceBootstrapOutput` supplies an
absolute, previously absent output path. On normal JVM shutdown the buffer
writes with CREATE_NEW and a dropped-row count. It performs no file writes
during startup. Forced exit or write failure means incomplete evidence, not
a passing run. Console emission remains unchanged.

Parse the early output and latest.log together, sorted by monotonic time.
Do not overwrite one stream with the other; bootstrap and GAME have separate
sequence counters. The existing parser keys scopes by phase and scope ID.
Use explicit `--profile resource_split`: the legacy FancyMenu preload hook
is not required for this fork, but all seven new start/end scopes are. All
other legacy origin/reload requirements remain. Verify zero dropped rows,
balanced scopes, actual process ownership, resource order and listener health
separately before classifying the run as valid.

## Validation and operational contract

- Local Gradle build/tests pass, including bounded-buffer order/overflow and
  refusal to overwrite previous evidence.
- All 33 Python tests pass; resource_split cannot silently accept absent new
  hooks or loosen the legacy profile.
- Both real PowerShell detector functions pass slash/case/native-path/sibling
  regression tests under Windows PowerShell 5.1 locally and on the laptop.
- Runtime activation of all seven scopes on the exact physical pack passed. This
  diagnostic is not a production promotion or a performance comparison.

Run ID `resource-split-20260922b`, remote directory
`C:/BootOptimBench/resource-split-20260922`, transaction state beneath
`state/resource-split-20260922b/state.json`.
Packaged wrapper SHA-256:
`741bad8b3e0654f8d041bb4de10582d786d17078e603299bb5834128ddd30245`.

Prism 11.1 / existing BootOptimBench instance, Oracle Java 21.0.9 / 6144 MiB;
unchanged original JVM options plus variance enable/output-path properties.
The corrected transaction preserves/restores exact wrapper/config bytes.
The user will notify completion; end the turn immediately after dispatch.
No log polling or additional diagnostic launch while Java runs. Automatic
exit remains first display update after TitleScreen opening, not proof of a
navigable TitleScreen. On completion fetch state, early log, latest/startup
logs and options before postflight; do not rerun merely because a hook failed.

## Completed physical evidence

User reported completion. Transaction captured Java PID 4488, creation
`2026-09-22T20:05:18.8789170+02:00`, the expected Oracle executable and all five
required arguments. Its finished state was valid. No Java remained at collection.
Postflight restored the original config SHA-256
`a9744bf7a4c5660f6b58a6fe1fe9309a91ecacc2ac2189bf099dd2a145aa1ecc`
and original bootstrap SHA-256
`379bc509efd43a0d2edf7cfb6bc1e2f99dd1601b3d8e3ab43b00c70f6dea4989`.
Integration refreshed after collection and remained `b3f0c5f`.

Local evidence directory: `C:/BootOptimBench/results/resource-split-20260922b/`.
It contains latest/startup/early logs, combined.log, finished/restored states,
before/after options, variance.json, resource-selection.json and attribution.json.
Early buffer persisted 8 rows, dropped 0. Combined evidence contains 44 probe
records; all seven new scopes paired, no scope warnings, one initial reload,
70/70 listeners with one barrier call and successful turn/completion. Pack
selection/order check passed. No Mixin injection failure was found. EMF emits
model-creation-limit errors, a previously observed pack issue; successful
listeners do not prove visual equivalence or a usable world.

**Do not certify total startup:** resource_split validator rejects
`jvm_start_wall_uptime_inconsistent`. Across the records,
`wall_epoch_ms - jvm_start_epoch_ms - uptime_ms` ranges from -5507 to -5492 ms.
The offset is already present at the first probe (uptime 38.183 s) and is
approximately constant thereafter. JVM-reported start epoch also follows the
captured OS creation timestamp by about 7.713 s. This does not identify the
cause; clock adjustment/startup bookkeeping remain hypotheses. Do not widen
the 5 s validator tolerance to pass this run. Local System.nanoTime intervals
remain useful for phase attribution. Reported uptime 332.736 s at title opening
and 351.840 s at presentation are unvalidated absolute startup endpoints.

### Initial reload and critical path

Origin: this JVM's initial `resource_reload` start marker; endpoint: its
successful completion, both System.nanoTime. Elapsed **142.332619 s**.
ModelManager preparation finished at reload-relative 100.002795 s, only
3.177 ms before the global preparation barrier. It is again the observed gate.
Its listener completed at 106.890236 s; the remaining reload tail was 35.442383 s.
These are boundaries, not sums of overlapping listener durations.

| Scope | Wall seconds | Owner-thread CPU seconds |
|---|---:|---:|
| Block-model loading (async) | 24.315 | unavailable |
| Blockstate loading (async) | 7.256 | unavailable |
| Atlas loading (async) | 38.730 | unavailable |
| ModelBakery constructor | 46.135 | 27.094 |
| CIT ActiveCITs.load inside constructor | 17.450 | 7.031 |
| Constructor blockstate registration | 13.000 | 9.609 |
| Additional-model event | 0.731 | 0.562 |
| Parent resolution | 1.405 | 1.250 |
| Constructor residual | 13.549 | 8.641 |
| Model loading, including baking | 29.086 | 21.359 |
| Baking nested in model loading | 24.049 | 17.938 |

All four measured constructor child intervals are disjoint and contained in
the constructor according to nanoTime. Therefore the 13.549142 s residual is
valid interval subtraction; it is **not** additional measured CIT time. The
largest uncovered gap is between blockstate registration ending (uptime
243.981 s) and the additional-model event starting (257.443 s). Source-level
item model/dependency traversal attribution is the next constructor question.
Atlas completion at uptime 227.895 s precedes constructor completion at
259.593 s; atlas is not the final preparation gate in this run.

Constructor process CPU was 157.953 s (all JVM threads), versus owner CPU
27.094 s. Neither the difference nor wall minus owner CPU proves disk I/O:
concurrent work, scheduling and waits are not separated by these scopes.

### Apply tail and presentation

EntityRenderDispatcher post-turn interval was 17.110904 s. Entity provider
creation accounts for 16.801572 s (98.19%), player creation 0.172141 s and
AddLayers 0.125060 s. Entity creation owner CPU is 6.328125 s and process CPU
15.203125 s. The remaining wall time needs provider/source and wait attribution;
this does not justify generic concurrent providers with EMF's mutable context.

Other substantial post-turn intervals: historical MoreCulling slot 25
8.552663 s, GameRenderer 3.659133 s, slot 26 2.010769 s, Veil ShaderManager
1.976079 s, block-entity renderer 1.005094 s. Slot names rely on the existing
source audit; generated lambda addresses are not durable identities.

Title opening to next presentation spans **19.104 s** of uptime (same-process
interval). It is outside resource_reload and must remain a separate unresolved
tail: screen construction, FancyMenu, texture/upload work and presentation
waiting are possible contributors, not proven causes. Free-memory snapshot
at title opening is about 461 MiB, which is a reason to investigate pressure,
not evidence of hard faults or HDD reads.

### Disposition and remaining coverage

No optimization or end-to-end speedup is claimed. The prior 189.547 s reload
is not a comparable control (ownership failure, detector polling and uncontrolled
cache state); the difference must not become a claimed 47 s improvement.

Prioritize source attribution of the constructor's 13.55 s uncovered region,
CIT's 17.45 s, and entity-provider creation's 16.80 s. Investigate the separate
19.10 s presentation interval before treating reload completion as menu
usability. Reuse existing #190/#194/#217/#221 evidence; no identity-bake cache
or generic provider parallelism is reopened by these numbers.

Generation 2+ reloads, changed-pack invalidation, cancellation/failure recovery,
per-provider waits/IO, and representative first-world behavior remain unmeasured.
No new laptop run was launched merely to repair total-clock certification.
