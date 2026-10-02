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
