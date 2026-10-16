# VoxelShaper fold cost: construction order, single `optimize()`, rotation — 2026-10-16

Status: **REJECTED at gate 1 (no exact-equivalent construction)**. No candidate was built. Diagnostic (never merged, kept for reproduction): PR [#372](https://github.com/wachipayox/BootOptim/pull/372), branch `claude/voxelshaper-fold-cost`.

Follows [voxelshaper-prefix-memo-2026-10-16](voxelshaper-prefix-memo-2026-10-16.md) (#371), which left Ponder `VoxelShaper` at ≈ 6.7 s of fold wall, almost all first-occurrence work. Respects [voxelshaper-safe-domain-2026-09-05](voxelshaper-safe-domain-2026-09-05.md) (#106/#110: delayed `optimize()`).

## Premise

Reduce the cost of each `rotatedCopy` fold (`acc = Shapes.or(acc, box)`) with a bit-identical result by (1) changing the fold order (balanced pairs), (2) a single `optimize()` at the end, (3) rotating an already-built shape instead of rebuilding it.

## Gate 1 measurement

Origin: hosted exact-pack surrogate, `exact-pack-fixture: latest` (Beta rev 3), smoke with `-Dboot_optim.profileVoxelShaperFold=true`, runs [38828590377](https://github.com/wachipayox/BootOptim/actions/runs/38828590377) and [38830273422](https://github.com/wachipayox/BootOptim/actions/runs/38830273422) (Lithium and MoreCulling active; 0 FALLBACK). Start: JVM; endpoint: `main_menu`. After each stock `rotatedCopy` returned, outside its timing, the recorded boxes were rebuilt in-process per construction and compared exactly with stock (concrete class, per-axis coordinate bits, ordered `toAabbs()` bits). Times are lab replays on the executing thread, warm, diagnostic-perturbed: use ratios.

12,640 calls, 631 with ≥ 2 boxes (the rest are one box, no fold work). Run 1 numbers (run 2 within ≈ 10 %):

| Finding | Value |
| --- | ---: |
| stock lab replay vs recorded step results | 0 mismatches over 29k steps (replay is deterministic) |
| stock fold wall, `optimize()` / `joinUnoptimized` | 6,151 ms / 138 ms (run 2: 6,604 / 152 ms) |
| inside `optimize()` (run 2, 17,046 steps) | `forAllBoxes` 73 ms, box allocation 37 ms, fold of `joinUnoptimized` over 595 k boxes **6,300 ms** (≈ 10.6 µs per join) |
| once (`joinUnoptimized` chain + one `optimize()`) | **87 / 631 calls not exact** (78 on `modloading-sync-worker`) |
| balanced pairs (`Shapes.or` tree) | **51 / 631 calls not exact** (45 on `modloading-sync-worker`) |
| stock keeps the input boxes (same set, same order) | 9 / 631 calls |

Time on the calls that happen to match (not obtainable, see below): once ≈ 25× faster on 33+ box calls (227 ms vs 5,627 ms), tree ≈ 7.6× (741 ms); matching-call saving 4.3 s (once) / 4.0 s (tree) task-sum, of which 3.3 / 3.4 s on the registry thread.

## Reading

- The cost is not the union step nor the box enumeration: it is the greedy `VoxelShape.optimize()`, which rebuilds the shape by folding `joinUnoptimized` over all of its boxes, i.e. O(boxes × grid) per fold step, repeated at every step. 99 % of `Shapes.or` time is this inner fold.
- Hypotheses 1 and 2 are not exact. The mismatching calls are the large ones, the same history sensitivity as #106/#110 (greedy decomposition depends on the intermediate grids). A construction that is exact only on a subset needs a proof or a per-call stock run; the second costs exactly the saving, the first was already refuted in #110 (epsilon domain, dyadic counterexample). The 9/631 figure also shows stock does not even keep the input boxes, so a rotation of the built shape (hypothesis 3) cannot reproduce its decomposition in general; not pursued further.
- This does not repeat the #371 ceiling: the opportunity is large (≈ 4 s task-sum if exact), it is just not reachable by reordering.

## Decision

Reject hypotheses 1–3 as stated. No candidate, no A/B, no physical run.

## Reopening criteria

- A bit-identical faster **inner** loop: `BitSetDiscreteVoxelShape.join` (called from `joinUnoptimized` for each box of `optimize()`) computed from per-axis merged-index arrays without per-cell callbacks, verified exactly against stock on every step (the lab here already confirms a stock replay of `optimize()` as `forAllBoxes` + `joinUnoptimized` fold is exact on 17,046/17,046 steps). The result is determined by the merger mappings and the operator, so equivalence is provable per step; the difficulty is access: `IndexMerger`/`Shapes.createIndexMerger` are package-private in a named module and Lithium replaces the merger, so the hook must go through Mixin with `@Coerce` or an interface bridge and use the real mergers (never reimplement their epsilon rules). Applies to every join, so scope it to the `rotatedCopy` call stack. Gate: ≥ several hundred ms on the registry thread (ceiling here: 6.3 s of join folds on the replay, 5.1 s on the registry thread).
- Do not retry delayed/batch `optimize()`, balanced folds, epsilon domains, or lineage/whole-call memoization without a materially new premise.
