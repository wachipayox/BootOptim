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

The fixture may contain more than one artifact declaring the same `modId` (the hosted smoke exposed
this with two `tfmg` JARs). The planner models that as one logical mod with multiple artifacts and
keeps all matching artifacts in `full` and closure variants; it does not arbitrarily discard one.

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

## First hosted attribution smoke — 2026-09-08

Run `34242754173` used commit `6e89511` and one fresh hosted repetition for
`full`, an empty `baseline`, and the single-mod closures `decocraft` and
`citresewn`. All four reached the same main-menu endpoint with
`resource_contract_valid=true`, `diagnostic_only=false`, zero BootOptim/Mixin
errors, and the same process-origin clock. The values are attribution evidence
only, not a promotion A/B:

| variant | TTMM / startup (ms) | mod entrypoint (ms) | post-mod (ms) | atlas |
| --- | ---: | ---: | ---: | --- |
| baseline | 17,394 | 12,132 | 5,262 | 1024×1024×2 |
| mod-citresewn | 21,644 | 11,523 | 10,121 | 4096×4096×2 |
| mod-decocraft | 22,405 | 11,169 | 11,236 | 8192×4096×2 |
| full | 91,444 | 30,703 | 60,741 | 8192×8192×2 |

The isolated closures for CITResewn and Decocraft are therefore inexpensive
relative to the 74,050 ms full-minus-baseline delta in this run. This does not
close their interaction paths, nor does it prove linearity: the next matrix
must test high-value consumers and interaction groups with the corrected
NeoForge dependency parser. FancyMenu/reload and panorama fields are absent in
reduced variants, so those fields must not be compared as if they were present
workloads.

## Second hosted attribution smoke — 2026-09-08

Run `34243601916` used the corrected planner and one fresh repetition for
individual closures and three interaction groups. Every completed variant had
`resource_contract_valid=true`, `diagnostic_only=false`, zero BootOptim/Mixin
errors and the same process-origin endpoint:

| variant | TTMM / startup (ms) | mod entrypoint (ms) | post-mod (ms) |
| --- | ---: | ---: | ---: |
| mod-moreculling | 13,827 | 9,702 | 4,125 |
| mod-entity_model_features | 15,062 | 9,523 | 5,539 |
| mod-modernfix | 14,994 | 10,731 | 4,263 |
| mod-mcef | 15,391 | 9,278 | 6,113 |
| mod-fancymenu | 16,263 | 9,251 | 7,012 |
| group-render (MoreCulling + EMF) | 20,618 | 12,158 | 8,460 |
| group-model (Decocraft + MoreCulling + EMF) | 27,559 | 12,778 | 14,781 |
| group-early (ModernFix + MCEF) | 20,511 | 12,916 | 7,595 |

The second smoke reinforces the interaction hypothesis: the known expensive
listeners do not approach the full-pack 91,444 ms by themselves or in these
small groups. This is not evidence that they are irrelevant in the full pack;
it means the next experiment must partition the remaining 160 artifacts into
larger functional groups (Create/content, client/render, resource/model and
UI/utility) before selecting an optimization target.

## Automatic broad partitions

The planner now supports `--balanced-partitions N`. It assigns the sorted mod
ID inventory to deterministic round-robin root partitions and computes a full
required-dependency closure for each partition. A dependency can therefore be
present in multiple partitions when the metadata requires it; a partition with
missing dependencies is not launchable and must not be treated as a clean
performance point. The planner now excludes such roots from the runnable
closure, records them in `excluded_roots`, and keeps the original assignment in
`roots`; this makes the coverage loss explicit instead of silently dropping a
mod. A partition with exclusions remains attribution-only and is not evidence
that the excluded mod is cheap. This keeps the experiment reproducible without
hand-maintaining another list of 160 IDs. The next hosted run should compare
`baseline`, `full`, and four `partition-*` variants with one fresh repetition;
the goal is localization of the broad scaling block, not a product claim.

The first broad-partition attempt exposed a second contract class before any
optimization could be judged: a reduced set can satisfy declared required
dependencies and still be invalid. Iris reached a Sodium API class without
Sodium in one partition; another activated AnalogAudio without its `flite`
native library; another let Bits'n'Bobs reference Create classes that were
absent. These are pack-validity failures, not startup measurements. The
planner therefore keeps such variants out of the performance ledger and now
includes optional dependencies that are actually present in the source pack,
while recording unmaterializable roots explicitly.

### Run 34245748631 disposition

The corrected broad-partition run produced two valid endpoints and four
contract failures. `baseline` reached 15,362 ms and `full` 91,877 ms; both had
`resource_contract_valid=true`, `diagnostic_only=false` and zero BootOptim/Mixin
errors. No partition TTMM is admitted to the ledger: partition-1 and
partition-4 failed in `NarratorLinux` because hosted Linux lacks `libflite.so`,
partition-2 reached a reduced-pack crash where Iris referenced Sodium's
`VertexSerializer` without Sodium, and partition-3 crashed because
Bits'n'Bobs referenced Create's `TooltipModifier` without the Create family.
The run was cancelled after all benchmark jobs had terminated but before the
aggregate could make a misleading summary. These failures motivate the next
planner revision: include present optional dependencies and preserve explicit
compatibility-family exclusions before trying another broad attribution run.
