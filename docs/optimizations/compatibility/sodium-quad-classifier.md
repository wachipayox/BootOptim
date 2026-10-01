# Sodium face-axis quad classification

Status: **PRODUCTION — ENABLED on the exact supported version/class**.
This replaces #299/#300's indefinite default-off position
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
before merging. Those gates are now complete as recorded below; do not retain
a separate disabled runtime experiment indefinitely.

## Completed clean runtime and visual gates

Clean source `cbc6ad5883b4f820f7c9f1c1875ece8568a1a3b1` passes packaged build,
absent-mod startup 36844294441 and exact-pack 36844294339 with actual exact
version activation, unchanged pack order, normal atlas and zero BootOptim Mixin
failures. No diagnostic capture/replay/timers are packaged.

On 2026-10-01 the user validated representative blocks, items and entities
in the usual fast-PC nooptim profile, before and after one F3+T, reporting no
visual anomaly. Collected closed-client log has exact activation
`BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active version=0.8.12-beta.1+mc1.21.1`,
two resource generations, zero resource-load/BootOptim Mixin failures, and
normal shutdown. Trial bootstrap SHA-256:
`defba2143d97353a6bebe75f6a192182657f9cb75c75d8320a4f6eca03611888`.
Owned trial JAR removed after closure; original config/options were not edited.
Local frozen artifacts/journal: `C:/BootOptimBench/artifacts/sodium-production-pc-20261001`.

Integration was refreshed to `9fbb4041b72c0191785c7b343781068b117577b5` and
the promotion rebased, preserving the final retirement/pixel-query ledgers.
Runtime source is identical to the visually validated source; the final
documentation refresh does not change the classifier. No physical laptop
world entry is required or claimed. This retains verified classifier CPU
removal under the segment-first policy, not a 19-second F3+T saving claim.
