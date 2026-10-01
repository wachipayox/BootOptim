# FerriteCore late bounded presizing — 2026-10-01

Status: **NEW BOUNDED SALVAGE TRIAL**, not production. Integration authority
refreshed to bd12a367 then 9fbb4041; #298/#308/#310 and final rejection #314
reviewed. Persistent empty storage is retired: this changes that exact memory
premise, not the hash algorithm or an unchanged repeat of the old candidate.

Actual FerriteCore 7.0.3 bytecode synchronizes quad insertion on its existing
static final ObjectOpenCustomHashSet. The anonymous listener clear/trim runs
after loading, without that monitor. Preserve those calls and their existing
ordered reload contract. Do not introduce another table, mutex, thread or
cache of model data. Capture previous unique count at original clear and keep
only an integer bounded to 2^20. Original trim still reduces storage to one
slot. At the next first insertion, under the original monitor, consume that
hint once and call **public** fastutil 8.5.12 ensureCapacity(int). Inspection
of the installed bytecode confirms public API; no reflection/method handle is
needed. This preallocates an empty table near actual use instead of keeping it
through all preparation. It still occupies the normal live bake table memory.

Exact Ferrite version and exact stock set class only; the diagnostic subclass
is admitted solely with the explicit trial property. Nonempty/custom sets,
zero hint, absent/changed version or disabled flag use original addOrGet.
Original equality/hash/canonical representative/clear/trim/null semantics stay
intact. ensureCapacity allocation failure leaves original fields intact in
the inspected implementation; consume the hint and try stock insertion.
No partial model publication, shader/GL work, weaker hash or extra worker.
Volatile pending count publishes across cleanup/next bake; the original mutex
serializes first insertion and all later calls. Same existing generation
ordering assumption as Ferrite, no added lock around original cleanup.

New flag: `boot_optim.ferriteCoreQuadPresize`, opt-in only in this bounded trial.
Old `ferriteCoreQuadCapacity` code/property is absent. Do not ship an indefinite
disabled option: finish the finite gates then promote cleanly or retire.

## Measurement

Reuse #308's lifecycle/actual table diagnostic rather than another reload
profiler. Same measured subclass in both arms; initial warmup plus four
same-mode primers and C1/B1/B2/C2 observations = nine full resource generations.
Changing mode takes effect for the following measured generation after its
primer; a leftover preceding-mode hint is absorbed in that unmeasured primer.
No world. Exactly two controls and two candidates.

Both arms clock the actual addOrGet operation including helper check, reserve,
stock hash/equality/canonicalization. Primitive totals under existing monitor,
two thread CPU reads per call, same wall read placement; no per-call allocations.
This includes added per-insertion guard cost, not only avoided growth. CPU
absolute includes identical clock overhead and is not uninstrumented budget.
Record exact insertion counts and actual unique quads. Growth CPU/wall remains
separate explanatory data and includes the one first reservation. **It is
nested inside insertion and must never be added again.** Primary owner total
is insertion + clear + original trim. Same-mode/activation/clock/workload/
complete-future errors invalidate, not become a rejection or win.

Both arms must end with one empty slot and no keys; candidate has a bounded
pending integer. For the pinned ~480k-unique pack expect one late reserve versus
19 control growths. Strict checker rejects old capacity markers, wrong slots,
missing pending hint, differing insertion workloads or failed generations.
Adjacent full-reload wall, GC and heap are reported separately for indirect
cost; not all global delta is automatically this mechanism.

Local packaged build and actual-fastutil tests pass: 160,000 canonical identity
checks with changed generations/nulls/collisions, original trim/lifetime,
bounded hint/allocation and unknown/nonempty/disabled fallback. Existing
720,000 measured-set equivalence checks pass. Thirteen strict parser checks
pass, including nested growth not double counted and explicit ns/ms units.
These counts are correctness fixtures, not actual performance wins.

Next gate: hosted exact-pack actual-owner comparison with sparse startup
profiling and these bounded timers. Retain only if both opposite-order primary
owner CPU contrasts improve without semantic/indirect failure. Then clean
uninstrumented and physical memory/GC gates; never run per-insertion CPU timers
on the old laptop as a normal performance load. A physical adaptation must
strip those high-volume clocks or demonstrate its observer overhead first.
No physical campaign dispatched for this candidate yet.
