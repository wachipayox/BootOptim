# Post-bootstrap block-shape prewarm

Scope: Simulated, Create Propulsion: Simulated, Create: The Factory Must Grow,
Create Crafts & Additions and Create. On the client, five independent workers can
initialize these optional mods' audited shape tables after vanilla
Bootstrap returns. The worker calls the existing JVM initializer. Geometry
algorithms and the registry's own access to the published fields stay stock.

The final transformed initializer and reachable non-vanilla helpers must
match the method and type contracts in `shape-prewarm-contracts.tsv`.
The fingerprints cover the relevant code and hierarchy, without depending on
the mod version or whole JAR. Each affected class fails open independently.
A supported mod reports `[WBOMBO-COMPAT] ACTIVE` when its worker completes
and `FALLBACK` when the code contract cannot be verified.

Use `-Dboot_optim.shapePrewarm=false` to disable all shape workers or
`-Dboot_optim.allShapesPrewarm=false` to disable only Create's AllShapes
worker. The latter checks the final piston shape dispatch and the
FerriteCore state table before invoking the initializer.

The original four targets matched all 1,880,694 records in the desktop
shape differential. Hosted
exact-pack activated all four mechanisms and reached the menu with zero
fallbacks. A Windows Java 25+AOT ABBA reduced the median block-register
interval by 2.740 s and the median time to the visible menu by 2.419 s.
See [the prewarm research entry](../../research/block-shape-class-prewarm-2026-11-10.md)
for endpoints and raw observations. The separate
[AllShapes research entry](../../research/allshapes-ferritecore-2026-11-10.md)
records the piston audit, matching shape dumps, exact-pack result and ABBA
measurements for adding Create.
