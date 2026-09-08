# FilePackResources entry-read replay — 2026-09-08

Status: **LIMITED / NO-GO AT CURRENT EVIDENCE**. No runtime cache, exact-pack startup A/B, or laptop run is justified.

Base: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

PR: #187, branch `agent54/filepack-entry-read-replay-20260908`.

## Question

PR #142 already removed repeated real `ZipFile.entries()` central-directory traversals after the first pass, and PR #182 rejected an exact-query index because it could only prune the residual in-memory scan. This experiment changes the premise: could the target HDD/page-cache benefit from avoiding repeated **entry decompression/read** for the same winning external-resource-pack ZIP entry?

Vanilla 1.21.1 already owns one `FilePackResources.SharedZipFileAccess` per file pack and lazily keeps one `ZipFile` open for that pack. Merely retaining/reusing the archive handle is therefore not new. A byte-cache mechanism only has leverage when the same exact entry bytes are requested more than once while the same resource-pack generation is alive.

No runtime cache was implemented. The requested replay/microbenchmark gate was used first.

## Provenance contract

The workflow consumes the immutable artifact from PR #183 / run `34173324041` / artifact `10036433904` rather than rebuilding its bounded semantic sample with a different heuristic.

Required contract:

- exact-pack SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- PR #183 semantic digest: `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`;
- 24 bounded roots / 24 materialized fixture entries plus the runtime vanilla parent resolved by #183's real-class replay;
- selected external resource-pack order exactly from pinned `options.txt`;
- each #183 bounded external winner must resolve to the identical archive entry and identical SHA before the I/O replay proceeds.

The first implementation intentionally started with ordinary model JSON only. Run `34175241986` falsified that scope before the benchmark phase: it found only 719 external logical models, while `Glowing Trim Armors v5.0.zip` had 21,916 central-directory entries and **zero** model JSON entries. Because that would exclude the physical FilePack outlier, the replay was corrected rather than interpreting the biased sample.

The final replay covers all valid `assets/<namespace>/<path>` entries in the ten selected external ZIP packs. It keeps `options.txt` later-wins precedence, retains earlier external candidates as shadowed rows for validation, rejects duplicate paths within one archive rather than inventing duplicate-name precedence, and still uses #183's bounded model winners as entry+SHA oracles.

This does not claim exact NeoForge mod-to-mod precedence or custom loader semantics; the target is the user-selected external ZIP tier where `options.txt` ordering is authoritative.

## Replay mechanism

`FilePackEntryReadReplay` uses Java 21 `java.util.zip.ZipFile`, matching the underlying JDK container used by `FilePackResources`.

For each selected external archive it records exclusive current-thread wall and CPU for:

1. `stock_one_pass`: one ZipFile open, one central-directory enumeration, then one `getEntry/getInputStream/readAllBytes` for each winning valid asset;
2. `cache_first_pass`: the same one-winner request set through a simulated bounded byte cache;
3. `cache_repeat_upper_bound`: a deliberately artificial second request of the exact same set, used only to prove the mechanism and establish a repeat-all upper bound.

The one-pass request set contains one request per logical winning resource. Therefore this replay is a **selection/read working-set replay, not a runtime frequency trace**. Zero first-pass hits means the #183-provenance working set itself supplies no evidence of second+ reads; it does not prove that stock Minecraft never reopens an entry through separate consumers.

Linux `strace -f -c` separately reports `openat/read/pread64/lseek/close/mmap/munmap`. That execution is warm/page-cache contaminated by design and is used only to identify the JDK I/O syscall stack, never as a performance A/B.

## Final green replay

Workflow run: `34175407353`.

Artifact: `filepack-entry-read-replay`, id `10037075267`, artifact ZIP SHA-256 `3d564060bd18c9ccf9a3a61fa5e280a06ebb4c204f8b6691b5d252ba3570e095`.

Build run for the same head: `34175407442`, green.

### Selection / bytes

- #183 bounded winners verified by exact archive entry + SHA: `24/24`;
- selected external ZIP archives: `10`;
- valid external asset candidates: `22,749`;
- logical winning resources: `22,745`;
- shadowed external candidates: `4`, order validation passed;
- winning uncompressed bytes: `9,861,823`;
- winning compressed bytes: `8,115,221`;
- `Glowing Trim Armors v5.0.zip`: `21,916` central entries and `19,803` accepted asset entries.

### Exclusive replay phases

| phase | wall | current-thread CPU | ZIP bytes actually read | entries | cache result |
| --- | ---: | ---: | ---: | ---: | --- |
| `stock_one_pass` | `483.694 ms` | `456.157 ms` | `9,861,823` | `22,745` | no cache |
| `cache_first_pass` | `266.020 ms` | `251.586 ms` | `9,861,823` | `22,745` | `0 hits / 22,745 misses` |
| `cache_repeat_upper_bound` | `47.842 ms` | `47.064 ms` | `0` | `22,745` | `22,745 hits / 0 misses` |

