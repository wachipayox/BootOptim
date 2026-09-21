# Pandora pre-Java physical attribution — 2026-09-14

## Boundary and validity

This is launcher time, not Minecraft time-to-main-menu. The measured boundary
is the opt-in Pandora probe's `launcher_pre_java.begin` to
`launcher_pre_java.end` on the old HDD laptop. It excludes Java-to-menu and
must not be combined with BootOptim startup timings.

The accepted JSONL is stored locally by the operator as
`C:\BootOptim-v0-test\pandora-prejava-probe-20260914-pr7-root-valid.jsonl`,
SHA-256 `5179f29d499938b134528f5be8e9fdbbf95ffaff8333fa19b65881a68eb59738`.
It was produced by Pandora PR #7 head
`55f05135396e9682a5d0f76a24e62f21850fbc03`, using executable SHA-256
`888790070526a4add4c3370ba169616674ecc7c0198fe0dd649ca3f45517e932` and
interposer SHA-256
`ce058a4994c6d1ea1c3f438192fc255c2964a38be910e1661e562e97d45b2341`.

The trace has exactly one root begin/end, monotonic timestamps, all observed
children inside the root, and ambiguous classpath/native/wrapper phases marked
unobserved. It is therefore valid for this boundary.

## Result

| Phase | Wall time |
| --- | ---: |
| Start to direct Java (`launcher_pre_java`) | 317.864 s |
| prelaunch | 43.543 s |
| version/loader resolution | 13.104 s |
| assets verify/download | 232.559 s |
| libraries/classpath inputs | 71.683 s |
| AppCDS preflight | 28.543 s |
| Java spawn | 0.027 s |

Assets and libraries overlap (libraries starts just after assets), so their
durations must not be added. The trace observed a loader SHA1 network request,
but does not attribute bytes or a network duration. This run is not comparable
as a performance delta with the earlier 742.966 s capture: the earlier run had
different download/repair state and was from a previous probe contract.

## Decision

The primary launcher front remains asset verification/download. PR #8's shared
I/O budget and the currently default-off, fail-closed USN design are candidates
only; neither has a physical performance claim yet. Any A/B must use this same
root endpoint, identical asset state, no repair/download, and no sum of
overlapping phases.

## Warm asset attribution capture — 2026-09-15

Pandora PR #13 (`8daf581ec346f07bb5edf1f575bb465313c182e6`) was deployed as a
diagnostic-only build. The root JSONL and its aggregate asset sidecar were
copied locally as `C:\BootOptim-v0-test\pandora-asset-attribution-root-20260915.jsonl`
(`935758a6908ecc90237832fafabfe213c5861bd4e057476687d36cf7b7ee4d0e`) and
`C:\BootOptim-v0-test\pandora-asset-attribution-20260915.json`
(`27be21be5f50e7e55299ae67f4fba64fcc5f44d98b492694e4d96c318570caad`).

The trace has one valid root begin/end and its sidecar reports `outcome=ok`,
`launch_probe_active=true`, no misses, downloads, network, or hash I/O errors.
It therefore describes a warm, clean asset state. It deliberately autocloses
only after direct Java spawn, so it is valid for the launcher boundary but is
not a menu/gameplay validation.

| Field | Observed value |
| --- | ---: |
| Start to direct Java | 381.342 s |
| assets verify/download wall | 90.292 s |
| planned/hashed objects | 3,911 / 3,911 |
| hash hits/misses | 3,911 / 0 |
| bytes read by SHA-1 | 824,917,408 B |
| downloads/network/errors | 0 / false / 0 |
| hash concurrency | 32 |
| AppCDS preflight | 245.192 s |

The 381.342 s total is **not** a baseline for an asset A/B: AppCDS preflight
was 245.192 s in this run rather than 28.543 s in the earlier root-valid
capture. The decisive result here is narrower: even with every object warm and
valid, stock verification reads and hashes 824.9 MB and occupies 90.292 s of
wall time on the HDD laptop. This establishes a material physical ceiling for
the fail-closed, default-off USN candidate once it is composed on top of the
same root-probe contract. A future A/B must separately stabilize or disable
the AppCDS preflight state and preserve warm/no-network sidecar outcomes.

