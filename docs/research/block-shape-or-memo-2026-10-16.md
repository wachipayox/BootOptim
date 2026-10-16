# Block shape builders: exact replay of `Shapes.or` results — 2026-10-16

Status: **INTEGRATE** (promoted in PR [#369](https://github.com/wachipayox/BootOptim/pull/369)). Closes frontier 2 of [beta-rev3-baseline-2026-10-15](beta-rev3-baseline-2026-10-15.md) (#360) for wall, cross-collision and pipe blocks. The remaining join CPU (Ponder `VoxelShaper`) is **not** closed here, see "Not covered".

Catalog entry: [block-shape-or-memo](../optimizations/compatibility/block-shape-or-memo.md). Counting diagnostic (never merged, kept for reproduction): branch `claude/block-shape-joins-diag`, PR [#366](https://github.com/wachipayox/BootOptim/pull/366).

## Premise and correction

#360 listed the `joinUnoptimized` family (≈ 1.9 s sampled CPU) as callers `WallBlock.makeShapes`, `CatwalkRailingBlock`, `CableShapes`, `CrossCollisionBlock`, `PipeBlock`. Re-reading the JFR of that entry's R2 run (38766230138, `startup.jfr`, 5,622 execution samples) by full stack shows a different split of the 659 samples that contain `Shapes.joinUnoptimized`:

| Caller family | Samples | Thread / phase |
| --- | ---: | --- |
| Ponder/Catnip `VoxelShaper.rotatedCopy` (closed in #110, #123) | 498 (76 %) | `modloading-sync-worker` 388 (inside `RegisterEvent<Block>`), Render thread 110 |
| `WallBlock` / `CrossCollisionBlock` / `PipeBlock.makeShapes` | 66 (10 %) | `modloading-sync-worker` 57 (inside `RegisterEvent`), 9 in `Blocks.<clinit>` |
| Lazy `getShape` (Belt, Cable, Catwalk, Chute, Furnish) | 76 (12 %) | Render thread, all from MoreCulling `initShapeCache` during the resource reload (already studied in the archived `moreculling-initshapecache-algorithmic-2026-09-08.md`) |
| Other | 19 | mixed |

Only the second row is in the "mod loading" segment and not Ponder. That is what this entry targets.

## Gate 1: counting diagnostic

Origin: hosted exact-pack surrogate, `exact-pack-fixture: rev_7d5b9e504f8422494860364f9312da8e` (Beta rev 3, manifest `16ebb8d1…`), smoke with `-Dboot_optim.profileShapeJoins=true`, run [38813154173](https://github.com/wachipayox/BootOptim/actions/runs/38813154173). Start: JVM; endpoint: `main_menu`. The diagnostic keys every `joinUnoptimized` call by a 128-bit digest of (operation truth table, per-axis coordinate bits and ordered `toAabbs()` bits of both inputs); the key is computed outside the timed interval. Builder scopes are exact; other callers are a 1-in-32 per-thread systematic sample. It perturbs timing (≈ +50 % on these scopes): use counts and ratios, not absolute times.

| Builder | Calls | Parameter tuples | Joins | Repeats an earlier join | Stock join time |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WallBlock.makeShapes` (WallBlock 460, ReinforcedWallBlock 50, CopycatWallBlock 2) | 512 (256 blocks × 2) | 2 | 1,057,792 | 99.92 % | 1,121 ms |
| `CrossCollisionBlock.makeShapes` (14 classes) | 449 | 3 | 46,303 | 99.15 % | 161 ms |
| `PipeBlock.makeShapes` (5 classes) | 9 | 3 | 14,112 | 91.59 % | 41 ms |

- Vanilla hard-codes the parameters in the constructors (`4,3,16,0,14,16` and `4,3,24,0,24,24` for every wall), so every block of the pack repeats the same two tuples: 2 distinct results across 512 calls. Each call performs ≈ 2,000 joins because `Shapes.or` → `optimize()` re-joins every box.
- Within one call only 0.3 % of joins repeat; the repetition is across blocks. A per-call cache would not help.
- Identity keys (`==` inputs within one call) catch only 7.4 % (wall), 0 % (cross), 3.6 % (pipe): an identity memo is insufficient. Equal-shape keys are needed, but computing a structural key per join costs about as much as the join itself (8.1 s of key time for 1.9 M joins against 8.8 s of join time, in this perturbed run). Hence the float-parameter key at builder level.
- Already cached by Lithium or MoreCulling? No. Lithium replaces only the index merger (`LithiumDoublePairList`, sampled inside these joins); MoreCulling caches per state after the fact. Both are on the stack of every sample.
- Thread: wall and most cross/pipe calls run on `modloading-sync-worker` between uptime ≈ 25 s and 49 s inside the serial `RegisterEvent<Block>` dispatch, i.e. on the registry critical path; the vanilla `FenceBlock`/`StainedGlassPaneBlock` calls run on the bootstrap thread (`Blocks.<clinit>`).
- Ceiling: stock join time of the three builders ≈ 1.3 s task-sum, 99.8 % repeats. That clears the "some hundreds of ms on the critical path" criterion, so gate 2 was opened.

## Gate 2: candidate

`BlockShapeOrMemo` records, per (builder, exact float parameters), the sequence of results returned by the `Shapes.or(VoxelShape, VoxelShape)` call sites inside the builder, and replays that sequence on later calls. Everything else in the stock method (box creation, state loops, `ImmutableMap` construction for the block's own states) runs unchanged, so third-party mixins into the same method keep working. Results are shared between blocks (immutable shapes; vanilla already shares `Shapes.block()` and friends). Cache lifetime: process, at most 64 keys (10 in the pack, 1,737 shapes).

Safety:

- Differential guard: the first replay per (builder, parameters, block class) re-runs stock and compares every `or` result exactly (concrete class, coordinate-grid bits, ordered box bits). With `-Dboot_optim.blockShapeOrMemo.audit=true` every replay is verified. A mismatch or a sequence-length difference disables the mechanism for the rest of the run and logs `[WBOMBO-COMPAT] FALLBACK project=BootOptim mechanism=block-shape-or-memo …`. `ACTIVE` is logged on first recording. The automatic injection check covers the six injectors (vanilla targets, `require = 0`). No mod version is tested.
- Kill switch `-Dboot_optim.blockShapeOrMemo=false`.
- Not a repeat of #106/#110: no change to the history of unions or `optimize()` calls; each replayed value is what stock computed for identical inputs.

Equivalence over the whole pack: audit run [38814899724](https://github.com/wachipayox/BootOptim/actions/runs/38814899724) (same fixture): 970 builder calls (wall 512, cross 449, pipe 9), every replay verified, **0 mismatches, 0 FALLBACK**, 10 memo keys, 1,737 recorded shapes, 0 mixin errors, the six existing mechanisms still ACTIVE.

## Gate 3: paired A/B

Origin: hosted exact-pack surrogate, `exact-pack-mode: paired`, 2 pairs per run (control→candidate, then candidate→control), same fixture. Start: JVM; endpoint: `main_menu`. Control = `-Dboot_optim.blockShapeOrMemo=false`, candidate = `=true`; both arms time the three builders with the same counters.

Segment first (wall of all 970 builder calls, task-sum on the executing threads):

| Run | Pair | Control | Candidate | Delta |
| --- | --- | ---: | ---: | ---: |
| 38817143837 | 1 (control→candidate) | 1,033.6 ms | 148.1 ms | −885 ms (−85.7 %) |
| 38817143837 | 2 (candidate→control) | 978.9 ms | 170.0 ms | −809 ms (−82.6 %) |

Candidate time is the first stock recording per key plus the first verification per block class; the rest is replay. The first A/B run (38816363682) predates control instrumentation, so it has candidate-side builder times only (153 ms and 157 ms) and is used for the end-to-end rows.

End-to-end (hosted, same fixture, all four pairs, candidate minus control). The builders run in `RegisterEvent<Block>`, i.e. after the `mod_entrypoint` marker, so the segment that contains them is post-entrypoint → `main_menu`:

| Run | Pair | `main_menu` | post-entrypoint → `main_menu` | `mod_entrypoint` |
| --- | --- | ---: | ---: | ---: |
| 38816363682 | 1 (control→candidate) | −1,854 ms | −1,447 ms | −407 ms |
| 38816363682 | 2 (candidate→control) | −3,680 ms | −1,582 ms | −2,098 ms |
| 38817143837 | 1 (control→candidate) | −2,545 ms | −2,220 ms | −325 ms |
| 38817143837 | 2 (candidate→control) | −2,825 ms | −2,881 ms | +56 ms |

Median per run: `main_menu` −2,767 / −2,685 ms; post-entrypoint −1,515 / −2,551 ms; process CPU at `main_menu` −11.1 s / −14.7 s; JIT time at `main_menu` −7.8 s / −4.7 s; GC time −187 / −112 ms. Retained heap at the menu: pair deltas +190, −149, −176, +118 MB, no systematic direction (the memo itself is 1,737 small shapes).

Reading:

- The segment result is the evidence: the builders drop by ≈ 0.8–0.9 s in both pairs of the controlled run, and the candidate side is stable (148–170 ms across four observations).
- The end-to-end deltas in the containing segment are negative in 4 of 4 pairs but 2–3× larger than the segment saving and vary pair to pair. Hosted fresh-VM variance (see [github-fresh-vm-variance](github-fresh-vm-variance-2026-09-07.md)) and JIT effects (the builders were a very large C2 target, see `docs/archive/research/voxel-shape-source-attribution.md`) are the likely reasons for the excess. `mod_entrypoint`, which does not contain the builders (only the bootstrap-thread `Blocks.<clinit>` ones), moves by noise (−2,098 … +56 ms). **Only the segment saving is claimed.**
- No physical laptop or fast-PC run. The mechanism removes CPU, so slower CPUs should save more; a physical run is optional, not a gate here, because the effect is large relative to the hosted noise at segment level and equivalence is verified over every pack input.

Decision (segment-first policy): **integrate** — actual segment work reduced consistently, exact equivalence verified over every pack input, no attributable regression.

## Not covered (new facts for the next frontier)

- The same run's census of the other callers, sampled 1-in-32 (19,880 sampled joins, ≈ 636 k real): `VoxelShaper` joins repeat an earlier structurally identical join in 72 % of calls and 74 % of its join time (≈ 6 s of stock join task-sum in this perturbed run, `modloading-sync-worker` and Render thread). This does **not** contradict #123 (whole-`rotatedCopy` input repeats are 92 % of calls but only 10 % of CPU): the repetition is at join level (shared prefixes of the box fold across different source shapes), a different premise. A structural per-join key is too expensive (see above); an incremental key over the box prefix (O(1) per fold step) has not been measured. Not opened here: it needs its own diagnostic. Reopening criterion: a prefix-keyed repeat count with exact-equality proof against stock, keeping the stock union/optimize history (the #106 failure).
- MoreCulling `initShapeCache` lazy `getShape` joins (≈ 76 samples, Render thread, reload phase): hidden by RRLS reload, out of this frontier.

## Reopening criteria

- The mechanism is retired if a hosted or physical run shows `[WBOMBO-COMPAT] FALLBACK … mechanism=block-shape-or-memo` after a pack or Minecraft update (investigate the reason string first; the injection check also reports a vanilla builder that changed).
- A new builder with hard-coded parameters shows up in the pack's join census (`-Dboot_optim.blockShapeOrMemo.audit=true` plus the diagnostic branch) only if it is a vanilla class; third-party builders are not covered and need their own premise.
