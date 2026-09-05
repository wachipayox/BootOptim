# Resource-open physical-path audit — 2026-09-06

Status: **REDESIGN DIAGNOSTIC / HOSTED EXACT-PACK FIRST / NO PHYSICAL RERUN YET / PRODUCTION NO-GO**

Audit branch: `agent30/audit-resource-open-physical-path`

Integration authority inspected: `agent/integration-current` at `145c10c2f8132b21e7b7be067c56513b394ccb5a`. The older prompt/base SHA `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c` is no longer current integration.

Relevant history: PR #47 (reload critical path), #68 (slow-laptop JFR evidence), #69 (resource-pipeline decomposition), #71 (`agent/profile-resource-open-physical-path`).

## Decision

Do **not** request another laptop run of PR #71 or a minimally repaired copy of that head.

PR #71 already received a physical exact-pack attempt on 2026-09-01. It reached the then-current main-menu marker at about `342.994 s`, but the diagnostic changed rendering semantics: the title logo disappeared, an image layer rendered incorrectly, and later shader reads failed because the global `Resource.openAsReader` mixin dereferenced `Resource.source` when it was legitimately null. The run is therefore not valid performance baseline evidence. Its pre-failure aggregates are useful only for stage directionality.

The next runtime gate, if this front is continued, should be **one hosted exact-pack diagnostic smoke** from current integration after a lower-noise, context-safe redesign. Hosted exact-pack now contains the pinned software corpus and enabled resource-pack selection that PR #71's original 2026-09-01 decision said CI lacked. Physical hardware is reserved for a later, single opt-in arbitration only if the repaired hosted result leaves a Windows/filesystem/page-cache-sensitive mechanism whose decision cannot be made reproducibly in CI.

No production cache, index, worker pool, or lazy-resource behavior is justified by this audit.

## What PR #71 actually established

The source split is correct:

- `Resource.openAsReader()` is an open/wrapper boundary. It calls `Resource.open()`, then constructs UTF-8/BufferedReader wrappers. JSON bytes are consumed later by the parser.
- NeoForge mod JAR resources normally use `PathPackResources` over SecureJar/UnionFS and embedded ZipFS.
- Standalone ZIP resource packs use `FilePackResources` and must remain a separate path.
- `resource.open_supplier` includes supplier execution / path-to-entry open work.
- a timed `InputStream.read` wrapper includes underlying archive read plus decompression work for DEFLATED entries; it is not a pure physical-disk timer.
- ModelManager launches block-model and blockstate resource futures concurrently. Task sums from the two fronts must not be added.

PR #71 also kept separate `PathPackResources.listResources` and `FilePackResources.listResources` attribution. That separation should be retained.

## Existing measurements and metric type

PR #69 slow-laptop decomposition:

- block states: `11,435` tasks / `8,649.468 ms` **task-sum**;
- block-state `Resource.openAsReader`: `7,961.276 ms` **task-sum**;
- block-state Gson parse: `549.682 ms` **task-sum**;
- block-state enumeration: `421.992 ms` **wall scope**;
- block models: `44,103` tasks / `63,243.150 ms` **task-sum**;
- `BlockModel.fromStream`: `5,896.289 ms` **task-sum**;
- block-model enumeration: `2,753.944 ms` **wall scope**.

Those numbers identify composition, not recoverable startup wall.

PR #71's failed physical attempt nevertheless produced a very strong pre-failure Decocraft block-model split:

- `7,280` `resource.open_as_reader` calls: about `11,903.532 ms` **task-sum**;
- `7,280` `resource.open_supplier` calls: about `11,824.171 ms` **task-sum**;
- `7,280` parse calls: about `346.282 ms` **task-sum**;
- measured stream reads: about `6.409 ms` **task-sum**, `1,623,056` logical bytes;
- all block-model enumeration: about `4,196.046 ms` **wall scope**.

This disproves the working idea that Decocraft's measured model-open cost is primarily JSON body throughput or JSON parsing. It points upstream of body consumption, inside supplier/open/path-entry setup. It does **not** prove a recoverable `11.8 s` startup saving because that value is concurrent task-sum.

Historical JFR independently recorded `8,899` `jdk.FileRead` events and about `3,603` reads / `11.8 MB` against the Decocraft JAR. Physical reads therefore exist, but the PR #71 split shows that logical model-body read time as measured at the returned stream is tiny relative to supplier/open task-sum. A deeper FileChannel-vs-inflate profiler is not justified until the critical-path question is answered.

## Why the current PR #71 instrumentation should not be rerun

The semantic bug is explicit in `ResourceOpenPhysicalMixin`: at global `Resource.open` / `openAsReader` entry it evaluates `this.source.packId()` before `ResourceOpenPhysicalProfiler.begin*` can reject calls outside the intended ModelManager context. Runtime-created `Resource` instances may have `source == null`, so profiling affects unrelated shader/FancyMenu reads.

There is also avoidable observer cost: `MeasuredInputStream` calls `System.nanoTime()` on every `read()` and `read(byte[],off,len)` invocation. The measured Decocraft read bucket is only about `6.4 ms` task-sum, so per-read timing is unnecessary for the now-disfavored body-I/O hypothesis and can be a nontrivial observer relative to the quantity being measured.

## Required redesign before any runtime

A repaired diagnostic should be based on current integration and keep only bounded aggregate state.

