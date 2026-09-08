# Exact-pack mod scaling matrix tooling — 2026-09-08

Status: **ACTIVE / diagnostic tooling only**

The architectural boot programme needs to distinguish real work from a cost that merely appears to
grow with the number of mods. Existing exact-pack CI can repeat a fixed pack, but it did not yet
produce an auditable set of per-mod and interaction variants. This change adds a read-only planner
and a safe materializer; it does not claim any startup result.

## Plan contract

```text
python scripts/exact-pack/plan_scaling.py \
  --pack-dir <extracted-exact-pack> \
  --baseline <mod-id> \
  --group decocraft=decocraft,moreculling \
  --output scaling-plan.json
```

The planner fingerprints every mod JAR, reads NeoForge/FML metadata, records required dependency
edges, and emits:

- `full`: every source JAR;
- `baseline`: the requested loader/pack baseline closure;
- one `single_mod_closure` variant per discovered mod;
- optional `interaction_group_closure` variants;
- missing dependency warnings instead of silently producing an invalid closure.

JARs containing BootOptim are rejected so the source fixture cannot accidentally benchmark a stale
or duplicated build. Metadata-free JARs remain visible as artifact-level records and are not silently
discarded.

Materialize a variant only into a new directory:

```text
python scripts/exact-pack/materialize_scaling_variant.py \
  --source <extracted-exact-pack> \
  --plan scaling-plan.json \
  --variant mod-decocraft \
  --destination <new-directory>
```

The source is never edited. The destination copies all non-JAR pack state, native/library
directories, and only selected mod JARs. `.bootoptim-scaling-variant.json` records the source
fingerprint, roots, selected artifacts and exclusions before a runner is allowed to launch.

## Measurement gate

This tooling creates workload variants; it does not make an A/B valid by itself. Each launch must
still use the exact-pack runner's same origin and endpoint, record the variant manifest and pack
fingerprint, reach the main menu without BootOptim/Mixin errors, and keep listener task-sums separate
from critical-path wall. A missing required dependency, resource fallback, stale JVM or duplicate
main-menu marker invalidates that variant rather than being averaged away.

The first runtime campaign should be hosted CI: full pack, baseline, high-value single closures and
interaction groups. Only phase deltas that remain material and coherent on CI should be sent to the
physical laptop. This is a software-pack scaling surrogate, not proof of laptop storage or native
GPU behavior.

The exact-pack workflow now exposes a diagnostic `scaling` mode. For a manual dispatch, provide
comma-separated planner IDs (for example `baseline,full,mod-decocraft`) and optional baseline/group
definitions. A pull request can request the same mode with repeated directives:

```text
[exact-pack-ci]
exact-pack-mode: scaling
exact-pack-scaling-variant: baseline
exact-pack-scaling-variant: full
exact-pack-scaling-variant: mod-decocraft
exact-pack-scaling-baseline: neoforge
exact-pack-scaling-group: render=embeddium,entity_model_features
```

Reduced variants are allowed to continue past the resource-selection check solely to expose startup
phase evidence. Their result records `resource_contract_valid=false` and `diagnostic_only=true` and
the aggregate marks those counts explicitly. Such a run cannot authorize gameplay equivalence or a
production optimization; it only identifies which startup phases disappear or scale when a mod
closure is removed.

## Validation

The planner/materializer are covered by the existing Python contract suite: metadata dependency
closure, missing-dependency reporting, duplicate BootOptim rejection, source immutability and native
directory preservation. No Minecraft process is started by either tool.
