# FerriteCore bounded empty quad-table storage

Status: clean production promotion; final physical indirect-cost gate pending.
Not yet present on integration. Policy #307 replaces the earlier indefinite
default-off posture of #298, based on actual-owner evidence from #308.

FerriteCore 7.0.3 clears its canonical quad set and trims it to its initial
minimum after every resource generation. This change preserves the original
clear, then retains only bounded empty capacity for the next generation.
Initial insertion/baking is unchanged; this benefits subsequent resource
reloads, not the first cold expansion. No persisted cache or quad data.

Exact version 7.0.3 and exact `ObjectOpenCustomHashSet` class are required.
Unknown versions/subclasses, absent target, nonempty set or missing prior count
use the original trim. Original hash/equality, `addOrGet`, canonical representative
identity, lock ownership and clear behavior remain unchanged. At most 2^21
reference slots plus the null slot survive trim; about 8 MiB with compressed
references at the maximum, normally 4 MiB for the pinned pack's 1048576 slots.
Every key is cleared, including the null slot. A failed bounded trim falls back
to stock trim. No OpenGL, extra worker, timer, diagnostic subclass or trial mask.

Enabled by default only on the guarded path. Stock fallback kill switch:
`-Dboot_optim.ferriteCoreQuadCapacity=false`. One fixed cleanup marker records
the prior unique count and trim result; it does not clock insertions.

## Evidence and gate

Build-only audit against actual fastutil verifies 420000 canonical representative
identities across varying generations, all references empty after clear, and an
oversized table shrunk to the storage bound. It is never in the packaged JAR.

Diagnostic [#308](https://github.com/wachipayox/BootOptim/pull/308), exact-pack
[36838525543](https://github.com/wachipayox/BootOptim/actions/runs/36838525543):
two controls/two candidates, same-mode unmeasured primers, actual growth/clear/
trim intervals. Controls made 19 growth rehashes, candidates zero. Net owner
CPU saved 175.746/138.570 ms. Unique workload spread 0.0171%, clear/trim cost
unchanged. Global reload and GC contrasts are adjacent evidence, not time
attributable entirely to this mechanism. Diagnostic dispatch/class changes
are excluded from this clean implementation.

Physical campaign `ferrite-phase-20261001` is checking owner CPU and indirect
memory/GC on the existing Oracle21.0.9 HDD laptop; no world entry. Do not merge
until that result and uninstrumented package/startup/exact-pack gates are read.
Final semantic/visual gate remains distinct from CPU removal evidence.
