# Lazy parent-cycle local: retired after actual owner gate (2026-10-11)

## Decision

Do not ship the lazy local set from [PR #337](https://github.com/wachipayox/BootOptim/pull/337). The experiment removed substantial allocation, but did not demonstrate a net CPU improvement in the real parent traversal. No candidate switch, helper, transform or diagnostic is added to integration. This supersedes the pending status of that experiment. No physical test requested for this implementation.

The frozen candidate was 0e9093211139958977a30b7400ccc3a98f07b267, based on integration 058ac544aef11c0c10dc32e0aeacba2e39176d40. [Exact-pack run 38133141870](https://github.com/wachipayox/BootOptim/actions/runs/38133141870) passed, as did build 38133141869 and standalone startup 38133141882. Compilation/runtime health is not a performance vote.

## Mechanism and historical context

BlockModel.resolveParents constructs a cycle LinkedHashSet even for already-linked parents. The candidate replaces the local initialization with null and ensures a fresh concrete LinkedHashSet at each recognized first use; no pool, shared graph, callback skip or worker change. The final ASM guard permits only the known local add/contains/stream shape, otherwise stock fallback.

PR #336 measured 3,292,876 parent calls, 98.70% already linked. Its expensive per-call clock samples cannot be extrapolated into a CPU budget, and collection factory counts do not demonstrate escaped allocations. This experiment therefore measured the complete original ModelBakery constructor parent traversal with one matching CPU/wall/allocation scope in both arms. Allocation queries are outside the CPU interval; helper, resolver and callback costs are included. Local validation passed 89 executable checks (actual patched class recognition plus synthetic callbacks, cycles, reentrancy/exception cases); these are not a full in-world equivalence proof.

Do not conflate this with material-list PR #177: its incomplete pool lifecycle remains a separate investigation. Prior #14/#36/#66/#152/#170/#171 results are not reopened by the counts.

## Actual two-control/two-candidate results

| Run | Parent traversal thread CPU ms | Traversal wall ms | Thread allocated bytes | Startup marker to menu ms | Reload to FancyMenu finish ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Control 1 | 493.006168 | 526.048800 | 279005792 | 82450 | 36418 |
| Control 2 | 326.496120 | 373.758067 | 277131400 | 51848 | 23478 |
| Candidate 1 | 652.975611 | 715.297920 | 18635968 | 91691 | 42024 |
| Candidate 2 | 496.516087 | 508.828128 | 19799456 | 87976 | 40320 |

Two-point medians: CPU 409.751144 -> 574.745849 ms (+164.994705 ms, +40.27%); traversal wall 449.903434 -> 612.063024 ms (+36.04%). Allocation 278068596 -> 19217712 bytes (-93.09%, about 246.86 MiB less). These are actual allocations in this scope, not retained heap or GC pause savings.

Each candidate CPU observation exceeds each control observation, although the closest pair differs by only 3.51 ms and runner variance is substantial. Startup medians 67149 -> 89833.5 ms and reload-to-FancyMenu 29948 -> 41172 ms are **not a causal +22.68 s regression claim**: different hosted VMs have visibly different throughput and pre-model phases. Two repetitions establish no statistical confidence or hardware equivalence. Nevertheless, allocation reduction alone is insufficient to retain added hot-path complexity when the actual net CPU gate shows no win. This is a conservative rejection of this implementation, not proof that every lazy allocation design must regress.

## Validity and limits

All four artifacts have one positive completed BOOTOPTIM_PARENT_CYCLE_STAGE marker. Both candidates have exactly one active transform marker; neither control has it. Ordered 14-pack selection matches the fixture; one initial reload; block atlas 8192x8192x2; zero reported BootOptim/Mixin errors; main menu reached. Java is Oracle 25.0.4 and hosted runner reports four CPUs.

Startup is measured from the BootOptim JVM uptime boundary to its single main_menu marker, matching result.json, not Gradle/launcher/archive preparation. Each job uses a fresh hosted VM and benchmark directory; console and startup records have the current run timestamps and a single matching launch/menu. No stale report or extra reload is used. The parent traversal is an inclusive single-thread scope: do not add it to overlapping listener durations. Generic pack ERROR messages are not asserted absent; the gate reports BootOptim/Mixin health and selected packs, not complete visual/in-world equivalence.

No laptop, fast-PC visual/gameplay or GC-specific experiment was performed. Do not assert allocation-only savings improve HDD startup or FPS. Raw artifact names: exact-pack-result-control-1/2, exact-pack-result-candidate-1/2 and exact-pack-summary under the linked run. Local checked extraction and validated-parent-summary.json: C:/BootOptimBench/analysis-reload-20261003/parent-cycle-38133141870.

## Reopening criteria

A materially different implementation must demonstrate lower net traversal CPU including added branches and JIT effects, or a deliberately chosen and measured memory/GC benefit worth its cost. Inline control flow/JIT behavior is a possible investigation, **not a demonstrated fix**. Do not repeat this exact helper merely because millions of factory calls disappeared. Any reopened candidate needs current-graph callback/cycle/error invariants, actual activation, comparable owner metrics first and physical evidence when necessary. No forgotten disabled production option is retained.
