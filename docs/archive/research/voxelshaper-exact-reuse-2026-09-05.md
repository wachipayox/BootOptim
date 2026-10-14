# VoxelShaper exact-input reuse audit — 2026-09-05

## Status

**REJECTED as a startup optimization / durable upper-bound closure.**

PR: #123 `Diagnostic: audit exact VoxelShaper input reuse`.

This investigation deliberately changes the premise from PR #106/#110. It does not change Ponder's fold/`optimize()` history, batch unions, epsilon semantics, registry ordering, or mod-event scheduling. Instead it asks whether startup repeatedly executes the exact same deterministic `VoxelShaper.rotatedCopy` input strongly enough that an exact memoization/single-flight design could matter.

The answer on the pinned exact pack is: **the call count is highly repetitive, but the expensive CPU is not.** Exact repeated inputs account for 92.081% of non-zero `rotatedCopy` calls but only 10.098% of measured stock current-thread CPU. The maximum stock work exposed to whole-call exact reuse is only 661.872 ms CPU / 719.819 ms wall in this hosted run, before any production key lookup, storage, cloning, invalidation, or identity-preservation cost. No A/B or physical run is justified for that ceiling.

## Prior evidence and target

PR #99 measured `minecraft:block` as the dominant registry event but did not successfully attribute it by `ModContainer`; `mod_count=0` means this work must not be assigned to Decocraft or any other mod from that profiler.

PR #105 then localized material caller-thread CPU during the block-register window to the public Ponder/Catnip chain:

`VoxelShaper.rotatedCopy -> Shapes.or -> VoxelShape.optimize -> Shapes.joinUnoptimized -> BitSetDiscreteVoxelShape.join -> LithiumDoublePairList.forMergedIndexes`.

That profile is source localization, not a TTMM saving ceiling. PR #106 changed the union/optimize history and failed strict representation equivalence. PR #110 found a strict-safe `<=2`-box subset but it owned only 5.790 ms CPU / 0.097% of measured stock fold CPU; the broader epsilon-stable domain had natural and exact-dyadic mismatches.

The present audit therefore leaves stock `rotatedCopy` untouched and measures a different opportunity: repeated *whole inputs*.

## Exact public source checked

Ponder/Catnip `VoxelShaper` at public commit `c3e5a41380203e1dd1e2431c494ec491a51965a5` implements `rotatedCopy` by enumerating source boxes, rotating both endpoints, converting each to `Block.box`, and folding each box with `Shapes.or`. `rotate` already returns the original source object when `from == to`.

The public history for this `VoxelShaper.java` contains no later algorithmic change after that commit, so there is no unpublished-in-project Ponder fix to backport from current upstream.

The exact pack uses Lithium `0.15.3+mc1.21.1` at `09d115dc18acc978b281107e9d02e5d043a0c20f`. Comparing the public `mc1.21.1-0.15.3` and `mc1.21.1-0.15.4` tags shows no shape-merging file changes. `LithiumDoublePairList.java` is also source-identical between the exact tag and current public development source. Updating only to 0.15.4 therefore does not supply a shape-merging optimization for this target.

## Diagnostic design

Branch head measured: `97021eb5536593caeb0f821c596ab3b0b094c44c`.

Property:

```text
-Dboot_optim.profileVoxelShaperExactReuse=true
```

The diagnostic uses an optional `@Pseudo` mixin against Ponder, `require=0`, gated to Create `6.0.10` and Ponder `1.0.82(+suffix)`. It **never substitutes a cached shape**: every natural call runs stock `rotatedCopy`, and stock is always returned.

A repeated-input key is equal only when all of the following are equal:

- concrete source `VoxelShape` class;
- X/Y/Z coordinate-list sizes and every coordinate's `Double.doubleToLongBits` value;
- ordered `toAabbs()` count and every endpoint bit pattern;
- resolved rotation vector bit patterns.

The map uses a hash only as an index; equality compares all fields and the complete stored arrays. Zero-rotation calls are excluded because Ponder already returns the original source object and caching that path would add no work reduction.

For every repeated key, the first stock output is snapshotted and every later stock output is checked for the same concrete class, exact coordinate grids, and exact ordered AABB bit patterns. Thus the audit tests the proposed key against stock output representation rather than assuming geometric equality is enough.

## Hosted exact-pack result

Exact Pack Startup Benchmark run `33984941567`, exact job `101356720865`, artifact `exact-pack-result-smoke-1` ID `9974921134`, artifact digest `sha256:679d91906cfde2d17687f6351ca430b501d33ff22e0c954189a8b9869f89f5ed`.

