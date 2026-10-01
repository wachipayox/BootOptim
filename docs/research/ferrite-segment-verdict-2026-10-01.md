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

No new performance result is claimed until the actual pack trial completes.
