# Indexed blockstate variant matching

## Status

**VALIDATED for production promotion — 2026-08-31 exact reference pack**

Experiment: PR #55 `Experiment: isolate indexed blockstate variant matching`.

The optimization targets Minecraft 1.21.1 `BlockStateModelLoader` variant matching. Stock constructs a predicate for each blockstate variant and then filters the complete `StateDefinition#getPossibleStates()` list for that variant. In the exact pack this produced an O(variants × states) scan with a very low match ratio.

## Exact-pack evidence

Deep structural profiling before the optimization measured:

- `110,053` variant predicates
- `10,856,307` variant × candidate-state tests
- `166,599` actual matches
- `BlockStateModelLoader.loadAllBlockStates`: about `4,405.969 ms`

PR #55 replaced only the candidate scan with per-`StateDefinition` property/value BitSet indexes. Stock predicate construction still runs first, so property/value validation and stock exceptions remain authoritative.

Normal exact-pack timing run (`verify=false`):

- definitions indexed: `10,105`
- indexed variants: `110,053`
- fallbacks: `0`
- stock candidate tests represented: `10,856,307`
- indexed match visits: `166,599`
- avoided candidate visits: `10,689,708` (`98.47%`)
- index construction: `4.191 ms`
- indexed matching: `63.834 ms`

Exact-pack equivalence run with `-Dboot_optim.blockstateIndexedMatchingVerify=true`:

- definitions indexed: `10,105`
- indexed variants: `110,053`
- fallbacks: `0`
- verification mismatches: `0`
- stock candidate tests: `10,856,307`
- indexed match visits: `166,599`
- avoided candidate visits: `10,689,708`
- index construction: `2.614 ms`
- indexed matching: `54.106 ms`
- stock verification path: `601.381 ms`

The verification compares the indexed result against the stock predicate for every variant using object identity and canonical order. The full exact reference pack therefore produced `0 / 110,053` disagreements.

## Production design

The promoted implementation keeps the same algorithm but removes all experimental timing, counters, verification and startup logging.

Invariants:

- stock `BlockStateModelLoader.predicate(...)` runs first and remains authoritative for parse/validation failures;
- the optimization changes only the `Stream.filter` candidate enumeration after successful stock predicate creation;
- indexed results preserve `getPossibleStates()` order;
- indexes are scoped to one `loadAllBlockStates` invocation and released afterward;
- an unexpected secondary-parser/index failure falls open to the original stock predicate path;
- `-Dboot_optim.blockstateIndexedMatching=false` is an emergency compatibility switch.

## What not to repeat

Do not rediscover this as another generic model-bake cache. PR #36 already proved top-level identity reuse is not the main cost. This optimization addresses a different algorithmic source: the blockstate variant candidate scan before baking.

Do not add permanent equivalence verification to production. PR #55 already compared all `110,053` exact-pack variants and found zero mismatches; the verification deliberately re-runs the stock predicate path and is diagnostic overhead.

Do not infer the exact end-to-end gain by subtracting ~64 ms from the earlier ~4.406 s `loadAllBlockStates` measurement. The earlier figure covered the entire loader while the experimental timers measured only index construction/matching. The correct conclusion is that the dominant 10.86M candidate scan was eliminated; whole-startup A/B remains noisy across warm launches.
