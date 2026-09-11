# Artifact-unit exact-pack scaling planner — 2026-09-11

Status: **ACTIVE / diagnostic tooling only**

## Premise

PR #249 proved that loader mod IDs are not valid bisection units. One physical
JAR can expose many IDs: the pinned Forgified Fabric API artifact exposes 51,
and Create exposes its own ID plus nested Flywheel/Ponder IDs. Splitting those
IDs across arms makes the apparent root sets different while the physical JAR
selection and dependency-closed removal sets overlap. Those runs can still be
contract-valid Minecraft launches, but they are not an orthogonal experiment
for attributing a cost to the named ID block.

This follow-up changes the planner's DOE unit, not Minecraft runtime behavior.
A top-level physical JAR is indivisible. Explicit compatibility/runtime families
can collapse multiple top-level JARs into one experimental unit before a
partition is generated. Repeated logical IDs in different top-level JARs are
instead alternative physical providers for dependency closure; they do not by
themselves justify merging those outer JARs. Runtime-symbol provider edges are
applied before dependency closure. Complement materialization then records
separately the artifacts assigned to the removed arm and artifacts removed only
because their closure reaches the excluded arm.

The distinction matters in the exact pack: WorldEdit, Sodium and Iris can embed
Fabric API modules whose logical IDs also appear in the top-level Forgified
Fabric API artifact. Treating every repeated ID as physical identity creates a
false multi-JAR superunit. The planner therefore asks whether any remaining
physical provider satisfies a dependency while preserving every top-level JAR
as an independent unit unless an explicit compatibility/runtime family joins
it to another artifact.

## Plan and manifest contract

Planner schema 2 records:

- `selection_unit=top_level_physical_artifact`;
- source `pack_fingerprint` over ordered JAR names + SHA-256;
- `artifact_unit_fingerprint` over the collapsed physical units;
- complete top-level artifact inventory with SHA-256, loader IDs and unit ID;
- `partition_validation.shared_artifacts`, which must be empty;
- per-complement `assigned_artifacts`, `direct_excluded_artifacts`,
  `effective_excluded_artifacts`, and one `artifact_decisions` row for every
  source JAR.

The materializer re-scans the source before copying. A stale plan whose source
fingerprint no longer matches is rejected. For schema-2 complement variants it
also rejects duplicate artifact decisions, decisions that do not cover the
entire physical source set, or an included/excluded decision set that disagrees
with the selected JAR set.

`validate_disjoint_partition_assignments` rejects any requested partition in
which the same top-level artifact is assigned to more than one arm. Dependency
closure may still remove additional artifacts from a complement; that is
reported as induced closure and must not be interpreted as direct cost of the
assigned arm. Explicit operator exclusions retain `operator_exclusion` as their
manifest cause even if the same artifact is also assigned to the removed arm.

## Regression fixtures

The focused Python suite includes:

- an FAPI-like JAR with 51 loader IDs and proves all 51 belong to exactly one
  partition arm;
- a Create-like JAR whose nested JARs expose `flywheel` and `ponder`, proving
  those IDs cannot be split from the top-level Create artifact;
- a `create-bundle` compatibility family proving a separate Bits'n'Bobs JAR is
  collapsed with the Create physical unit before partitioning;
- a custom FAPI complement proving that naming one of the co-located IDs removes
  the whole physical FAPI artifact and records a dependent consumer as closure-
  induced exclusion;
- explicit rejection of a hand-constructed partition that shares `fapi.jar`
  between arms;
- materializer coverage/fingerprint validation and stale-plan rejection.

## Interpretation gate

Reduced-pack variants remain diagnostic workload variants. A timing from a
variant is accepted for structural attribution only when it reaches the same
hosted `main_menu` endpoint, preserves resource order with `reload_count=1`,
keeps `resource_contract_valid=true`, and has zero BootOptim Mixin errors. A
failed/timeout/resource-invalid variant is a contract result, not a fast point.

Even a valid complement does not establish gameplay equivalence or removable
startup savings. In particular, PR #249's FAPI-only and Create-bundle
complements are nested closure regimes and cannot be subtracted or summed as
per-mod costs. The source-level follow-up remains Create/FML constructor work
and the independent post-entry ModelManager/resource critical path.