## AppCDS preflight attribution — 2026-09-15

PR #19 (`15a6eef952f2463295209b1a9fb56bf722e8cace`) ran with the same valid
outer root probe, a fresh diagnostic sidecar, and `BOOTOPTIM_APPCDS_MODE=plan`.
The copied evidence is `C:\BootOptim-v0-test\pandora-pr19-appcds-root-20260915.jsonl`
(`aeba7acaa32a12635172d9b27d34056a5e657abdfb7dd3c55cbcd2197595ea44`) and
`C:\BootOptim-v0-test\pandora-pr19-appcds-sidecar-20260915.json`
(`abeb4deb138e89b9627f5592f84c112bdd0443441cf3e15131df340b2b279afe`).

The sidecar is valid: `mode=plan`, `decision=STOCK`, and no classification,
promotion, or training buckets. This excludes READY archive verification and
training promotion by construction. The lock is a single 50.265 ms attempt and
publication is 678.520 ms; neither explains the wall time.

| Preflight bucket | Wall time | Inventory |
| --- | ---: | --- |
| outer `appcds_preflight` | 311.197 s | — |
| helper diagnostic total | 308.650 s | — |
| build launch plan | 307.918 s | — |
| pack inputs | 204.692 s | 896 files / 662,301,137 B |
| mods | 75.921 s | 157 files / 437,241,036 B |
| classpath | 17.108 s | 107 files / 74,071,700 B |
| components | 9.395 s | 2 files / 55,683,584 B |
| persist plan | 0.679 s | — |

This is a physical HDD attribution, not a claim that any hash can be skipped.
It shows that normal `plan` reconstructs a strong AppCDS identity by repeatedly
reading roughly 1.23 GiB of launch inputs. The next candidate must preserve the
exact plan bytes and fail to the current full hashes whenever identity, volume,
journal/FileId/USN, handle, configuration, mode, or cache publication is
uncertain. It must be evaluated separately from the asset-object USN cache:
their input sets and repair contracts differ.

## Asset USN physical activation check — 2026-09-15

The composed PR #22 artifact was deployed to the old HDD laptop and exercised
twice through the ordinary Pandora GUI authority (not `--run-instance`).  Both
runs used the same warm asset state, `BOOTOPTIM_ASSET_USN_CACHE=1`, a fresh
root probe/asset-attribution sidecar, and an autoclose watcher that also killed
the launcher afterwards so a later invocation could not be forwarded to a
stale process.

The seed run reached Java cleanly with no asset downloads or network.  The
following reuse run was also clean, but its sidecar still reported 3,911 hash
attempts/hits and 824,921,227 B read.  It therefore performed stock full SHA-1
verification rather than reusing an asset index.  No USN/journal-named cache
file appeared below the instance `.minecraft/.bootoptim` cache root.

This is an **activation failure / no-go for the deployed artifact**, not a
measurement regression: the intended candidate did not enter a reuse path, so
there is no asset-time delta to claim.  Do not repeat the same A/B.  First
instrument or repair the candidate so its sidecar explicitly records its
eligibility decision, helper invocation/result, cache publication and cache
reuse reason; only then repeat one seed + one reuse GUI pass.  The fallback
behavior remained correct (all hashes valid, no download/network), which is the
required fail-open property.

## Asset USN physical seed/reuse — 2026-09-15

Pandora draft PR #24 (`28b997a8adbd3ae71bc1fa73c15b1c5357bec046`)
corrected the activation diagnostic and was exercised on the HDD laptop by two
separate ordinary GUI Start launches. The packaged Windows artifact was checked
against its `SHA256SUMS`; evidence was copied to
`C:\BootOptim-v0-test\pr24-usn-physical-20260915`. Both runs used a fresh
probe, clean autoclose (Java plus launcher), `BOOTOPTIM_ASSET_USN_CACHE=1`,
the packaged helper path and its pinned SHA-256. The user accepted the UAC
prompt on both launches.

