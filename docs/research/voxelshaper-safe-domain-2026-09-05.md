# VoxelShaper limited safe-domain investigation — 2026-09-05

Status: **ACTIVE DIAGNOSTIC / DO NOT MERGE**

Branch: `codex/research-voxelshaper-safe-domain`

Base: refreshed `agent/integration-current` at `792d06ec008c5ebae3681dd94f7aeee2c8e5f2a2`.

This work deliberately does **not** reopen the generic delayed-optimize construction rejected by PR #106. It asks whether a source-level precondition can prove strict stock representation equivalence for a limited subset of the exact Ponder 1.0.82 `VoxelShaper.rotatedCopy` inputs, and how much stock fold CPU actually belongs to that subset.

## Prior evidence

PR #105 localized active CPU inside exact-pack `minecraft:block` registration to the Ponder/Catnip chain `VoxelShaper.rotatedCopy -> Shapes.or -> Shapes.join -> VoxelShape.optimize -> Shapes.joinUnoptimized -> BitSetDiscreteVoxelShape.join -> LithiumDoublePairList.forMergedIndexes`. Its 7.168 s target-thread CPU is attribution evidence, not a savings estimate.

PR #106 preserved Ponder's already-created rotated-box stream but replaced every stock per-box `Shapes.or` with `joinUnoptimized` plus one final `optimize`. Its natural exact-pack verifier found 12,553 strict matches and 87 mismatches over 12,640 calls. The candidate was rejected before A/B. The first mismatch labels identify the comparator stage only; they do not establish a cause. The same PR's generic guard also had two source-review defects (a disjoint-grid upper-bound underestimate and non-reentrant single ThreadLocal state), neither of which explained the observed 87 mismatches because no runtime fallback occurred.

This branch uses neither generic candidate mode nor that grid-size guard.

## Exact source mechanism

Runtime versions:

- Create `6.0.10`, Ponder `1.0.82`;
- Ponder source `VoxelShaper.java` / `VecHelper.java` at `c3e5a41380203e1dd1e2431c494ec491a51965a5`;
- Lithium `0.15.3+mc1.21.1`, source commit `09d115dc18acc978b281107e9d02e5d043a0c20f`.

`VoxelShaper.rotatedCopy` enumerates stock source boxes exactly once, applies Ponder's existing X/Y/Z `VecHelper.rotate` arithmetic, creates each rotated shape with `Block.box`, then folds it with `Shapes.or`.

Minecraft 1.21.1 `Shapes.box/create` first applies the `EPSILON=1e-7` empty test. `findBits` recognizes only unit-cube endpoint pairs that lie within epsilon of uniform grids of resolution 1, 2, 4 or 8. Such a box becomes `CubeVoxelShape`; otherwise it becomes an `ArrayVoxelShape` retaining the supplied endpoint doubles. `Shapes.join` is `joinUnoptimized(...).optimize()`.

`VoxelShape.optimize()` greedily enumerates the occupied boxes of the current discrete shape and rebuilds them via `Shapes.joinUnoptimized`. `BitSetDiscreteVoxelShape.forAllBoxes(..., true)` scans in a fixed y/x/z order, extends a contiguous z strip, then matching x strips, then matching x-z rectangles in y, clearing each emitted prism before continuing.

Lithium replaces vanilla `IndirectMerger` with `LithiumDoublePairList`. Its merge compares coordinates using `1e-7` and coalesces points within that epsilon. This is the key reason that merely observing geometrically equal unions is not enough for arbitrary float-derived coordinates.

## Proposed precondition, before exact-pack measurement

The diagnostic classifies a non-zero `rotatedCopy` call as eligible only in either of two source-proven domains.

### Domain A: at most two stock-created rotated boxes

This domain does not constrain the coordinate values.

For one rotated box `b`, stock computes:

`Shapes.or(empty, b) = joinUnoptimized(empty, b).optimize() = b.optimize()`.

Because `b` is the direct result of stock `Block.box -> Shapes.box`, `b.optimize()` enumerates that single box's exact bounds and calls the same `Shapes.box` on the same double values. Empty, cube and array representations therefore reconstruct strictly identically.

For two boxes, the first stock accumulator is thus strictly identical to the first box. Both stock and delayed construction then feed strictly identical operands to the second `joinUnoptimized` and perform the same final `optimize`. Hence the final class, coordinate lists, ordered decomposition and geometry are identical.

### Domain B: three or more boxes, and every actual rotated box is exactly `CubeVoxelShape`

The guard observes the already-created Ponder `rotatedBox`; it does not inspect source asset IDs and does not round or normalize any coordinate.

At this exact callsite, a `CubeVoxelShape` can only be the result of the immediately preceding stock `Block.box -> Shapes.box`. Therefore `Shapes.findBits` has already established that each effective axis lies on a stock uniform dyadic grid of resolution at most 8. After stock construction, all effective coordinates are exact multiples of `1/8` (or a coarser nested grid) inside the unit cube.

Consequences:

