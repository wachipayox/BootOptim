# Segment-first optimization decisions — 2026-10-01

Status: **CURRENT USER POLICY**. Explicit user instruction supersedes older
default-off/inconclusive retention policies for the pending optimization lane.

## Measurement and decision

1. Use two control and two candidate observations with identical instrumentation,
   software, packs, workload, clock and segment boundaries. Prefer ABBA/BAAB
   within one JVM when mode changes are safe. Warmup and necessary retained-state
   conditioning are unmeasured, declared separately, never additional votes.
2. Measure the actual changed work first. Whole `bakeModels` is not automatically
   an exclusive FerriteCore, Sodium or Decocraft budget. Count reductions and
   synthetic equivalence tests alone do not prove a game performance saving.
3. CPU and elapsed wall are separate. Two controls reveal some drift; they do
   not mathematically eliminate every hardware variation. Keep raw observations
   and workload/call counts so an invalid or contradictory contrast is visible.
4. Inspect other phases only where a source-level mechanism can affect them.
   FerriteCore capacity retention requires clear/trim cost, retained empty heap
   and GC checks. Moving work requires the destination phase and first-world
   usability. Callback changes require semantic equivalence. Unrelated launcher
   or runner noise is not evidence against a proven local saving.
5. Decide **integrate** when actual segment work is reduced consistently and
   semantic/runtime checks pass without a material attributable regression.
   A global time-to-menu improvement is not required for deliberately retained
   CPU removal under this user policy.
6. Otherwise **reject and remove from the active lane**, documenting the tested
   mechanism, conditions, raw evidence, reason and reopening criteria on
   `agent/integration-current`. Invalid evidence must first be repaired; never
   invent a binary performance conclusion from a failed recording.
7. A specific feasible salvage change can start a bounded follow-up. Do not
   leave an experimental option indefinitely awaiting an unspecified better run.

## Current scope

FerriteCore bounded empty quad capacity (#298), Decocraft corner reuse V2
(#296), Sodium classification (#299/#300), generated-layer arithmetic (#301),
and validated multipart union (#305) need explicit dispositions under this
policy. Existing production mechanisms, including Decocraft quarter-turn
geometry reuse and generated-item direct baking, are separate and remain stock
integration authority. UnionFS lexical-prefix reuse is an unimplemented source
idea, not an already validated optimization.

The interrupted laptop campaign preserved completed indices 0–9. Index 10
was incomplete. No old/new JVM measurements may be paired across the restart.
The 21-step continuation being prepared is superseded as the next decision
protocol: it measured the whole bake, not each actual optimized segment.
Keep its completed artifacts for evidence; do not silently count an interrupted
trial as a control or candidate result or relaunch the old broad suite.

## FerriteCore next boundary

Its mechanism avoids repeated `ObjectOpenCustomHashSet.rehash` after stock
generation clear/trim. Measure actual rehash CPU/wall directly, including table
size and growth count. Clear/trim and empty retained slots are adjacent costs.
Any diagnostic substitute for the table must preserve the original strategy,
load factor, canonical object identity, synchronization and clearing, must be
identical in both arms, and must never enter a production promotion.

Tests, hosted exact-pack and physical semantic/GC validation still apply. No
new timing result or candidate disposition is claimed by this policy document.
