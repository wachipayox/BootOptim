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

## Completed hosted owner result

[Exact-pack run 36880289246](https://github.com/wachipayox/BootOptim/actions/runs/36880289246)
passes on `8f881e7e`. Build and absent-mod startup pass. Strict owner parser
validates nine generations, exact activation and all four observations. Pack
selection valid, stock atlas8192x8192x2, zero BootOptim Mixin errors.

| Observation | Insertions | Owner CPU ms | Growth CPU ms | Growth calls | Empty slots | Reload wall ms | GC ms | End heap bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| C1 | 1250596 | 2231.823102 | 153.502241 | 19 | 1 | 31794.583754 | 2137 | 5458835360 |
| B1 | 1250596 | 2005.836799 | 0.239587 | 1 | 1 | 31308.721208 | 1875 | 6000906992 |
| B2 | 1250596 | 2012.820220 | 0.243364 | 1 | 1 | 30897.096714 | 1826 | 5891177512 |
| C2 | 1250596 | 2178.549746 | 161.408432 | 19 | 1 | 30419.259290 | 1978 | 5512295680 |

Opposite-order primary owner CPU saves **225.986303 / 165.729526 ms**, midpoint
195.857915 ms, including helper/first reserve/insertion/clear/stock trim. Unique
479823..479905; candidate hints bounded479905/479830, no empty table retained.
Growth saves153.262654/161.165068ms; these nested numbers are explanatory and
NOT added to owner savings. All 1,250,596 insertions have the same clock placement.
Absolute owner CPU includes observer overhead; do not publish it as an
uninstrumented budget or all-reload critical-path benefit.

Adjacent full reload−485.863/+477.837ms, GC−262/−152ms, end heap
+542071632/+378881832bytes. Final table is one slot/no keys in both arms; this
does not establish a retained-array leak or attribute all end heap to this
mechanism. Physical memory/GC gate remains necessary given the preceding
implementation's low-end result. No global seconds-saved claim.

Segment verdict **retain**, bounded next step clean production source without
measured subclass, insertion clocks, mask/driver/parser logic. Then uninstrumented
runtime and physical gate; this is not permission to merge profiling code or
keep an indefinite disabled experiment. Original persistent-capacity variant
remains retired. Artifacts:
`C:/BootOptimBench/analysis-reload-20261001/ferrite-presize-36880289246`.
