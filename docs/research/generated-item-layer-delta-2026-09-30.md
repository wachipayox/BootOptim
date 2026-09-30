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
visual validation from a green compile. Enabled exact-pack smoke is the
next runtime gate. Physical timing/visual checks stay pending in the
[candidate register](express-candidates-laptop-register-2026-09-30.md).

Pixel-query reuse is a separate subagent investigation and is not included
here. Do not silently promote any callback-skipping implementation alongside
this arithmetic change.
