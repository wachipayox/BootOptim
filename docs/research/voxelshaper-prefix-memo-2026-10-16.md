# VoxelShaper fold-prefix memo: ceiling census — 2026-10-16

Status: **REJECTED at gate 1 (economic ceiling)**. No candidate was built. Diagnostic (never merged, kept for reproduction): PR [#370](https://github.com/wachipayox/BootOptim/pull/370), branch `claude/voxelshaper-prefix-diag`.

Follows [block-shape-or-memo-2026-10-16](block-shape-or-memo-2026-10-16.md) (#369), which left Ponder `VoxelShaper` open: 76 % of `joinUnoptimized` samples, 72 % join-level repeat. Earlier closures that this entry respects: [voxelshaper-safe-domain-2026-09-05](voxelshaper-safe-domain-2026-09-05.md) (#110, delayed `optimize()` / epsilon domains), whole-call reuse (#123, `docs/archive/research/voxelshaper-exact-reuse-2026-09-05.md`), indexed-merger replay (#127, closed, no entry).

## Premise

`rotatedCopy` folds `acc = Shapes.or(acc, box_i)`. A step is a pure function of (acc, box), so two steps with the same ordered, exact box sequence since `Shapes.empty()` have identical accumulators by induction. A trie keyed by the exact box structure (concrete class, coordinate bits per axis, ordered box bits) could replay such a step without touching the union/`optimize()` history (unlike #106/#110). Design constraints for a candidate: verify the first repetition per node against stock, never replay the final step of a call (the externally returned shape would otherwise be aliased between blocks, the #123 concern), FALLBACK and kill switch.

## Gate 1 measurement

Origin: hosted exact-pack surrogate, `exact-pack-fixture: latest` (Beta rev 3), smoke with `-Dboot_optim.profileVoxelShaperPrefix=true`, run [38827338179](https://github.com/wachipayox/BootOptim/actions/runs/38827338179). Start: JVM; endpoint: `main_menu` (82.7 s, `mod_entrypoint` 32.8 s; diagnostic timing is perturbed, use ratios). The diagnostic never replaces a result; per-step wall/CPU brackets only the stock `Shapes.or`, key construction (19.9 ms total) is outside it.

| Quantity | Value |
| --- | ---: |
| `rotatedCopy` calls / fold steps | 12,640 / 29,055 (12,009 calls have a single box) |
| trie nodes (distinct lineages) | 14,953 |
| broken accumulator chains | 0 |
| stock output differences on 2nd occurrence | 0 |
| fold wall (task-sum) | 6,686 ms (`modloading-sync-worker` 5,276 ms, Render thread 1,410 ms) |
| steps that repeat a lineage (occurrence ≥ 2) | 14,102 (48.5 % of steps) |
| …their stock wall | 776 ms (11.6 % of fold wall) |
| non-final steps, occurrence ≥ 2 (no verification) | 2,384 steps, 764 ms wall / 661 ms CPU |
| **non-final steps, occurrence ≥ 3 (first repetition verified)** | **764 steps, 181 ms wall / 134 ms CPU** |

Reading:

- The repetition is real and exact but cheap: repeated steps are early steps with small accumulators (48.5 % of steps, 11.6 % of wall). The expensive steps are late steps of large unique shapes; their lineage is first-seen. This is the same shape as #123 (92 % of calls, 10 % of CPU), one level down. The 72 % join-level figure of #369 counts structurally equal joins reached from *different* box orders, which a lineage key cannot see; a structural key per join costs about as much as the join (#369).
- A design that verifies the first repetition per node (the project rule) can save at most 181 ms task-sum across both threads; the Render-thread part runs in the reload hidden by RRLS, so the registry critical path gets less than that. The unverified ceiling (764 ms) is not obtainable under the guard policy: the verification is exactly the stock work it would skip. Both are below the "some hundreds of ms on the critical path" criterion of the front's brief, before lookup, storage and trie memory cost.
- Not covered by Lithium or MoreCulling (same conclusion as #369): the fold time above is measured with both active.

## Decision

Reject. A hosted A/B cannot resolve a ≤ 0.2 s task-sum effect against the observed fresh-VM variance (see [github-fresh-vm-variance](github-fresh-vm-variance-2026-09-07.md)), and the PC fast-machine critical path (registry dispatch 8.8 s) would gain at most ≈ 0.2 s of the 8.8 s `RegisterEvent<Block>` dispatch with verification (≈ 0.8 s under the unattainable unverified ceiling, hosted timings). No physical run requested.

## What this leaves

`VoxelShaper` still owns ≈ 6.7 s of fold wall in this run, of which ≈ 5.3 s on the registry thread, and almost all of it is first-occurrence work on large accumulators. Per [#127](https://github.com/wachipayox/BootOptim/pull/127) the callbacks of the index merger are not the cost either.

## Reopening criteria

- A premise that reduces first-occurrence cost of a fold step while keeping the stock result representation bit-for-bit (a proven direct remap/fill, or a specialization inside `optimize()` itself), verified per natural step as in #127.
- A new pack profile where the interior occurrence ≥ 3 ceiling (here 181 ms wall) grows to several hundred ms, e.g. many more blocks sharing leading boxes after a Create/Ponder update.
- Do not retry lineage or whole-call memoization, structural per-join keys, delayed/batch `optimize()`, or epsilon domains without a materially new premise.
