# FilePackResources entry-read replay — 2026-09-08

Status: **ACTIVE REPLAY; no runtime candidate unless the one-pass trace proves repeated entry-read reuse**.

Base: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Question

PR #142 already removed repeated real `ZipFile.entries()` central-directory traversals after the first pass, and PR #182 rejected an exact-query index because it could only prune the residual in-memory scan. This experiment changes the premise: could the target HDD/page-cache benefit from avoiding repeated **entry decompression/read** for the same winning external-resource-pack ZIP entry?

Vanilla 1.21.1 already owns one `FilePackResources.SharedZipFileAccess` per file pack and lazily keeps one `ZipFile` open for that pack. Merely retaining/reusing the archive handle is therefore not new. A byte-cache mechanism would only have leverage when the same exact entry bytes are requested more than once while the same resource-pack generation is alive.

No runtime cache is implemented here. The first gate is an exact-pack replay that measures whether the semantic one-pass workload contains such reuse at all.

## Provenance contract

The workflow consumes the immutable artifact from PR #183 / run `34173324041` / artifact `10036433904` rather than rebuilding its bounded semantic sample with a different heuristic.

Required contract:

- exact-pack SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- PR #183 semantic digest: `502282f60acdc16beaaf312a81fd76958a8a7ca9504f1ee5305e970eb5fc2050`;
- 24 bounded roots / 24 materialized fixture entries plus the runtime vanilla parent resolved by #183's real-class replay;
- selected external resource-pack order exactly from the pinned `options.txt`;
- each #183 bounded winner must resolve to the identical external archive entry and identical SHA before the I/O replay proceeds.

The I/O extension then enumerates ordinary model JSON entries in all selected external ZIP packs using the same strict duplicate-path refusal as #183. For every logical external model, later selected packs win. Earlier external candidates are retained as `shadowed` rows for precedence validation but are excluded from the timed winner-read pass.

This does not claim exact NeoForge mod-to-mod precedence or custom loader semantics; the FilePack target here is the user-selected external ZIP tier where `options.txt` ordering is authoritative.

## Replay mechanism

`FilePackEntryReadReplay` uses Java 21 `java.util.zip.ZipFile`, matching the underlying JDK container used by `FilePackResources`.

For each selected external archive it records exclusive current-thread wall and CPU for:

1. `stock_one_pass`: one ZipFile open, one central-directory enumeration, then one `getEntry/getInputStream/readAllBytes` for each winning ordinary model entry;
2. `cache_first_pass`: the same one-pass request set through a simulated bounded byte cache;
3. `cache_repeat_upper_bound`: a deliberately artificial second request of the exact same set, used only to establish a maximum repeat-read ceiling.

The acceptance logic requires the semantic one-pass cache to have **zero hits** unless the request trace itself contains an exact duplicate winner request. A hit that exists only in `cache_repeat_upper_bound` is not startup evidence and cannot justify runtime code.

Linux `strace -f -c` separately reports `openat/read/pread64/lseek/close/mmap/munmap`. Its second execution is warm/page-cache contaminated by design and is used only to show the JDK I/O syscall stack, not as a performance comparison.

## Safety model for any future cache

The replay simulation is intentionally stricter than a path-only cache:

- archive fingerprint: normalized path, size, last-modified timestamp and filesystem file key when available;
- entry fingerprint: name, CRC, uncompressed size, compressed size and compression method;
- immutable copied byte arrays only; no retained streams;
- 16 MiB LRU upper bound;
- a changed archive fingerprint invalidates the key;
- corrupt ZIP probe must fail without returning stale cached bytes;
- resource-pack precedence, central-directory order, shadowed candidates, callbacks and `IoSupplier` identity are not replaced by this replay.

A production mechanism would additionally need reload/close lifetime scoping and a kill switch. None is added unless the reuse gate passes.

## Decision gate

A runtime candidate is allowed only if this replay or a subsequent real exact-pack trace proves material exact-entry second+ reads in the **same startup resource generation**, and the avoided read/decompression CPU/wall is large enough to plausibly move the ModelManager/atlas preparation barrier. The synthetic repeat pass alone never qualifies.

If one semantic pass has no cache hits, the correct disposition is **NO-GO / LIMITED**: do not build a byte cache, do not run 3x3 exact-pack startup A/B, and do not request the laptop. Reopen only with a real FilePackResources entry-read trace showing repeated exact winner entries on the critical preparation path.

## Historical boundaries

- #140: 5.275585 s inclusive FilePack on one physical run, storage-sensitive but not a savings claim.
- #141: 1.321009 s inclusive FilePack on another run while ModelManager remained the enclosing join; large page-cache/storage variance.
- #142: ordered `ZipEntry` snapshot removed repeated real central-directory scans but moved hosted reload→FancyMenu only -103 ms; no production win.
- #182: exact-query cache rejected because it can only optimize residual in-memory scans without measured third+ query reuse.
- #183: real-class model replay and provenance contract used here.

## Evidence

Workflow: `.github/workflows/filepack-entry-read-replay.yml`.

Action/run/artifact numbers and measured wall/CPU/syscall results are appended after the first green PR run. Until then this branch is diagnostic tooling only.
