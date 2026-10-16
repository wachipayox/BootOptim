# Decocraft whole-bake staging

Status: **INTEGRATED**, default ON on PC (decision relayed by the coordinator session, 2026-10-16; kill switch kept). Migrated from Android Support `824b37d` (`memory.decocraftBakeScratch`). Ledger: `docs/research/decocraft-support-migration-2026-10-16.md`. Shares the code-shape contracts and ownership rule of [decocraft-compact-structures](decocraft-compact-structures.md).

## Mechanism

`BlockbenchBakery.bakeQuad` builds four `VertexData` temporaries (five floats each) and, per vertex, a selected-position `Vector3`, then copies everything into the final `int[]` of a new `BakedQuad`. While one `bakeQuad` runs on the owning thread, `DecocraftBakeScratch` hands out the same four `VertexData` (by loop index) and the same position vector for the next quad instead of allocating them:

- `@Inject` at `bakeQuad` HEAD/RETURN opens and closes a lease (depth 1). The first baking thread owns the scratch of that bakery instance; other threads take the original allocations.
- `@WrapOperation` on `new VertexData()` in `makeVertexData` (loop index = local 16, pinned by the exact method-body contract) and on `selectPosition` in `bakeVertex`.
- Kept fresh and independent per quad: the `BakedQuad`, its 32-int payload and the array of four vertex references. Position values are written with raw float bits in the original order.
- A re-entrant or interrupted `bakeQuad` (depth ≠ 1) or an unexpected `VertexData` (exact name and fields `x y z u v` as floats) / `Vector3` shape disables the mechanism: `[WBOMBO-COMPAT] FALLBACK ... mechanism=decocraft-bake-scratch`.

Mixin priority 1100 keeps the original-body check of `calculateFacing` ahead of the private-direction mixin.

## Guard

Nine fingerprints (`selectPosition`, `bakeQuad`, `makeVertexData`, `bakeVertex`, `fillVertex`, `calculateFacing`, `VertexData.<init>`, `Vector3.<init>(FFF)`, `Vector3.set(FFF)`): the producers and the single consumer that copies before the next element. A changed body, missing class, change before apply or missed injector disables all coupled hooks together. Kill switch: `-Dboot_optim.decocraftBakeScratch=false`. A legacy Support carrying the same mixin on Android takes precedence (`legacy_support_owns_mechanism`).

## Evidence and limits

- Mobile (Vivo/Java 21), whole-bake windows: needed final output 192 B/quad; total 448 → 224 B/quad (−50 %); 224 B/call avoided; ~87.5 % of the temporary gap. Original-output comparison on 2,000 complete quads and 2,632 checks over 12 variants passed. **Temporary allocation only**: no retained-memory, CPU or FPS claim (the JIT timings of those windows do not prove CPU gain). Do not multiply by accumulated counters of another population.
- Host contract (`./gradlew check`): lease, cross-thread, re-entrant and wrong-shape fallbacks, 50,000 raw-bit positions, every contract method has a fingerprint. Offline: `./gradlew verifyDecocraftContracts -PdecocraftJar=<installed JAR>` matches all 36 fingerprints on Decocraft 3.0.11; the local index 16 is the vertex loop counter in that bytecode.
- PC: hosted smoke checks menu, `ACTIVE`, no `FALLBACK`; it does not measure allocation. Reopen for a PC figure with JFR allocation-by-site on the pack.