1. **Context-safe Resource hook.** Check the ModelManager ThreadLocal context first, before touching `Resource.source`. Prefer deriving pack identity from the ModelManager task context already established from its `Resource` argument. Outside `block_models` / `block_states`, return immediately and do not dereference or wrap anything. Null source must fail open.
2. **No per-read clock.** Keep logical bytes/read-call counts with primitive-local accumulation if still useful, but remove `System.nanoTime()` around every read. Use existing/optional JFR `jdk.FileRead` evidence for actual physical reads rather than pretending the wrapped stream isolates disk wait from inflater CPU.
3. **Retain stage aggregates.** `enumeration.wall`, per-pack `listResources` wall, `open_as_reader` task-sum, `open_supplier` task-sum, parse-inclusive task-sum, logical bytes, call counts. No resource IDs or per-resource logging.
4. **Retain source-path separation.** Report `PathPackResources`/SecureJar-backed mod resources separately from `FilePackResources` standalone ZIPs and from unknown custom `PackResources` implementations.
5. **Add critical-input timestamps.** Reuse the #47/ModelManager subphase approach to timestamp completion of block-model, blockstate, and atlas prerequisite futures, ModelBakery start, and the ModelManager preparation barrier. The useful wall metric is the resource input's exclusive gate tail, not accumulated open time.
6. **Record effective ModernFix overlap.** ModernFix's `perf.resourcepacks` source already contains resource indexes/caches. The diagnostic must establish whether those transformations are actually active in the exact pack before interpreting enumeration/path lookup as an uncovered BootOptim opportunity.

## Critical-path metric

For required ModelManager input futures, define:

`exclusive_input_gate_tail = latest_required_input_completion - second_latest_required_input_completion`

This is **wall time / critical-input ceiling**, not an expected saving. If block models or blockstates are not the latest required input, their exclusive input gate tail is zero for that run even if their task-sum is large.

Also retain the #47-style `ModelManager preparation barrier` timing to confirm that ModelManager remains the global preparation gate in the same workload. Only a later behavior-preserving candidate A/B may convert a ceiling into a TTMM performance claim.

## ModernFix overlap

ModernFix's public `perf.resourcepacks` implementation is directly relevant and rules out inventing a generic resource index from the current evidence:

- its PathPackResources mixin owns a `PackResourcesCacheEngine`, caches namespaces/existence, and replaces `listResources` with cached collection;
- its FilePackResources mixin owns a `ZipPackIndex`, uses it for namespaces and `listResources`, and invalidates it on pack close (feature-level gated in current upstream source).

The exact pack carries ModernFix `5.27.14+mc1.21.1`. PR #69's reflective config probe failed, so the effective state was not established there. The repaired diagnostic must report the effective state, ideally by a fail-open runtime transformation/interface check plus the effective config, rather than assuming source presence means activation.

Therefore: even if enumeration is expensive, a BootOptim listing/index cache is **NO-GO** until a non-overlapping mechanism is demonstrated. The unresolved candidate is narrower: repeated supplier/open resolution after enumeration despite whatever ModernFix already caches.

## Hosted exact-pack gate

One hosted exact-pack smoke is justified only after the diagnostic is repaired. It must validate:

- pinned fixture identity and resource-selection checker pass;
- current integration ancestry;
- ModernFix `perf.resourcepacks` effective state;
- no BootOptim/Mixin/shader/FancyMenu resource errors;
- non-empty `PathPackResources` and `FilePackResources` aggregates where present;
- block-model/state/atlas prerequisite completion timestamps and ModelManager preparation barrier;
- aggregate stage composition with wall/task-sum labels.

### GO

Proceed to a distinct ceiling/optimization experiment only if all of the following are true in the hosted exact pack:

- a model resource future is the latest ModelManager prerequisite and has a material positive `exclusive_input_gate_tail`;
- `open_supplier` is the dominant measured sequential stage in that resource front while body-read/parse evidence remains minor;
- the concentration is attributable to a specific source path (`PathPackResources` or `FilePackResources`) and pack family rather than only a global task-sum;
- the mechanism is not already supplied by active ModernFix resource-pack indexing/caching.

A production claim still requires same-build candidate/control TTMM evidence; reducing open counts or task-sum alone is not enough.

### NO-GO / close

Close the front without a production experiment if any of these occurs:

- model resource futures are not the critical ModelManager input (exclusive gate tail effectively zero);
- parse/body work, not supplier/open, becomes dominant under the repaired low-noise profiler;
- the apparent lookup target is already ModernFix's active cache/index path with no distinct repeated work demonstrated;
- PathPack/FilePack attribution is diffuse or inconsistent enough that no safe bounded mechanism remains.

## Physical-laptop rule

Do not run a repeated physical campaign. At most one later opt-in physical run is warranted if the hosted diagnostic passes all semantic gates and produces a concrete mechanism whose expected result materially depends on Windows storage/UnionFS/ZipFS/page-cache behavior. Before that run, the current resource-selection checker must validate the ten external ZIPs and the effective reload; the 2026-09-05 audit showed that earlier retained laptop evidence had silently omitted them.

## Final conclusion

Hypothesis **discarded**: "the ModelManager resource-open residual is primarily bytes read / JSON parse and therefore a byte cache is the next candidate."

Hypothesis **supported but not yet performance-qualified**: "for Decocraft's mod-JAR model path, most measured per-resource cost is before body consumption, within supplier/open/path-entry setup."

Critical-path status: **unproven by PR #71**. The next decision is a repaired, lower-noise hosted exact-pack diagnostic with critical-input timing; **not** another laptop run and **not** a production resource index/cache.