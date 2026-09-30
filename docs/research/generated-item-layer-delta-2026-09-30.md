# Generated-item per-layer expansion arithmetic

Base: freshly verified integration `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
Scope: tiny default-off candidate, not a claimed reload win.

The retained direct generated-item baker computes
`(1.0F - seamExpand) * 0.01F` inside each Trimmable Tools side-face branch.
`seamExpand` is assigned once per layer and never modified. The candidate
evaluates these same float operations once per layer and uses that result
in the unchanged side-face branch. Java float operations and parenthesization
remain identical, including signed zero, infinities and NaN behavior.
No reassociation, FMA or precision conversion is introduced. An unused pure
float calculation on non-Trimmable layers has no observable side effect.

Flag: `-Dboot_optim.generatedItemLayerDeltaHoist=true`, default false.
The production parent `generatedItemDirectBake` must remain enabled.
There is no retained cache, allocation, invalidation, thread or scheduling
change. Every virtual `sprite.contents().name().getNamespace()` read stays
in its original per-face position, as do transparency queries and all
FaceBakery calls. The original namespace-hoist suggestion was narrowed:
moving those virtual reads would need an additional callback-invariance
proof. The flag selects only the pure arithmetic change.

Historical context: PR #62/#64 promoted the direct baker after 14,865 stock
equivalent models, including Trimmable Tools. Its ~995,754 side spans are the
**total across namespaces**, not the count of matching Trimmable faces or
an estimate of arithmetic savings. PR #57 rejected a repeated-span cache;
this candidate does not add one. The JIT may already eliminate some of the
arithmetic. No exclusive CPU or end-to-end saving is established.

Local `gradlew.bat build --no-daemon` passed and produced the packaged
`bootstrap/build/libs/boot_optim-bootstrap-0.1.0.jar`. Source equivalence is
limited to this two-operation hoist; do not claim new exhaustive model or
visual validation from a green compile. Enabled exact-pack smoke passed
as recorded below. Physical timing/visual checks stay pending in the
[candidate register](express-candidates-laptop-register-2026-09-30.md).

## Completed runtime gate

[Exact-pack smoke 36721846567](https://github.com/wachipayox/BootOptim/actions/runs/36721846567)
passed on commit `f940cad8` with `generatedItemLayerDeltaHoist=true` in the
benchmark JVM arguments. Build and minimal client-startup gates also passed.
The actual resource-selection check is valid, with no issues, and the expected
enabled pack order matches exactly. The block atlas remains 8192x8192x2,
BootOptim Mixin errors are zero and no direct-baker fail-open warning appears.
Startup marker to title is 89.223 seconds; mod entrypoint is 30.860 seconds,
post-entrypoint 58.363 seconds. These are one enabled hosted run, not an A/B,
not physical F3+T and not an estimate of time saved. There is no per-layer
activation counter or exhaustive quad comparator in this branch, so a healthy
runtime does not constitute a new exhaustive semantic proof.

Disposition: keep the small candidate default off and unpromoted; source
arithmetic equivalence and enabled runtime gate pass. Timing and physical
visual evidence remain missing. Preserve it in the future low-end register,
without using the laptop now or rerunning unchanged smoke for a speed claim.
Artifacts: `C:\BootOptimBench\analysis-reload-20260930\generated-layer-36721846567`.

Pixel-query reuse was separately completed in commit `6c2feba0`; 5,562
ordered-topology cases pass but a callback counterexample changes 12 queries
to 4. It is express NO-GO without a reliable purity guard, not an implementation
included here. Do not promote callback skipping alongside this arithmetic change.
