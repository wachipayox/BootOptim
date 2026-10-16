# Block shape builders: exact `Shapes.or` replay

Status: **PRODUCTION, default enabled** (PR #369). Evidence and decision: [`docs/research/block-shape-or-memo-2026-10-16.md`](../../research/block-shape-or-memo-2026-10-16.md).

## Scope

Vanilla 1.21.1 static shape builders, run once per block at registration:

- `WallBlock.makeShapes` (+ `applyWallShape`), including every subclass that does not replace it (private in vanilla);
- `CrossCollisionBlock.makeShapes` (fences, panes, iron bars);
- `PipeBlock.makeShapes` (chorus plant and pipe-like blocks).

Vanilla passes constant parameters from the constructors, so every block of the pack repeats the same few parameter tuples and about 2,000 identical `joinUnoptimized` calls per block. Measured effect on the hosted exact-pack (Beta rev 3): builder wall ≈ 1.0 s → ≈ 0.16 s per startup (two paired observations, same instrumentation); 99.8 % of the builders' joins were repeats.

## Mechanism

Per (builder, exact float parameter bits) the first call records the results of the `Shapes.or(VoxelShape, VoxelShape)` call sites (MixinExtras `@WrapOperation`, `require = 0`); later calls return the recorded shapes at the same sites. The rest of the stock method runs unchanged. Shapes are shared between blocks; the table lives for the process and holds at most 64 keys (10 keys, 1,737 shapes in the pack).

## Safety

- Targets are vanilla classes; nothing is gated on a mod version. If a builder no longer contains the injection points, the post-apply injection check logs `[WBOMBO-COMPAT] FALLBACK` and stock runs.
- Differential guard: the first replay per (builder, parameters, block class) re-runs stock and compares each result exactly (class, coordinate-grid bits, ordered box bits). `-Dboot_optim.blockShapeOrMemo.audit=true` verifies every replay (clean over all 970 builder calls of the pack). A mismatch or sequence-length difference disables the mechanism for the run and logs `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=block-shape-or-memo reason=…`; `ACTIVE` is logged on first recording.
- Kill switch: `-Dboot_optim.blockShapeOrMemo=false` (builders are still timed, so a control reports stock wall time in the `BOOTOPTIM_BLOCK_SHAPE_MEMO` log lines).
- Does not change the union/`optimize()` history of any shape (unlike the rejected VoxelShaper batching); not applicable to third-party builders.
