# FerriteCore bounded empty quad-table capacity

Status: **LIMITED DEFAULT-OFF EXPERIMENT — no consistent hosted speedup; not promoted**

Base authority refreshed: `agent/integration-current` at `a0b8fdc`.
No open/closed BootOptim PR specific to FerriteCore quad storage was found;
#36's model identity cache and #186/#188 model-map sizing are different
mechanisms. This experiment includes #297's opt-in hosted repeat-reload
diagnostic to exercise generation transitions; neither component is ready
for production merge.

## Confirmed mechanism

The actual installed FerriteCore 7.0.3 bytecode uses a static fastutil
`ObjectOpenCustomHashSet<int[]>` with the default 0.75 load factor. Its
canonicalization uses the stronger per-int Murmur hash and array equality.
`Deduplicator$1.apply` runs after model loading, clears all cache contents,
then calls `BAKED_QUAD_CACHE.trim()`. The next model reload grows this empty
table from its minimum again. The physical first-F3+T JFR has 21 bake-window
samples with the hash function under `rehash`, plus ordinary insertion/hash
samples. Sample counts are not exclusive CPU time or a savings estimate.

Source: [FerriteCore 1.21.1 Deduplicator](https://github.com/malte0811/FerriteCore/blob/1.21.1/Common/src/main/java/malte0811/ferritecore/impl/Deduplicator.java).
The source explicitly rejects the weaker plain Arrays hash due collisions;
this experiment preserves the original hash, equality and canonicalization.

## Candidate and safety boundary

`-Dboot_optim.ferriteCoreQuadCapacity=true` is default-off and enabled only
for FerriteCore 7.0.3. The optional mixin targets the exact typed anonymous
apply method. It records the preceding unique-quad count, executes original
`clear()` once in place, and replaces only the immediately following
zero-argument trim with stock `trim(min(previousUnique, 2^20))`.

Only an empty, exact `ObjectOpenCustomHashSet` is eligible. Unknown versions,
missing targets, nonempty/custom sets and allocation failure use stock trim.
Other caches, ordering, synchronization, hashing, model results, GL and
thread ownership are unchanged. **No vertex arrays or baked models survive
the original clearing point.** Only empty reference-table allocation may
remain: at the verified load factor, at most 2^21 reference slots plus one
null slot (about 8 MiB with 4-byte references, 16 MiB with 8-byte references,
plus array/object headers). Larger preceding tables shrink to that bound.
Small/empty generations shrink accordingly; this is not an unbounded peak
capacity or content cache. Ordinary hash-set key immutability remains the
same assumption as FerriteCore's existing deduplication.

The tradeoff is retained empty heap storage in exchange for fewer later
rehashes. It does not accelerate initial model loading, avoid quad hashing
or address the whole 17–19-second request wall. Low-memory GC behavior must
be measured, especially before any laptop promotion.

## Validation

Local packaging passes. `tools/reload-bench/FerriteCapacityEquivalence.java`
runs against exact fastutil 8.5.12 and compares 420,000 insertions across
three changed-content generations. Control/candidate return the identical
first canonical array object on every insertion, clear removes every key
reference, and an oversized empty table shrinks to the bound. It observed
44 control versus 13 retained-table rehashes. These are fixture test counts,
not actual pack activation or measured speed. Optional Mixin application,
actual unique counts and repeat wall remain pending hosted exact-pack smoke.

Next gate: candidate smoke with two stock menu reloads, expected three
successful capacity markers, unchanged selected pack order and atlas,
strict #297 repeated-future contract and no new Mixin errors. Only then
request paired hosted economics; any positive result still needs physical
in-world F3+T and representative visual/GC validation. The user instance and
laptop remain untouched.

## Hosted smoke passed

[Run 36690784816](https://github.com/wachipayox/BootOptim/actions/runs/36690784816)
passes startup and two same-JVM repeat reloads with the original resource
selection and 8192x8192x2 block atlas. All three capacity markers are
successful: preceding unique counts 479,905 / 479,878 / 479,905. At this
size the retained empty backing table has 2^20 slots, below the worst-case
bound. The strict repeat future contract passes and there are zero
BootOptim Mixin failures. This proves actual target application and
generation transition under hosted runtime, not visual equivalence.

Repeat request-to-future walls are 31.085 and 28.692 seconds. These must
**not** be compared with #297's 25.149 / 22.077 values: they come from
different hosted VMs and initial startup itself is much slower here (92.320
versus 67.885 seconds), before capacity retention could benefit a repeat.
The smoke supplies no economic delta. Artifacts are retained at
`C:/BootOptimBench/ferrite-quad-capacity-20260930/smoke/`.

Next gate is three same-VM control/candidate process pairs, alternating
order, with two reloads in each process. Compare corresponding repeat
ordinals separately; retain the individual deltas, not only a median.
Both sides have identical sparse telemetry and diagnostic settling.
Candidate differs only by `ferriteCoreQuadCapacity=true` versus false.
No physical staging or production promotion is authorized by smoke alone.

## Three paired repeat comparisons completed

The initially linked run 36691743452 was cancelled when a replacement of
the same head was launched. Its partial artifacts are retained separately
at `paired-cancelled/` and are excluded from comparison.
[Completed run 36691964405](https://github.com/wachipayox/BootOptim/actions/runs/36691964405)
on `b5514af8` is authoritative: all six processes and twelve repeats pass
resource selection, atlas, strict request/future boundaries and zero
BootOptim Mixin failures. All nine candidate capacity markers succeed,
with preceding unique counts between 479,823 and 479,905.

Candidate-minus-control repeat wall deltas in milliseconds:

| Pair | Process order | Repeat 1 | Repeat 2 |
| --- | --- | ---: | ---: |
| 1 | control then candidate | -1747.841 | +1003.863 |
| 2 | candidate then control | +361.038 | -171.663 |
| 3 | control then candidate | +136.220 | +502.319 |
| Within-pair median | | +136.220 | +502.319 |

The isolated first-repeat improvement does not repeat, and the second
repeat is slower in two pairs. Startup deltas are also positive
(+3183/+1282/+2568 ms), including +924/+1086/+753 ms before mod entrypoint.
Capacity reuse cannot explain that pre-entrypoint difference; do not claim
all observed startup difference as a causal mechanism regression. Runner
conditions vary substantially between pairs, so subtracting variant-wide
medians would also manufacture a different inference from paired deltas.

This does not establish a consistent time reduction. No physical F3+T or
laptop speedup/zero-effect claim is made, and no GC-equivalence claim is
inferred from successful reloads. Artifacts are under the adjacent
`paired/` directory. Keep the candidate default-off and unpromoted; do not
ask for another unchanged hosted comparison without a new premise.

Explicit future low-end checklist: include capacity true/false in a paired
repeat-reload/GC comparison when the user authorizes the laptop campaign.
Heap/GC sensitivity is the remaining distinct physical premise; neither
the experimental code nor that pending check should be forgotten. The
current evidence does not warrant installing it in the user's active PC
instance. A targeted proof that rehash CPU or GC dominates a physical
reload can reopen the candidate; the complete FerriteCore/model bake
subsystem is not closed by this limited storage result.
