# Post-bootstrap block-shape prewarm

Scope: Simulated, Create Propulsion: Simulated, Create: The Factory Must Grow
and Create Crafts & Additions. On the client, four independent workers can
initialize these optional mods' audited shape tables after vanilla
Bootstrap returns. The worker calls the existing JVM initializer. Geometry
algorithms and the registry's own access to the published fields stay stock.

The final transformed initializer and reachable non-vanilla helpers must
match the method and type contracts in `shape-prewarm-contracts.tsv`.
The fingerprints cover the relevant code and hierarchy, without depending on
the mod version or whole JAR. Each affected class fails open independently.
A supported mod reports `[WBOMBO-COMPAT] ACTIVE` when its worker completes
and `FALLBACK` when the code contract cannot be verified.

Kill switch: `-Dboot_optim.shapePrewarm=false`.

The desktop shape differential matched all 1,880,694 records. Hosted
exact-pack activated all four mechanisms and reached the menu with zero
fallbacks. A Windows Java 25+AOT ABBA reduced the median block-register
interval by 2.740 s and the median time to the visible menu by 2.419 s.
See [the prewarm research entry](../../research/block-shape-class-prewarm-2026-11-10.md)
for endpoints, raw observations and why Create AllShapes is outside scope.