The seed sidecar recorded `capability_authenticated`, active session, absent
manifest before and a newly created global launcher-assets manifest at
`%APPDATA%\\PandoraLauncher\\assets\\objects\\.bootoptim-usn-assets-v1.json`.
It performed stock SHA-1 for all 3,911 objects. The subsequent reuse sidecar
recorded the same authenticated active session, manifest present before/after,
3,911 verified reuse files and zero stock SHA-1 files. The attribution sidecar
also records zero hash attempts, zero asset bytes read, zero downloads and no
network/errors in reuse.

| Comparable asset phase only | Seed | Reuse | Difference |
| --- | ---: | ---: | ---: |
| `assets_verify_download` wall | 103.990 s | 34.805 s | -69.185 s |
| SHA-1 attempts | 3,911 | 0 | -3,911 |
| Bytes read for SHA-1 | 824,921,227 B | 0 B | -824,921,227 B |

This is a real, fail-closed physical asset-verification win, not yet an
end-to-menu claim: the measured endpoint is the launcher asset phase and the
root still has independently variable AppCDS/prelaunch work. The UAC acceptance
was part of both measurements, so its human delay is a residual source of
noise; the zero-hash counter is the decisive proof of reuse. Requiring UAC on
every normal start is not acceptable for a packaged player experience. Before
promotion, redesign helper elevation/capability installation so it is obtained
once during explicit launcher setup or otherwise falls back stock without a
per-launch prompt, while retaining the exact identity and invalidation gates.

## Persistent installer SHA-1 cache — no-go, 2026-09-15

Pandora draft PR #25 (`0c2e2a051bf207c49a8b0f1bf04c449fe257b829`) audited
the recurring NeoForge/Forge installer `.sha1` request. It is on the loader
resolution path, but no persistent no-network cache is safe under the current
contract: the same configured version and canonical URL can denote either the
previous installer digest or a subsequently republished installer digest. A
local JAR/hash proves only local consistency, not remote freshness; Maven
metadata selects versions but is not an authoritative artifact-digest manifest.

Conditional HTTP validation may preserve semantics, but still pays the
connection/RTT that matters on the laptop while transferring only 40 bytes.
The candidate is therefore rejected rather than shipping a stale-update risk.
Reopen only if a verified remote authority binds the concrete loader version to
the installer digest and detects replacement under the same coordinate.
## Direct unprivileged asset USN cache — physical seed/reuse, 2026-09-15

Pandora draft PR #27 (`48a07528dff5b8249c8284545e4cc26efb23b49a`, Windows
artifact `10418032129`) replaces the PR #24 per-launch elevated helper with a
direct non-elevated NTFS journal/file-handle capability. Its exact packaged
artifact was deployed to the old HDD laptop and run twice through normal GUI
Start, after moving the former helper-backed manifest to a recoverable result
backup. Neither run had a helper environment variable or UAC prompt.

The seed probe recorded `direct_unprivileged_ntfs`,
`elevation_requested=false`, an absent manifest followed by publication, and
3,911 stock SHA-1 files. The reuse probe recorded the same direct active mode,
manifest present before/after, 3,911 verified reuse files, and zero stock
SHA-1 files. Its asset attribution has zero hash attempts/bytes, downloads,
network or errors. The phase markers give 163.319 s for the seed and 2.313 s
for reuse, a -161.006 s difference for this physical pair.

This validates the no-UAC mechanism and its asset-phase benefit, but is not an
end-to-main-menu claim and the unusually slow seed must not be generalized as
a stable median. Promotion remains contingent on fail-open physical tests for
asset mutation, delete/recreate, manifest corruption and journal discontinuity,
plus ordinary code review.
Three deliberate physical fail-open checks were then performed through the
same ordinary GUI path. A one-byte write to one 8,565-byte asset yielded 3,910
verified reuses, one stock SHA-1, and exactly one 8,565-byte repair; the final
SHA-1 matched the expected object name. Deleting that same object produced
the same 3,910/one split, one expected I/O error and one correctly restored
download. Finally, invalid JSON replaced only the disposable manifest after a
byte-for-byte backup. The next start rejected it, performed 0 reuses and all
3,911 stock SHA-1 checks, made no download/network request, and published a
valid new manifest.