1. distinct effective coordinates are separated by at least `1/8`, far larger than `EPSILON=1e-7`;
2. equal dyadic coordinates have identical double representations;
3. Lithium's epsilon merge cannot collapse two distinct physical cuts or choose between near-but-different representatives in this domain;
4. every intermediate coordinate grid is therefore an exact refinement/coarsening of the same 1/8 physical lattice, never an epsilon-shifted lattice.

For the same union geometry, `BitSetDiscreteVoxelShape.forAllBoxes(..., true)` is invariant under such pure refinement. The first occupied physical position is unchanged; inserted cuts inside a filled run cannot terminate its z extension, x-strip extension or y-rectangle extension. The emitted maximal physical prism therefore has the same physical bounds. Clearing that prism preserves the refinement relation, so induction gives the same ordered physical box sequence. `VoxelShape.optimize()` then feeds that same ordered bound sequence into deterministic `Shapes.box` / `joinUnoptimized`, yielding the same strict final representation.

This is a falsifiable proof claim, not a pack whitelist. Any eligible strict mismatch invalidates the domain and closes it.

### Deliberately rejected by the guard

For 3+ boxes, any actual stock-created rotated box that is not exactly `CubeVoxelShape` is rejected. That includes the general float-derived `ArrayVoxelShape` case where two coordinate streams can contain distinct values inside the merger epsilon and intermediate optimize can change which representative survives. Numeric proximity to a dyadic value is not accepted: stock `Shapes.box` itself must already have canonicalized the box.

## Diagnostic design

Property:

```text
-Dboot_optim.profileVoxelShaperSafeDomain=true
```

The optional `@Pseudo` mixin is version-gated to Create `6.0.10` and Ponder `1.0.82(+suffix)` and all injections are fail-open.

During real startup:

- Ponder executes `forAllBoxes`, all rotations and `Block.box` exactly once;
- each stock `Shapes.or` executes exactly once and is always returned;
- only that stock fold body gets its own wall/current-thread-CPU timer;
- the domain guard/class check and bounded reference capture run after the stock-fold timer;
- no candidate shape algebra runs before the main-menu timestamp;
- per-call state uses a ThreadLocal stack, so nested `rotatedCopy` does not destroy an outer context;
- capture is bounded to 512 boxes/call, 16,384 calls and 50,000 boxes globally, with explicit dropped counters;
- zero-rotation calls retain stock source identity and are counted separately.

After the semantic main-menu timestamp, the diagnostic replays captured boxes with the #106 delayed construction solely as a verifier. It checks, in order: empty state, XOR geometry, exact bounds bits, exact X/Y/Z coordinate counts/bits, exact ordered `toAabbs()` bits, and exact concrete shape class. Stock remains the runtime result.

For the first four mismatches only, post-TTMM logging records numeric source boxes, rotation bits, rotated-box bounds, stock/candidate coordinate grids and ordered AABBs. It also reconstructs stock and delayed prefixes to identify the first prefix at which `delayed.optimize()` ceases to be strictly identical. No asset path, registry ID, pack filename or private resource content is logged.

## CPU / wall accounting

The summary separates:

- total stock fold CPU/wall;
- eligible stock fold CPU/wall;
- rejected stock fold CPU/wall;
- eligible stock CPU/wall share;
- measured guard CPU/wall (outside the stock-fold timer);
- total per-call CPU as an observer-effect context metric;
- post-TTMM candidate fold/final-optimize/strict-comparison CPU and wall.

The candidate replay numbers are not TTMM savings. They run after title and exist only to quantify the construction's internal cost after the correctness/coverage split. The stock eligible share is the relevant CPU ceiling for deciding whether a later candidate is worth an A/B.

## Adversarial gate

After TTMM the same strict comparator covers:

- zero, one and two boxes with deliberately noncanonical/outside-unit values (must stay eligible under the <=2 proof);
- disjoint, adjacent, overlapping, containing and slab/cross patterns built from exact 1/8-grid stock `Shapes.box` values;
- 512 deterministic 3-12-box sequences drawn from a stock-confirmed `CubeVoxelShape` pool;
- 3+ examples containing near-dyadic, arbitrary decimal and just-outside-unit boxes that must be rejected unless stock `Shapes.box` actually canonicalized them.

The run reports guard-expectation and setup failures separately from verifier mismatches.

## Gate

1. Build/package and normal Startup CI must pass.
2. Hosted exact-pack smoke runs with the diagnostic property above and must retain the exact fixture/resource-selection contract.
3. Continue only if there are zero strict mismatches for eligible natural calls, zero eligible adversarial mismatches, zero guard/setup/identity failures, zero capture drops, and eligible stock CPU is materially large enough to justify a later behavior-changing experiment.
4. If eligible correctness fails, preserve the bounded numeric counterexample and close the domain.
5. If correctness passes but eligible CPU is immaterial, close without A/B.
6. Only after both correctness and material coverage pass should a separate reviewed candidate be considered. No laptop or hosted candidate/control A/B is requested by this diagnostic PR.

No production optimization is present on this branch.
