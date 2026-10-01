# FerriteCore owner-first verdict — 2026-10-01

Status: **ACTIVE BOUNDED VALIDATION**, not production. Integration base refreshed
at `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`; #298/#304/#306 and their actual
results reviewed. Existing table-capacity candidate is unchanged. This new
premise repairs #304's overly broad `bakeModels` clock under the explicit
segment-first user decision policy (#307).

## Actual segment

FerriteCore 7.0.3 `Deduplicator` has one private static final quad table, creates
it with `ObjectOpenCustomHashSet(Hash.Strategy)` and uses synchronized `addOrGet`.
Its anonymous reload listener performs original clear, then trim. Candidate
#298 keeps the original hash/equality, ordering, canonical representatives and
clearing, retaining only bounded empty table storage.

For diagnosis only, intercept that exact constructor and instantiate a final
subclass that inherits stock insertion/hash/equality unchanged. **Both arms
use that same subclass.** Override rehash, clear and trim solely to time original
calls. Two thread CPU reads per rehash/clear/trim; no per-quad clocks, recording,
vertex copies, changed mutex, GL or background tasks. Empty rehash inside trim
is included only in trim, never counted again as growth. CPU and elapsed wall
are separate. Inspect table size and unique count to verify actual activation.
The exact fastutil 8.5.12 trim() delegates virtually to trim(size), so only the
trim(int) override owns its clock. Future versions must be audited again.

This subclass changes Java class identity and virtual dispatch for diagnostics,
so it must never enter a production promotion. The narrow stock-class capacity
guard admits it only under the explicit trial flag. Missing mixins/CPU clocks,
failed futures, resets with live keys, duplicate tables, failed trim or resource
fallback invalidate the run instead of producing a performance decision.

## Four observations

One JVM, full stock menu reloads, no world: C1 / B1 / B2 / C2. A same-mode primer
precedes each observation and is unmeasured. Thus eight reloads plus the initial
generation, exactly two controls and two candidates. Primers prepare the empty
capacity for the **next** bake; judging the cleanup generation itself would
shift the treatment by one and manufacture a result.

Primary segment: sum of the actual nonoverlapping growth-rehash, clear and trim
CPU/wall intervals. This is not an inclusive listener sum. Report each interval
and both opposite-order contrasts. The segment supports retention only when
both contrasts reduce owner CPU and growth calls with comparable unique data.
Other model phases and launcher/runner wall are not the primary verdict.

Indirect checks: retained empty slots (<=2^21 plus null slot), no keys after
clear, original capacity/canonicalization semantics, GC and heap by complete
reload. Do not convert a noisy GC contrast into a causal failure; inspect the
actual memory mechanism and any repeatable attributable regression. Final
uninstrumented runtime/visual validation remains necessary before production.

`check_ferrite_phase.py` validates exact boundaries, order, masks, successful
primers, nine resource generations, four candidate transitions and workload.
It records raw observations and a binary segment verdict, distinct from final
semantic/indirect-cost promotion. Hosted collection uses full console **after
process exit** so rotated latest.log cannot lose early trial markers.

Flags: `-Dboot_optim.benchmark.ferritePhaseTrials=true` and
`-Dboot_optim.ferriteCoreQuadCapacity=true`; hosted optional welcome decline uses
the existing exact AnalogAudio onClose route. No laptop run has been launched.

## Other pending mechanisms

Decocraft V2 (#296), Sodium (#299/#300), layer arithmetic (#301) and multipart
union (#305) must receive a separate exact-segment disposition. Prior whole-bake
or whole-load clocks are not relabeled as exclusive owner evidence. Preserve
the existing completed artifacts, then integrate or remove each active lane
under #307; do not repeat the interrupted broad 21-step laptop suite.

## Completed actual-owner hosted comparison

[Run 36838525543](https://github.com/wachipayox/BootOptim/actions/runs/36838525543)
on source `ef149f041fc8a28faeb29ef4954a8212d0704bf1` passed Build, minimal
startup, strict owner endpoint/capacity/clock checks, all nine resource generations
and unchanged pack selection. Measurements below exclude primers and startup.

| Observation | Growth calls | Growth CPU ms | Clear + trim CPU ms | Net owner CPU ms | Reload wall ms | GC ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| C1 | 19 | 175.703 | 1.097 | 176.800 | 27783.742 | 1877 |
| B1 | 0 | 0 | 1.054 | 1.054 | 26533.910 | 1836 |
| B2 | 0 | 0 | 1.049 | 1.049 | 26149.267 | 1754 |
| C2 | 19 | 138.561 | 1.057 | 139.618 | 26995.053 | 2077 |

Net owner CPU saved **175.746 / 138.570 ms**, midpoint 157.158 ms, in the two
opposite-order contrasts. Actual unique inputs 479823–479905 (0.0171% spread).
All four candidate transitions retained 1048576 empty slots; controls trimmed
to one. Thus this mechanism demonstrably removes growth CPU in its actual game
segment even though it fell below the old whole-bake 200 ms screening floor.
Current explicit user policy allows this verified CPU removal to be retained.

Full-reload contrasts −1249.832/−845.785 ms and GC −41/−323 ms are adjacent
observations, not all attributable to the table. End-of-reload heap contrasts
were +217015016 / −136942576 bytes; these opposite signs are not a proof of a
retained-heap leak. The exact candidate owns one empty 1048577-slot reference
array (about 4 MiB with compressed references), cleared every generation.
Inspect physical memory behavior before final production promotion; do not
claim the entire global delta as FerriteCore time saved.

**Segment disposition: retain.** Next bounded gate is physical owner/GC comparison
using the same frozen hosted-validated artifact, then a clean uninstrumented
production implementation with its safety catalog. No indefinite disabled
experiment is intended. Other candidate mechanisms remain separate decisions.

Artifacts: `C:/BootOptimBench/analysis-reload-20261001/ferrite-phase-36838525543`.
The physical controller now has an explicit `ferrite-phase` mode and Java-version
parameter (preserve the actual laptop's 21.0.9; no silent migration). It captures
rotated dated logs after owned JVM exit, excludes duplicate debug and stale
archives, checks exact lifecycle boundaries and restores original JAR/config.
Eight PS5 controller success/failure fixtures, rolled-log ordering/filter test,
path guards and 100-write atomic-state tests pass locally. These are controller
tests, not physical performance evidence.