**Do not subtract `stock_one_pass` and `cache_first_pass` as a savings measurement.** They execute sequentially in one hosted JVM and the latter benefits from already touched archive/page/JIT state. They are separate exclusive phase observations, not a controlled A/B.

The synthetic repeat phase proves only that a bounded byte cache can avoid repeat decompression/read when the *same request set is deliberately replayed*. It does not show that the startup performs that repeat.

### I/O syscall stack

The separate `strace` execution reported `205,801` selected syscalls total:

- `read`: `103,634` calls / `0.623963 s` traced;
- `lseek`: `101,353` calls / `0.558145 s` traced;
- `pread64`: `225` calls / `0.001518 s`;
- `openat`: `179` calls (`56` errors);
- `close`: `125` calls.

This confirms a read/seek-heavy JDK ZIP path and is consistent with storage/page-cache sensitivity, but it is a warm hosted Linux trace, not HDD timing and not critical-path evidence.

## Safety model exercised

The simulated cache is intentionally stricter than a path-only cache:

- archive fingerprint: normalized path, size, last-modified timestamp and filesystem file key when available;
- entry fingerprint: name, CRC, uncompressed size, compressed size and compression method;
- immutable copied byte arrays only; no retained streams;
- 16 MiB LRU upper bound;
- actual retained full winning set in this replay: `9,861,823` bytes;
- changing the archive mtime changed the archive fingerprint and passed the invalidation probe;
- corrupt ZIP probe failed without returning cached bytes;
- shadowed-resource order was preserved/validated.

A production mechanism would additionally need lifetime scoping to the live resource-pack generation / close-reload boundary, concurrent first-fill behavior, and a kill switch. None is added because the reuse gate failed.

## Critical-path interpretation

This replay measures exclusive Java ZIP work only. It does **not** measure TTMM or a ModelManager/atlas critical-path delta.

Historical #141 evidence still places FilePack work inside the broad ModelManager preparation window while showing only `1.321009 s` inclusive in one physical run versus `5.275585 s` in #140. Those values demonstrate storage/page-cache variance but are not recoverable wall-time sums.

The current replay found no demonstrated second+ entry reads in its semantic one-pass working set. Therefore proven critical-path saving from this candidate is **0 ms**: there is no runtime candidate whose barrier effect can honestly be measured yet.

## Decision

**NO-GO at current evidence / reopenable if runtime reuse is proven.**

Do not implement a byte cache, do not run exact-pack startup 3x3, and do not request a physical laptop A/B from this branch. The exact-pack startup benchmark for this PR is intentionally not requested because the replay gate did not justify runtime code.

This is materially different from #142 and #182: the hypothesis tested entry decompression/read reuse rather than central-directory enumeration or query filtering. The mechanism is technically viable and bounded, but the required real repeat frequency is missing.

## Concrete reopening / promotion gate

Reopen only with a low-overhead exact-pack runtime trace that fingerprints actual `FilePackResources` entry stream opens and proves all of the following during the **same initial resource generation**:

1. second+ opens of the exact same `(archive fingerprint, entry fingerprint)` are real, not inferred from `listResources` counts;
2. repeated-entry read/decompression owns at least `200 ms` of exclusive hosted wall/CPU **or** another directly measured material barrier-relative ceiling, concentrated before the ModelManager/atlas preparation join;
3. the repeated bytes/entries are stable enough that a bounded cache does not merely trade I/O for comparable allocation/GC;
4. winner selection, shadowed candidates, entry SHA, invalidation, ZIP failure behavior and callbacks remain stock-equivalent.

Only then create a separate default-off runtime candidate with a hard memory cap and kill switch. It must pass semantic replay/error probes first, then exact-pack candidate/control A/B with at least three fresh-VM repetitions and comparable process-origin/main-menu endpoint. Promotion requires a coherent improvement in TTMM or the actual preparation barrier, not task-sum/inclusive FilePack movement. Because the premise is storage/page-cache-sensitive, a small-but-coherent hosted result still requires **one** final physical gate before production; repeated laptop exploration is not justified.

## Links

- PR #187: https://github.com/wachipayox/BootOptim/pull/187
- Green replay: https://github.com/wachipayox/BootOptim/actions/runs/34175407353
- Replay artifact: https://github.com/wachipayox/BootOptim/actions/runs/34175407353/artifacts/10037075267
- Green build: https://github.com/wachipayox/BootOptim/actions/runs/34175407442
- PR #183 source contract: https://github.com/wachipayox/BootOptim/pull/183
- PR #142 enumeration snapshot: https://github.com/wachipayox/BootOptim/pull/142
- PR #182 exact-query no-go: https://github.com/wachipayox/BootOptim/pull/182
