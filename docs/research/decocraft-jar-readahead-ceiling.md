# Decocraft physical-JAR read-ahead ceiling

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE**

Branch: `agent/experiment-decocraft-jar-readahead`

This experiment exists only to test whether the laptop's expensive stock resource opens are dominated by cold/random physical reads that can be converted into page-cache hits by one sequential pass over Decocraft's physical mod JAR. It does not change resource selection or decoded content.

## Exact-pack evidence that motivated the experiment

PR #72's corrected exact-pack run observed 20,054 sprite loads, 20,049 unique resources and only five repeated resource keys. Atlas task-sum was 83,872.766 ms, with 76,125.646 ms measured inside `Resource.open()`. Work after `open()` returned was much smaller: 116.321 ms of stream reads and 3,113.293 ms of STB PNG decode. Observed atlas CPU time was 6,437.500 ms.

Decocraft alone accounted for 5,771 sprite loads and 51,402.057 ms inclusive task-sum. Its `Resource.open()` bucket was 47,697.878 ms, while observed CPU was 3,078.125 ms and STB decode was 1,630.305 ms. The published Decocraft 3.0.11 JAR contains 5,773 texture PNGs, so startup touches essentially the entire texture corpus.

The same static audit found that all 5,773 Decocraft PNG entries are outer ZIP-DEFLATED even though PNG is already compressed. The outer ZIP compression shrinks 20,700,997 bytes to 20,537,997 bytes, only 0.787%. This fact does **not** imply that outer inflate is responsible for the 47.7 s open task-sum.

## Why the post-open read metric is not physical JAR read time

SecureJarHandler 3.0.x UnionFS resolves a logical `UnionPath` and ultimately opens the selected backing path using `Files.newByteChannel(..., READ)`. For a JAR-backed path that reaches JDK ZipFS. Java 25's ZipFS read-only `newByteChannel` obtains the entry stream and eagerly calls `readAllBytes()` before returning a `ByteArrayChannel`.

Therefore the expensive archive access, any outer ZIP inflate and eager byte-array materialization happen before `Resource.open()` returns. The later `ChannelInputStream` read seen by PR #72 is mostly copying already-materialized bytes.

This also means a pure streaming rewrite is not justified by the 76.1 s figure. Streaming would still perform resource selection, archive reads, ZIP inflate and PNG decode; it would mainly remove one eager materialization layer. ZipFS also uses positional `FileChannel` reads when the archive backing channel is a `FileChannel`, so there is no source-level proof that `readAllBytes()` alone serializes all resource workers behind one mutable archive position.

## Why this experiment preserves semantics

The experiment obtains Decocraft's physical mod-file path through NeoForge/FML's public mod-file API and performs one ordinary sequential `FileChannel` read of that file. The read bytes are discarded immediately.

It does **not**:

- replace or bypass UnionFS;
- resolve individual resources itself;
- alter pack ordering or override semantics;
- cache decoded or encoded resource contents in Java heap;
- alter ZIP entry inflate;
- alter PNG decode, metadata, mipmaps or atlas stitching;
- run on subsequent/manual resource reloads.

The hook is immediately before the `ReloadableResourceManager.createReload(...)` call in `Minecraft.<init>`, so its lifetime is exactly the initial client reload. If Decocraft is absent, its mod file is not a regular physical file, or an I/O/API failure occurs, the experiment logs one result and fails open.

## Hypothesis and ceiling rule

The hypothesis is specifically physical access ordering:

> One sequential scan of the Decocraft JAR may be cheaper on the old laptop's storage than thousands of later effectively random entry reads, and may leave those archive pages resident for the stock ZipFS reads performed by ModelManager/atlas preparation.

This is not a claim that `Resource.open()` becomes free. The experiment still pays the normal later ZipFS lookup, entry inflate and PNG decode. It also adds an extra full-file read, so it can lose if the OS cache is already hot, the JAR pages are evicted before use, or sequential warming competes with other startup I/O.

The first candidate is intentionally synchronous immediately before the initial reload. Do not overlap it with MCEF yet: exact-pack logs show MCEF initialization ends only about 0.2-0.8 seconds before the initial resource reload begins, and introducing concurrent disk I/O would confound the ceiling by changing MCEF's own startup time.

## Metrics emitted

The experiment emits exactly one `BOOTOPTIM_DECOCRAFT_JAR_READAHEAD` line per client construction with one of these states:

- `completed`: physical file scanned, including `bytes_read`, `file_bytes`, wall time and current-thread CPU time when available;
- `skipped`: Decocraft missing, mod list unavailable or non-regular physical path;
- `failed_open`: an exception occurred and stock startup continues;
- `disabled`: system property kill switch disabled the diagnostic.

The kill switch is `-Dboot_optim.experimentDecocraftJarReadahead=false`. It defaults to enabled **only on this diagnostic branch**.

## Validation / stop conditions

1. Build CI must pass.
2. Vanilla Startup CI must reach the menu with the mixin injection applied and the no-Decocraft path fail-open.
3. Do not request an exact-pack laptop run merely because CI is green. First inspect the CI marker and source-level hook placement.
4. If exact-pack testing is later warranted, compare the read-ahead artifact with the same artifact disabled via the system property. Use initial resource-reload/ModelManager wall and end-to-end main-menu wall; do not infer a global win from the read-ahead function's own duration.
5. If synchronous read-ahead adds roughly as much wall as it removes from the subsequent reload, close the idea. Only then consider whether a carefully scheduled overlap has any independent ceiling.
6. This branch is diagnostic and must not be merged into `agent/integration-current`.
