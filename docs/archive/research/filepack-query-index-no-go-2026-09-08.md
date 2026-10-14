# FilePackResources exact-query ZIP index follow-up — 2026-09-08

Status: **REJECTED / NO-GO AT CURRENT EVIDENCE**

Base audited: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This is Agent 48's follow-up to PRs #140, #141 and #142. No runtime code is added.

## Question

Could BootOptim materially outperform PR #142 with a narrower reload-scoped index keyed by exact `FilePackResources.listResources(PackType, namespace, path, output)` queries, while preserving vanilla pack order, shadowing, namespace/path filtering, `ResourceLocation` validation, `ResourceOutput` callbacks, suppliers, ZIP lifetime and reload reentrancy?

A safe implementation is technically possible: observe the vanilla full enumeration for an exact query, retain only the ordered subsequence that could match that query, and on later identical queries feed that subsequence back into the unchanged vanilla filtering/callback path. A conservative form would not publish a cache until the second observation, would preserve duplicate `ZipEntry` order, would scope state to the open `FilePackResources`/`ZipFile`, capture file identity (normalized path, size and last-modified/file-key when available), and clear on pack close/reload. It would be property-gated, fail-open and default-off.

That mechanism is materially narrower than #142's global `List<ZipEntry>` snapshot. It is nevertheless **not justified as a candidate yet**.

## Why it does not currently beat the #142 no-go

PR #142 already removed the expensive structural part of repeated ZIP enumeration after the first pass: every later `ZipFile.entries()` call was redirected to an immutable in-memory snapshot preserving the original ordered `ZipEntry` sequence. Its hosted exact-pack 3x3 preserved resource selection, atlas `8192x8192x2` and zero BootOptim Mixin errors, but the enclosing performance signal was negligible/incoherent:

- TTMM medians: `90.000 s` candidate vs `92.442 s` control, with paired deltas `+1.342 s`, `-4.578 s`, `-5.516 s` and a quantized 90 s candidate sample;
- `reload -> FancyMenu`: `41.207 s` candidate vs `41.310 s` control (`-103 ms`);
- post-entrypoint: `60.369 s` vs `60.736 s` (`-367 ms`).

The proposed exact-query cache can only improve the **residual work that #142 still performed**: walking an in-memory entry snapshot and executing prefix/filter checks for later exact-query repeats. It cannot recover the HDD/page-cache-sensitive central-directory traversal already removed by #142, nor can it convert overlapped work into critical-path time merely by being more selective.

That is a strict evidence problem, not a claim that the data structure is impossible. Without proof that the residual in-memory scans themselves are material and gate ModelManager preparation, an exact-query cache starts from a smaller savings ceiling than a candidate that already failed to move the real barrier coherently.

## Missing evidence: exact query repetition was never measured

PR #140 intentionally kept cardinality low. The physical runs measured operation/pack aggregates, not exact `(pack type, namespace, path)` tuples:

- physical `filepack-physical-002`: `900` `listResources` calls, `4,957.141 ms` inclusive plus `318.444 ms` for `getNamespaces`; `Glowing Trim Armors v5.0.zip` dominated;
- combined #141 run: only `1,321.009 ms` total inclusive, again dominated by the same pack, while ModelManager remained the enclosing preparation join.

Those counts prove repeated `listResources` activity but **do not prove repeated identical queries**. A cache keyed by exact query only becomes useful after a tuple repeats; a conservative build-on-second-use design needs a third call before it can avoid the full scan. The existing diagnostics therefore do not establish a hit-rate or avoided-entry count for the proposed mechanism.

## Source cross-check: ModernFix

ModernFix already contains a BETA-gated `FilePackResourcesMixin` backed by `ZipPackIndex`. It parses the ZIP central directory into a directory tree and answers namespace/prefix queries without scanning every entry. That source confirms the broad architectural idea is viable, but it does not change BootOptim's evidence gate:

- the exact pack runs at the ModernFix feature level where this mixin is inactive, as established by #137/#140;
- the ModernFix implementation is broader than the proposed BootOptim exact-query cache and reconstructs traversal from a tree, so BootOptim must not assume strict original enumeration order from that implementation;
- enabling or copying the BETA mechanism would therefore add more semantic surface than necessary without evidence that the remaining #142 residual is critical.

## PR #178 isolated replay applicability

PR #178 is useful as a design filter, but its current fixture does not contain the information needed to validate this premise. It replays model/blockstate graph work and phase scheduling; it does **not** capture:

- actual `FilePackResources.listResources` `(pack, type, namespace, path)` query sequences;
- per-ZIP central-directory entry order/counts for those queries;
- the number of first/second/third+ exact-query repetitions;
- barrier-relative timestamps for those exact calls.

Using the current replay to infer a ZIP-query hit-rate would therefore be a provenance error. If #178 later gains a captured resource-query trace, stock-vs-query-index replay becomes appropriate before another full client A/B.

## Reopening gate

Do not implement the runtime cache until a **new low-noise hosted diagnostic or captured replay fixture** proves all of the following:

1. exact `(pack type, namespace, path)` repetition is high enough that third-and-later uses are common on the expensive external ZIPs;
2. a stock-vs-#142-snapshot-vs-exact-query micro/replay comparison shows a material residual CPU/wall reduction from pruning the in-memory snapshot, not merely fewer loop iterations;
3. the affected calls overlap the actual ModelManager/atlas preparation gate in a way that can move the measured barrier;
4. semantic replay preserves the exact ordered output sequence, duplicate behavior, `ResourceLocation` acceptance/rejection and supplier identity for the selected pack corpus.

Only after those gates should a default-off candidate request exact-pack smoke and then 3x3 A/B. The A/B acceptance gate remains identical resource selection, atlas/model markers, zero BootOptim/Mixin errors, and a coherent improvement in TTMM or the real preparation barrier. Mixed-sign deltas or a micro-only win remain rejection.

## Decision

**NO-GO documented.** Do not add another FilePackResources cache on current evidence, do not request a laptop run, and do not reinterpret the 1.321–5.276 s inclusive physical totals as recoverable menu time. The next permissible step is evidence collection for exact-query reuse/residual snapshot cost, not production code.

## References

- PR #140 — FilePackResources ZIP enumeration diagnostic.
- PR #141 — ZIP enumeration / resource-reload boundary correlation.
- PR #142 — global ordered `ZipEntry` snapshot candidate and hosted 3x3 no-go.
- PR #178 — isolated replay methodology; current fixture lacks resource-query traces.
- ModernFix `FilePackResourcesMixin` / `ZipPackIndex` — BETA-gated hierarchical ZIP index source.
