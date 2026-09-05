# VoxelShaper limited safe-domain investigation — 2026-09-05

Status: **REJECTED / DOCUMENTATION ONLY**

PR: [#110](https://github.com/wachipayox/BootOptim/pull/110)

Branch: `codex/research-voxelshaper-safe-domain`.

The branch began from `agent/integration-current` at `792d06ec008c5ebae3681dd94f7aeee2c8e5f2a2`. During closure integration advanced to `d29a6bad6358c7ff78dadbc5e85bd753c0ad2a54`; the final PR diff is aligned to that current integration state.

This investigation did **not** retry rejected PR #106 as a production candidate. It asked a narrower question: can a source-level precondition admit a subset of exact Ponder 1.0.82 `VoxelShaper.rotatedCopy` calls for which delaying intermediate `optimize()` is strictly stock-representation-equivalent, and is enough measured stock fold CPU located in that subset to matter?

Final answer: **no shippable domain was found**.

- Calls with at most two rotated boxes survived strict verification, but owned only `5.790 ms / 5,980.679 ms = 0.097%` of measured stock fold CPU. Reject on economic ceiling.
- The broader `EPSILON_STABLE` domain covered a materially larger fraction of measured stock fold CPU, but failed strict equivalence with two real-pack eligible counterexamples and one deterministic adversarial eligible counterexample. Reject on semantics.
- No candidate/control A/B and no physical laptop run are justified.
- No diagnostic or behavior-changing code from this investigation is retained.

## Prior evidence and source mechanism

PR #105 localized active CPU inside exact-pack `minecraft:block` registration to:

`VoxelShaper.rotatedCopy -> Shapes.or -> Shapes.join -> VoxelShape.optimize -> Shapes.joinUnoptimized -> BitSetDiscreteVoxelShape.join -> LithiumDoublePairList.forMergedIndexes`.

Its ~7.168 s target-thread CPU was attribution evidence, not a savings estimate.

PR #106 preserved the already-created rotated-box stream but accumulated with `joinUnoptimized(..., OR)` and called `optimize()` once at the end. Its exact-pack verifier observed `12,640` calls / `29,055` boxes, `12,553` strict matches and `87` mismatches. That mechanism was rejected before A/B.

Exact runtime/source versions used here:

- Create `6.0.10`;
- Ponder `1.0.82`, source commit `c3e5a41380203e1dd1e2431c494ec491a51965a5`;
- Minecraft `1.21.1` `Shapes`, `VoxelShape`, `BitSetDiscreteVoxelShape`;
- Lithium `0.15.3+mc1.21.1`, source commit `09d115dc18acc978b281107e9d02e5d043a0c20f`.

Stock `Shapes.or(a,b)` reaches `Shapes.join(a,b,OR) = joinUnoptimized(...).optimize()`. Stock can therefore greedily decompose/rebuild the accumulator after every box. Final-only construction carries a different discrete-grid/decomposition history to its last `optimize()`.

Strict equivalence checked, in order: empty state, XOR geometry, exact bounds bits, exact X/Y/Z coordinate counts/bits, exact ordered `toAabbs()` bits, and concrete shape class. Passing XOR/bounds is not sufficient for BootOptim's semantic contract.

## Domain A — at most two rotated boxes

For one stock-created rotated box `b`, `Shapes.or(empty,b)` is `b.optimize()`. The input was just created by stock `Block.box -> Shapes.box`; optimizing that single prism enumerates the same bounds and reconstructs the same stock shape from the same doubles. For two boxes, the first accumulator is therefore strictly the same first shape, so stock and delayed construction feed identical operands into the second union/final optimize.

Hosted exact-pack attribution:

- natural non-zero calls: `12,640`;
- small-count calls: `12,036`;
- strict matches: `12,036 / 12,036`;
- strict mismatches: `0`;
- eligible stock fold CPU: **`5.790 ms`**;
- total stock fold CPU: **`5,980.679 ms`**;
- eligible CPU share: **`0.097%`**.

This is stock-fold current-thread CPU attribution, not demonstrated TTMM savings. No candidate replaced stock and no performance A/B ran.

Decision: **REJECTED — economically immaterial.** Call count was misleading: the expensive CPU was concentrated in larger-box calls.

Evidence: Exact Pack #187, run `33975479303`, artifact `9972231465`.

## Domain B — `EPSILON_STABLE`

For 3+ boxes the diagnostic admitted only finite effective coordinates, rejected any two distinct same-axis cuts within `Shapes.EPSILON = 1e-7`, and rejected non-exact values inside the `findBits` snap neighborhood. It never rounded or normalized coordinates.

The proposed proof claimed that, without epsilon collisions or snap ambiguity, intermediate grids could differ only by exact refinement/coarsening and `BitSetDiscreteVoxelShape.forAllBoxes(..., true)` would preserve the same greedy physical decomposition. The exact-pack and adversarial evidence falsified that last claim.

Validation smoke #192 (`33977469417`, artifact `9972796933`) measured:

- eligible stock fold CPU: **`1,436.191 ms`**;
- total measured stock fold CPU: **`5,670.007 ms`**;
- eligible CPU share: **`25.33%`**;
- natural eligible strict mismatches: **`2`**;
- adversarial eligible strict mismatches: **`1`**.

The **25.33% is only guard coverage / a pre-correctness CPU ceiling. It is not valid savings, recoverable CPU, or TTMM opportunity.** Correctness failed before any candidate or A/B could exist.

The final evidence rerun #196 reproduced `12,215` eligible calls, `12,213` matches, the same 2 natural mismatches and 1 adversarial mismatch. Its measured eligible share was `24.782%` (`1,155.085 / 4,661.029 ms`), showing normal diagnostic run-to-run variation. In that run guard CPU was `20.561 ms`, bounded capture CPU `10.413 ms`, and candidate replay CPU `80.679 ms`; replay/compare occurred only after the semantic TTMM timestamp.

## Hosted evidence and exact-pack contract

Evidence commit `93af543dd9bc8a6c97881e96dc42fd332e3b9303`:

- Build #1465 PASS;
- Startup #412 PASS;
- Exact Pack #193 PASS, run `33977905971`;
- artifact `9972917895`, digest `sha256:cd03c4d031d3e7c11ee0a102c51d3cf6764a23df28277c732ad0f60bf85ba938`;
- atlas `8192 x 8192 x 2`, main menu reached, zero BootOptim Mixin errors, resource contract valid.

Audit found one mechanical evidence-only bug: the new targeted dumper existed but was not invoked, so #193 could not emit the requested targeted rows. This did not alter the guard or any semantic result.

Commit `b507c753323afc8e0e04f87e6d679a14823e76b8` added only the missing post-TTMM invocation. Final corrected gate:

- Build #1483 PASS;
- Startup #414 PASS;
- Exact Pack #196 PASS, run `33978305847`;
- result artifact `9973025541`, digest `sha256:f06b56d31f5a83d7c79aa6cd42fe8e03884a0e70745141f4f96bf8027d0733b6`;
- summary artifact `9973028562`;
- pinned fixture SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- one effective reload, all ten external ZIP packs retained in expected order, no fallback;
- atlas `8192 x 8192 x 2`;
- `bootoptim_mixin_errors = 0`;
- main menu reached.

The one-run `main_menu_ms = 75,036` is diagnostic smoke sanity only, not an optimization comparison.

## Natural eligible counterexample 1 — call 136

Marker: `boxes=9`, `reason=coord_count_y`, `guard_failure=none`, rotation `(0,-90,0)`, first divergent prefix `7`.

Already-rotated boxes:

1. `(0,0,0 -> 1,1/4,1)`
2. `(0,0,3/16 -> 1,1/8,13/16)`
3. `(0,0,13/16 -> 1,1/8,1)`
4. `(0,1/8,0 -> 1,1/4,3/16)`
5. `(0,1/8,3/16 -> 1,1/4,13/16)`
6. `(0,1/8,13/16 -> 1,1/4,1)`
7. `(1/16,1/4,3/16 -> 15/16,7/8,13/16)`
8. `(1/16,1/2,3/16 -> 15/16,3/4,13/16)`
9. `(1/16,3/4,3/16 -> 15/16,7/8,13/16)`

Stock final Y grid:

`[0,1/8,1/4,3/8,1/2,5/8,3/4,7/8,1]`

Final-only Y grid:

`[0,1/8,1/4,1/2,3/4,7/8,1]`

Every input cut is exact dyadic. The first divergence appears after box 7: stock's repeated optimization history retains `3/8` and `5/8`; final-only does not. XOR geometry and bounds had already passed before `coord_count_y` failed.

## Natural eligible counterexample 2 — call 818

Marker: `boxes=36`, `reason=coord_count_x`, `guard_failure=none`, rotation `(0,-90,0)`, first divergent prefix `31`.

The 36-box stream is deterministic in artifact `9973025541`; its distinctive guard-admitted non-dyadic X bounds include exact doubles `0x1.554fdf3b645a2p-2` (~`0.33331250000000001`) and `0x1.355810624dd2fp-1` (~`0.60418749999999999`), alongside 1/16-grid cuts. Representative boxes around the first non-dyadic transitions include:

- `(1/16,5/8,0 -> 0.33331250000000001,7/8,5/16)`;
- `(1/16,5/8,1/4 -> 0.33331250000000001,3/4,5/16)`;
- `(1/16,3/4,0 -> 0.60418749999999999,7/8,1/4)`;
- `(1/16,3/4,3/4 -> 0.60418749999999999,7/8,1)`;
- `(0.33331250000000001,7/8,0 -> 7/8,1,1)`;
- `(0.60418749999999999,1,0 -> 7/8,9/8,1)`.

Stock final X grid:

`[0,1/16,1/8,1/4,5/16,0x1.554fdf3b645a2p-2,3/8,1/2,0x1.355810624dd2fp-1,5/8,11/16,3/4,7/8,1]`

Final-only X grid:

`[0,1/16,1/4,5/16,0x1.554fdf3b645a2p-2,1/2,0x1.355810624dd2fp-1,11/16,3/4,7/8,1]`

Final-only omits stock cuts `1/8`, `3/8`, and `5/8`. No guard condition fired, so epsilon separation plus snap stability is insufficient.

## Deterministic adversarial eligible counterexample — case 49

This is the strongest closure evidence because all six boxes use exact dyadic coordinates.

Marker: `boxes=6`, `reason=coord_count_x`, first divergent prefix `5`.

Input boxes:

1. `(1/4,0,0 -> 1/2,1,1)`
2. `(1/8,0,1/8 -> 3/8,1,3/8)`
3. `(0,0,0 -> 1/2,1/2,1/2)`
4. `(1/16,3/16,5/16 -> 9/16,11/16,15/16)`
5. `(1/2,1/2,1/2 -> 1,1,1)`
6. `(1/4,0,1/4 -> 3/4,1,3/4)`

Stock final X grid:

`[0,1/16,1/8,1/4,3/8,1/2,9/16,3/4,1]`

Final-only X grid:

`[0,1/16,1/8,1/4,3/8,1/2,9/16,5/8,3/4,7/8,1]`

The first divergence appears after box 5. Final-only retains additional `5/8` and `7/8` X cuts despite exact, widely separated inputs. Therefore the proposed refinement invariant is false independently of Lithium near-epsilon representative selection: **the greedy `VoxelShape.optimize()` / `forAllBoxes(..., true)` reconstruction is history-sensitive for strict representation.**

Some rejected #106 failures did involve near-epsilon representative differences, but that mechanism is not necessary for these #110 failures and must not be used to explain them away.

## CPU / wall interpretation

The diagnostic separated real stock fold CPU/wall, guard/capture overhead, and post-TTMM candidate replay/comparison.

- `0.097%` is a stock-fold CPU share for the strict-safe small domain, not an end-to-end saving.
- `25.33%` is CPU coverage of a domain that later failed correctness, not recoverable CPU and not TTMM savings.
- Candidate replay after title is verifier cost, not startup improvement.
- No candidate/control A/B exists, so no critical-path or TTMM benefit was demonstrated.
- Hosted llvmpipe absolute wall time is not a laptop baseline.

## Final decision

**CLOSE THIS FRONT.**

1. `<=2` boxes: correctness survives, economics do not (`0.097%` measured stock-fold CPU).
2. `EPSILON_STABLE`: CPU coverage looked material, correctness does not (2 natural + 1 adversarial eligible strict mismatches).
3. The exact-dyadic adversarial case disproves the key refinement/decomposition invariance premise.
4. Do not relax the guard, round/normalize coordinates, blacklist observed failures, whitelist pack IDs, change Lithium/Palladium/ToadLib flags, or retry #106.
5. No hosted A/B and no physical laptop run are requested.
6. The final PR retains only durable research documentation; all diagnostic mixin/profiler/evidence-dump code is removed.

Reopen only for a **materially different construction premise** that proves strict stock representation equivalence for every admitted input before performance testing. A lower threshold or another numeric guard is not a new premise.