Fixture validation passed for `exact-pack-2026-09-02-v1` at pinned SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`. Resource selection was valid with zero issues and the expected external ZIP packs matched the observed set. The smoke reached title with `bootoptim_mixin_errors=0` and an `8192x8192`, 2-level block atlas.

Measured `BOOTOPTIM_VOXELSHAPER_EXACT_REUSE` marker:

| Metric | Value | Type / interpretation |
| --- | ---: | --- |
| non-zero `rotatedCopy` calls | 12,640 | count |
| exact unique keys | 1,001 | count |
| exact duplicate calls | 11,639 / 92.081% | count coverage only |
| stock `rotatedCopy` total | 6,918.927 ms | inclusive measured wall inside audited calls |
| stock `rotatedCopy` total | 6,554.428 ms | current-thread CPU |
| duplicate-call stock work | 719.819 ms | wall ceiling exposed to whole-call reuse |
| duplicate-call stock work | 661.872 ms / 10.098% | current-thread CPU ceiling exposed to whole-call reuse |
| first/unique-key stock work | 5,892.556 ms | current-thread CPU by subtraction; remains untouched by whole-call reuse |
| exact-output mismatches | **0** | representation verifier |
| signature construction | 24.231 ms wall / 38.387 ms CPU | diagnostic cost, not a saving |
| duplicate-output verification | 16.993 ms wall / 28.567 ms CPU | verifier-only cost |
| maximum structural key payload | 902 longs | diagnostic memory/complexity indicator |
| TTMM | 99,521 ms | hosted smoke observation only; no control/candidate A/B |

The high duplicate count is therefore misleading in the same way as earlier cheap-wrapper reuse experiments: most of the expensive `VoxelShaper` work is concentrated in the first occurrence of the 1,001 exact inputs. Whole-call memoization cannot touch that 5.893 s current-thread CPU remainder.

The structural signature itself cost 38.387 ms current-thread CPU in the diagnostic. A production implementation would also require map lookup/storage and a bounded lifetime. More importantly, returning one previously created `VoxelShape` for two stock calls would introduce cross-call object aliasing / identity reuse that stock does not provide. Proving that aliasing unobservable across all exact-pack consumers, or cloning the exact stock representation to preserve distinct identities, would add semantic proof and implementation cost for a measured gross ceiling below 0.7 s wall in this hosted run.

A stricter cache keyed by source object identity cannot have a higher ceiling than the broader exact-representation key measured here, so another identity-only counting run is unnecessary.

## Why this is a no-go

1. **Correctness of the diagnostic premise was good:** 11,639 repeated exact keys produced 0 stock representation mismatches. This is not a rejection because the key collided.
2. **Economic ceiling is small:** even a hypothetical zero-overhead, zero-risk cache can eliminate at most 661.872 ms of measured current-thread CPU / 719.819 ms measured wall from whole repeated calls in this run. That is only 10.098% of audited stock CPU, despite 92.081% call coverage.
3. **The expensive first occurrences remain:** 5,892.556 ms current-thread CPU is outside the mechanism by construction.
4. **A real cache has non-zero semantic cost:** cross-call `VoxelShape` identity/aliasing differs from stock; exact structural keys need lifetime/invalidation and lookup; preserving distinct identities would require an exact representation clone. None of those risks/costs are justified by the measured ceiling.
5. **There is no newer public upstream implementation to inherit:** current Ponder has no later `VoxelShaper` algorithm, Lithium 0.15.4 does not touch shape merging, and public `LithiumDoublePairList` is unchanged.
6. **No TTMM claim was made:** the smoke proves compatibility and mechanism coverage, not end-to-end saving. With the mechanism already bounded below one hosted second before overhead, a hosted A/B and laptop gate would spend measurement budget on an optimization that still leaves the dominant unique work intact.

## What remains open

This result closes **whole-call exact memoization/single-flight of `VoxelShaper.rotatedCopy`** for the current exact pack. It does not prove that the remaining unique work is irreducible.

A materially different investigation may reopen VoxelShaper only with a premise that attacks the first/unique 1,001 expensive rotations while preserving stock output representation exactly. Examples that would qualify are:

- a specialized exact `Shapes.or(accumulator, rotatedBox)`/grid-remap algorithm that is verified bit-for-bit against stock after every natural fold, rather than postponing or changing `optimize()` history;
- source evidence plus profiling that a specific consumer is constructing many *different expensive* source representations from a common immutable intermediate and can eliminate that construction without changing returned shape objects;
- a future Ponder/Lithium/NeoForge version with a changed shape-merging implementation whose exact-pack CPU profile materially changes the 5.893 s unique-work remainder;
- a new exact-pack profile showing the whole-call duplicate CPU ceiling has grown materially, not merely that duplicate call count remains high.

Do not reopen this direction by using hash-only keys, epsilon canonicalization, longer-lived caches, a source-identity-only cache, or by treating 92% duplicate calls as 92% recoverable CPU.

## Physical-hardware decision

No laptop A/B is requested. The mechanism is Java shape construction rather than a Windows/native/GPU-specific behavior, hosted CI already bounds the reusable work, and there is no hosted-positive production candidate to arbitrate physically.
