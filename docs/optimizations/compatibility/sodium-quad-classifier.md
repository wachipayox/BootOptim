# Sodium face-axis quad classification

Status: clean production promotion; uninstrumented runtime/visual gate pending.
Not yet integrated. This replaces #299/#300's indefinite default-off position
under policy #307; #309's capture/replay/trial logic is not part of production.

Sodium's quad flag function calculates coordinate extrema on all three axes.
For the face-normal axis, test equality with stock [-32,32] sentinel bounds
instead, while retaining stock min/max on both tangent axes. The result remains
the same partial/parallel/aligned flags. Twelve virtual coordinate reads remain
in original vertex/X/Y/Z order, including their exception behavior. NaN and
out-of-range planes preserve stock comparisons; no negated NaN predicate.

Scope: exact `BakedQuad` class and Sodium version `0.8.12-beta.1+mc1.21.1`.
Unknown versions/classes, absent bridge, null face/quad or disabled property
delegate to the original method. The optional interface bridge declares only
the existing float getX/getY/getZ descriptors, with no Sodium hard dependency.
No cache, model lifetime change, callbacks skipped, threads, GL or scheduling.
`-Dboot_optim.sodiumAxisQuadFlags=false` restores original classification.

## Evidence and limits

Independent installed-binary audit #299 checks 2410296 results plus getter order
and 72 exception cases against the pinned Sodium class. Build-only harness in
`scripts/sodium-quad-classifier-audit` can rerun this against an exact supplied
Sodium JAR; it is not packaged. The bridge's actual JVM/Mixin descriptor proof
and absence case are separate compatibility evidence.

[Actual transformed-owner replay #309](https://github.com/wachipayox/BootOptim/pull/309)
and [exact-pack run 36841073385](https://github.com/wachipayox/BootOptim/actions/runs/36841073385)
sampled 3069 real pack quad references with their original directions. Flags
equal before timing; two control/two candidate blocks of 7997814 calls each,
same checksum21903430, after common warmup. CPU C1/B1/B2/C2:
422.178/360.310/359.052/421.993 ms. Opposite-order savings61.868/62.940ms
(14.65%/14.92%); no GC during blocks. This is warmed actual-method cost, not
reload-wall savings, not a prediction of startup seconds or cold JIT benefit.

Whole-bake/whole-reload variation in #300/#304 is not used to reject this
verified owner CPU reduction. The diagnostic subclass/capture/MethodHandle/
trial masks are absent here. Uninstrumented package, absent-mod startup,
exact-pack runtime and representative in-world visual validation are required
before merging. Keep this promotion bounded; do not retain a separate disabled
runtime experiment indefinitely.
