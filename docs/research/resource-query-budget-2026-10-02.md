# Exact ZIP query owner budget — 2026-10-02

Diagnostic branch from integration b076c8ca. Reopens the missing evidence in
#182, not the rejected blanket snapshot premise #142. Existing #140/#141
aggregate pack/operation inclusive wall but lack exact query repetitions and
callback-excluded CPU. #303 discovery scope measures dependencies/model lookup,
not this provider enumeration. No optimization or physical campaign yet.

Scope: FilePackResources.listResources only, keyed by actual pack instance,
PackType, namespace and path. Counts successful calls, emitted outputs and
third-and-later starts; measures current-thread CPU without enabling JVM CPU
telemetry, inclusive wall and wall/CPU excluding ResourceOutput.accept.
All original suppliers, ordering, callback exceptions and return behavior stay
stock. Whole-method finally restores nested diagnostic context on exceptions.
Failed calls are counted separately; CPU unavailable is explicit via cpu_calls.
Callbacks may include nested provider work; that entire work is subtracted
from the outer provider bucket, never added as exclusive provider CPU twice.
Third-plus CPU is only a savings ceiling: misses, constructing fresh suppliers,
first indexing and repeated scans still cost work. This does not capture entry
sequences or prove an index equivalent; it cannot authorize a resource cache.

Rows are formatted after the main-menu marker, before the existing CI stop.
Diagnostic clock/callback/map overhead means this run is attribution only,
not startup performance evidence. CPU and concurrent task-wall sums are not
critical-path wall. Strong callback nesting can make timestamp overhead
material; judge coarse CPU ceilings, not tiny differences.

Property boot_optim.profileResourceQueryBudget=true, off by default. Separate
branch, never merge diagnostic machinery as production. Hosted gate: populated
query rows, cpu_calls matching successful calls when CPU telemetry available,
failed_calls=0, valid exact ordered packs, atlas/menu and no new Mixin failures.
Next decision: if third-plus owner CPU is small, close query-index front; if
material, capture ordered queries/results for a stock/snapshot/index replay.
Only then use 2 controls/2 candidates and physical storage validation if needed.

Local initial Gradle daemon failed Windows UDP bind; IPv4 JVM override allowed
compilation. This is tooling behavior, not a game or candidate failure.

## Hosted disposition
Run37068780641@7faee03d PASS: 788 exact rows, 900 successful calls,
900 valid CPU observations, 44644 callback outputs, zero failed calls,
ordered packs valid, atlas8192x8192x2, menu90695ms, Mixin0.
Owner CPU510.565594ms; inclusive wall1079.326465ms, callback-excluded
wall1040.025274ms (task sums, not critical path). Third+ only12calls,
all empty textures/fluid lookups: ownerCPU6.113751ms, GlowingTrim5.176982ms.
REJECT exact repeated-query memo: insufficient eligible work. Diagnostic
runtime removed from the active branch; no hidden default-off profiler retained.

Different-query prefix lookup is a separate changed premise. GlowingTrim74calls
ownerCPU428.806791ms, outputs39601. Offline pinned fixture central-directory
replay21916entries/74query multiset verifies2173prefixes ordered equality;
stock/snapshot/sortedprefix checksum9193906643919661600 identical. Windows
12repetition CPU quantized15.625ms: stock1000/968.75ms, snapshot140.625/187.5ms,
index15.625/0ms. These are synthetic warm replay results, not game deltas,
first index fill/heap/GC/multithread contention unproven, unordered query
multiset not a scheduling replay. Supports only a bounded candidate smoke.
Do not use6.1ms to close all unique-query indexing or physical HDD effects.
Rawquery-budget-summary.json in analysis-reload-20261002/resource-query-37068780641.
