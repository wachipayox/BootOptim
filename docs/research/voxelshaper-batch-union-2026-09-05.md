# VoxelShaper delayed-optimize / batch-union experiment — 2026-09-05

Status: **REJECTED**.

PR: [#106](https://github.com/wachipayox/BootOptim/pull/106)

Diagnostic premise: [#105](https://github.com/wachipayox/BootOptim/pull/105) localized CPU inside the exact-pack `minecraft:block` RegisterEvent and found the dominant active chain running through Ponder/Catnip `VoxelShaper.rotatedCopy -> Shapes.or -> Shapes.join -> VoxelShape.optimize -> Shapes.joinUnoptimized -> BitSetDiscreteVoxelShape.join -> LithiumDoublePairList.forMergedIndexes`. #105 was diagnostic only and is not modified by this experiment.

## Integration and workload provenance

The task started while `agent/integration-current` was `ad39b13824d71f6308050e8932f249dd18238923`. During experiment setup integration advanced to `f79c494ea1546286f9d42a8b87d1324badc5820a` through #103, which added the hosted resource-selection contract. The experiment branch was rebuilt directly on `f79c494ea1546286f9d42a8b87d1324badc5820a` before measurement.

This matters because #103 rejects a hosted exact-pack run when the fixture's pre-launch resource-pack selection/order differs after launch, when any effective `Reloading ResourceManager` loses/reorders an external `file/` ZIP, or when a resource-pack fallback is logged. Thus title reachability alone is no longer accepted as workload proof.

Fixture: `exact-pack-2026-09-02-v1`.

Measured code SHA: `26e6a36341ce858cab595db3d741fb6190c0e968`.

Hosted gates:

- Build #1416, run `33973894984`: PASS.
- Startup Benchmark #400, run `33973894987`: the first attempt hit an unrelated FML early-display / `UnionFileSystemProvider` failure before Minecraft or Ponder. Re-running failed jobs passed, final job `101327568735`. No experiment code was changed in response to the infrastructure failure.
- Exact Pack Startup Benchmark #177, run `33973894988`: benchmark job `101327182885` PASS; aggregate PASS.
- Exact artifact `exact-pack-result-smoke-1`: ID `9971781205`, digest `sha256:7c5e9dec37896c397c5ac8a8468300c24f9b2d2e7e57a5647bcc361c30a9dcff`.
- Exact resource-selection report: valid, no issues, one effective reload, exact pre/post selection match including ten external ZIPs in priority order. Final in-memory ResourceManager separately reported 260 packs, 10 external ZIP packs, 10/10 matched and zero missing.
- Smoke title time: 91.518 s; block atlas 8192×8192×2. This is a verifier smoke, **not** a performance A/B.

## Exact source mechanism

Create 6.0.10 pins Ponder 1.0.82. The relevant Ponder source is:

- [`VoxelShaper.java` @ `c3e5a41380203e1dd1e2431c494ec491a51965a5`](https://github.com/Creators-of-Create/Ponder/blob/c3e5a41380203e1dd1e2431c494ec491a51965a5/common/src/main/java/net/createmod/catnip/math/VoxelShaper.java)
- [`VecHelper.java` at the same source commit](https://github.com/Creators-of-Create/Ponder/blob/c3e5a41380203e1dd1e2431c494ec491a51965a5/common/src/main/java/net/createmod/catnip/math/VecHelper.java)

`VoxelShaper.rotatedCopy` enumerates the source shape's boxes. For each box it:

1. scales both endpoints by 16 and subtracts center `(8,8,8)`;
2. applies the existing `VecHelper.rotate` X, then Y, then Z arithmetic to both endpoints;
3. translates back by the center;
4. creates `Block.box(min/max)` from the two rotated endpoints;
5. accumulates with `Shapes.or(current, rotated)`.

The experiment did **not** reimplement or alter those rotations. It captured the already-created rotated `VoxelShape` at the existing `Shapes.or` callsite.

Minecraft's shape construction explains the CPU premise: `Shapes.or(a,b)` calls `Shapes.join(a,b, OR)`, and `Shapes.join` is `joinUnoptimized(...).optimize()`. `VoxelShape.optimize()` itself enumerates the current shape's occupied boxes and rebuilds them using `Shapes.joinUnoptimized(..., OR)`. Stock Ponder therefore performs an optimization after every rotated source box.

## Hypothesis

Preserve the same transformed-box stream and order but replace the repeated stock sequence

`Shapes.or -> joinUnoptimized -> optimize`

with

`Shapes.joinUnoptimized(..., OR)` for each box, followed by one final `optimize()`.

The intended gain was fewer intermediate optimization/join passes. This was deliberately not a cache, not shared mutable state across calls, not a deferred lifecycle operation, and not registry/event parallelism.

A conservative guard estimated an upper bound on the merged coordinate-grid cell count before each candidate fold. Default threshold: 262,144 cells. Crossing it would make that call fall back to stock `Shapes.or` for the remaining boxes.

## Verifier design

Property: `-Dboot_optim.voxelShaperBatchUnionVerifier=true`.

The verifier is an optional, fail-open `@Pseudo` Ponder mixin. It gates runtime applicability to Create `6.0.10` and Ponder `1.0.82` (allowing the actual `1.0.82+mc1.21.1` suffix). Injection requirements are zero.

For a natural Ponder `rotatedCopy` call:

- Ponder performs `forAllBoxes`, all `VecHelper.rotate` arithmetic, and `Block.box` exactly once.
- The redirect receives the already-created rotated box.
- The stock accumulator executes `Shapes.or` exactly once and that stock result is returned to Ponder.
- Separately, the verifier candidate consumes the same captured rotated box using the delayed-optimize algorithm.
- At `rotatedCopy` return, the candidate is finalized and compared, but **stock is always returned**.

Thus the verifier does not double-invoke Ponder's source-box callback or duplicate its rotations. Its cost is still intrusive because both stock and candidate shape algebra run in the same process; all internal timings below are diagnostic only.

### Equality contract

XOR/volume equivalence alone was explicitly insufficient. The comparator checks, in order:

1. same empty/non-empty state;
2. no XOR geometry through `Shapes.joinIsNotEmpty(stock, candidate, BooleanOp.NOT_SAME)`;
3. exact bounds double bits;
4. exact X/Y/Z coordinate-list sizes and coordinate double bits;
5. exact ordered `toAabbs()` / `forAllBoxes` decomposition and all six double coordinates per box;
6. same concrete `VoxelShape` representation class.

This strict contract matters because Minecraft operations can consume representation as well as occupied volume. `VoxelShape.findIndex` operates on coordinate lists; collision walks the discrete grid against those coordinates with epsilon offsets; `clip` ultimately consumes `toAabbs()`; and third-party callers can observe `forAllBoxes` decomposition/order.

Zero-rotation identity is preserved by not creating an experiment context when the Ponder rotation vector is exactly `Vec3.ZERO`. Empty sources also retain stock return behavior.

## Edge-case coverage

A separate verifier suite runs only **after** the TTMM marker. It calls the real Ponder public factories reflectively, so it exercises the mixin and Ponder implementation rather than a local rewrite.

Input shapes include:

- empty;
- one box;
- overlapping boxes;
- face-adjacent but non-rectangular boxes;
- coordinates outside the unit cube;
- negative coordinates;
- a dimension just above `Shapes.EPSILON` (`1e-7`) with no normalization or snapping added by BootOptim.

Factory/orientation coverage includes:

- all six base directions of `forDirectional`;
- all four horizontal base directions of `forHorizontal`;
- all three axes of `forAxis`;
- all three axes passed to `forHorizontalAxis` exactly as the public API accepts them;
- `withVerticalShapes`;
- identity checks that the factory's no-rotation orientation returns the original input object.

Result: 119 factory cases generated 385 verifier calls; **385/385 strict matches**, zero mismatches, zero fallbacks, zero identity failures and zero reflection failures.

This passing synthetic suite was not sufficient to authorize the candidate: the natural pack exposed inputs not represented by these small cases.

## Natural exact-pack verifier result

The real pack produced:

| Metric | Value |
| --- | ---: |
| `rotatedCopy` calls under verifier | 12,640 |
| Rotated boxes | 29,055 |
| Verified calls | 12,640 |
| Strict matches | 12,553 |
| **Mismatches** | **87** |
| Pure delayed-batch calls | 12,640 / 100% |
| Guard fallbacks | 0 |
| Maximum predicted intermediate grid | 6,498 cells |
| Guard threshold | 262,144 cells |

The first bounded mismatch records include:

- `coord_count_x` with 16 boxes;
- `coord_bits_z_1` with 16 boxes;
- `coord_bits_z_1` with 8 boxes;
- `coord_bits_z_2` with 6 boxes;
- `coord_bits_z_1` with 7 boxes;
- `coord_count_x` with 28 boxes;
- `coord_bits_x_3` with 36 boxes.

Because the comparator tests XOR geometry and bounds before these coordinate checks, these retained examples reached the coordinate-representation stage rather than failing those earlier tests. They are therefore concrete evidence that the delayed-optimize construction is not representation-equivalent to stock for real Ponder shapes.

The guard cannot explain the failures: all 12,640 calls stayed below the threshold and there were zero fallbacks. Lowering or raising that guard does not change the core semantic result for the affected calls.

## Internal cost observation

Verifier-internal totals for the natural pack:

| Work | Wall | Current-thread CPU |
| --- | ---: | ---: |
| Stock per-box `Shapes.or` folds | 6,956.663 ms | 6,361.358 ms |
| Candidate unoptimized folds | 155.571 ms | 188.762 ms |
| Candidate final `optimize()` | 157.002 ms | 171.373 ms |
| Strict comparison | 32.986 ms | 46.746 ms |

Candidate construction (`fold + final optimize`) was therefore dramatically cheaper inside this verifier and the grid stayed modest. This is useful evidence that the repeated intermediate optimizations identified by #105 are expensive.

It is **not** an estimate of TTMM savings: stock and candidate ran back-to-back in one instrumented process, comparison added more work, and no normal candidate/control run was executed.

## Why the first mechanism loses

The experiment fails the mandatory semantic gate, not the CPU premise.

Stock Ponder repeatedly performs `joinUnoptimized(...).optimize()` as boxes arrive. A final-only optimize allows a different intermediate coordinate grid and occupied-box decomposition to survive until the final reconstruction. The real inputs include float-derived rotated coordinates; the shape mergers also use epsilon-sensitive comparisons. The exact pack demonstrates path dependence in the resulting coordinate representation even though the tested small edge cases did not.

Those coordinate/decomposition differences cannot be dismissed as cosmetic. Minecraft collision indexing and clip/raycast behavior depend on shape coordinates / generated AABBs, and arbitrary mods may consume `forAllBoxes`. BootOptim's scope forbids trading startup time for even a plausible gameplay/collision representation change without a stronger equivalence proof.

The verifier returned stock throughout the smoke, so no candidate gameplay behavior was actually shipped or exercised.

## Decision

**REJECTED before A/B.**

The protocol required zero exact-pack mismatches before enabling the normal same-JAR candidate. The verifier produced 87 natural-pack strict mismatches, so:

- no candidate/control hosted 3×3 was launched;
- no sampler comparison was substituted for A/B;
- no physical laptop or collision/gameplay gate is requested;
- no production promotion is proposed.

The existing candidate-mode code remains experiment scaffolding only in PR #106 and must not be merged as production.

## Reopening criteria

Do not repeat “all `joinUnoptimized`, then one final `optimize`” merely because its internal CPU total is attractive. A new experiment requires a materially different premise that can preserve the exact stock coordinate/decomposition representation for the affected real inputs, or a source-level proof that a deliberately relaxed representation contract is unobservable across Minecraft collision/clip and the pack's relevant consumers followed by an appropriate gameplay equivalence gate.

Changing only the grid threshold, adding a global cache, parallelizing registration, changing Lithium/Palladium behavior, or accepting XOR-only equivalence does not satisfy the reopening criterion.
