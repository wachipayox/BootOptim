# FerriteCore late quad presizing

Status: **CLEAN PROMOTION, final runtime/physical gates pending — not integrated**.
This is the bounded salvage of the retired persistent-empty-storage #298/#310.
Do not merge the profiler from #315 or retain an indefinite experimental lane.

Exact FerriteCore7.0.3 clears and trims its canonical quad table after loading.
Capture only its previous unique-count integer at original clear, bounded to
2^20. Keep stock clear and zero-argument trim exactly. First next insertion,
under FerriteCore's original mutex, consumes the hint and invokes stock public
fastutil8.5.12 ensureCapacity. Original addOrGet and setVertices always execute
in original order, preserving canonical identities/hash/equality/callbacks.
No model/vertex references or large empty table survive through preparation.

Default on for the exact version and exact stock set class; kill switch
`-Dboot_optim.ferriteCoreQuadPresize=false`. Unknown/absent versions, custom or
nonempty table, missing typed callsite or zero hint use stock. Allocation failure
consumes the hint and runs original insertion; inspected rehash publishes table
fields only after allocation/filling. No replacement set, altered mutex, new
threads, GL, weak hash, reflection or callback skipping. Existing generation
ordering/immutable-key assumptions remain FerriteCore's original contracts.
The pending volatile count is consumed once under stock insertion monitor;
cleanup retains stock barrier ordering and does not add locks. Maximum reserved
array is2^21+1slots; this is the live bake table, trimmed again after loading.
Initial cold bake has no preceding count and is not sped up by this mechanism.

## Evidence and bounds

Diagnostic #315 exact-pack36880289246 passed nine generations and two controls/
two candidates with same-mode primers. Both measured arms have1,250,596
insertion calls and479823..479905unique quads. Insertion+clear+trim CPU saved
225.986303/165.729526ms, including new check and first reserve. Growth19→1
is nested inside insertion and not summed a second time. Absolute CPU includes
identical observer overhead; these are not uninstrumented budgets or startup
seconds. Whole reload−485.863/+477.837ms is mixed; GC−262/−152ms and endheap
+542071632/+378881832bytes remain adjacent observations. No automatic leak or
attribution of all global delta: both final arrays are one slot/no keys.

Build-only actual fastutil test covers160000canonical identities across changed
generations, nulls/collisions, empty references/original trim, bounded count/
allocation and disabled/custom/nonempty fallback. Runtime carries only fixed
support/remember/reserve markers once per generation, no timers, replay driver,
trial mask, measured subclass or counters. Exact-pack smoke must validate actual
cleanup/default activation; first subsequent-reserve/semantic and physical
memory/GC gates remain required. Do not silently equate a one-generation smoke
with having executed the subsequent-reload reserve.

Source base refreshed to214293eb600f4a0eefbb68fd74042bac9158b1c2 including
production Sodium and the final retirement ledger. Prior old-laptop result
confirms growth CPU matters but does not validate this altered memory lifetime.
Never send high-volume per-insertion CPU clocks to the laptop as its baseline.