These cover normal reuse, modified content, FileId-changing deletion and
corrupt metadata without UAC. A real NTFS journal discontinuity is deliberately
not manufactured: resetting or damaging an OS-wide journal is an invasive,
non-representative administrator operation. The remaining promotion gate is
source review and ordinary compatibility validation, not another performance
claim.

## Real prelaunch mods attribution — physical HDD, 2026-09-16

The exact release diagnostic artifact from Pandora draft PR #30 was run once
through normal GUI Start with fresh `BOOTOPTIM_LAUNCH_PROBE` and
`BOOTOPTIM_PRELAUNCH_ATTRIBUTION` sidecars, no inherited AppCDS/interposer/USN
variables, and automatic close five seconds after Java-window detection. The
sidecar correlates exactly to `launcher_pre_java.begin`; it observes launcher
pre-Java only, not Java-to-menu/TTMM.

`prelaunch` was 23.731 s wall / 13.594 s process CPU. Its exclusive observed
children were: `load_content` 14.702 s wall / 7.688 s CPU for 157 mods;
`apply_copies_to_mods_dir` 3.077 s / 1.672 s CPU for 157 copies and
437,241,036 B; and one `extras_copy` of 281,282,882 B at 4.649 s / 2.781 s
CPU. Other extras were below 0.4 s total. Mod scanning, rotation and directory
creation were negligible. These spans are siblings/exclusive and must not be
summed with any inclusive parent. The result turns `load_content`, the large
extra materialization, and immutable-mod layout materialization into separate
optimization fronts; it does not itself justify cache/reuse semantics or a
TTMM claim.

## AppCDS training and READY consumption campaign — 2026-09-21

**Status: validated mechanism, not promotable with the current identity path.**

The old-HDD laptop ran the reproducible v0 artifact with a fresh launch-probe
JSONL per run, `BOOTOPTIM_APPCDS_MODE=auto`, and the packaged interposer. The
previous `ready.*` files were moved to a recoverable campaign backup; no pack
files, Java installation or global environment variables were changed. This is
not a time-to-menu measurement: the probe ends at direct Java spawn and the
normal user close time after menu is not controlled.

| Run | AppCDS state/result | click → Java | AppCDS preflight | Java lifetime until normal user close |
| --- | --- | ---: | ---: | ---: |
| first rebuilt plan | `FIRST_OR_MISMATCH`, stock | 711.241 s | 168.189 s | 747 s |
| matching-plan training | `TRAIN`, clean archive | 823.000 s | 287.614 s | 1,093 s |
| READY consumption | `READY`, `activation=enabled` | 896.680 s | 377.100 s | 501 s |

The training run produced `training.jsa` (285,802,496 B) and
`training.complete` after clean exit. The next run promoted it to `ready.jsa`
with the same size and logged `BOOTOPTIM_INTERPOSER status=ready
activation=enabled`; the archive was therefore genuinely consumed, not merely
present on disk.

The Java lifetime is suggestive only: READY was 246 s shorter than the first
stock run, but it includes uncontrolled time after reaching menu and cannot be
claimed as a menu saving. More importantly, the ready launch spent 377.100 s
rebuilding exact identity, so its click→Java boundary was 185.439 s *slower*
than the first stock-plan capture. The current v0 cannot be promoted for HDD
users.

Two redesign requirements follow:

1. A first-seen identity may train immediately; it must not be consumed until
   a later independently rebuilt identity matches exactly. This removes the
   otherwise wasted proof-only game launch without weakening the consumption
   gate.
2. Identity must be incremental and per-profile. A versioned local manifest may
   reuse unchanged files only with a demonstrated filesystem identity/change
   contract; journal/volume/FileId discontinuity, corruption, reparse ambiguity,
   managed update, repair/download or unknown state must fall back to full
   hashing. Rehashing the roughly 1.23 GiB pack inputs on every launch is not
   acceptable on this HDD.
