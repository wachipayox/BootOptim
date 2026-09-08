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

The planner also emits `complement-1` … `complement-N` when `--balanced-partitions N` is used. A
complement keeps the rest of the exact pack and removes one deterministic block of roots; known runtime
compatibility families are removed together. If a surviving root requires an omitted or undeclared runtime
partner, that root is recorded in `excluded_roots` with `depends_on_excluded_or_missing` instead of being
silently included. Complements are the preferred next attribution workload because they preserve the
resource, renderer and UI environment much more faithfully than a subset-only partition. They still need
the same menu, resource-contract and zero-error gates before their timings enter the ledger.

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

The closure reader now also inspects JARs nested under `META-INF/jarjar`. This
matters for the pinned pack: Create 6.0.10 embeds Flywheel 1.0.6 and Ponder
1.0.82, so those IDs are provided by the Create artifact and must not be
reported as missing when a reduced closure includes Create. The materializer
still copies one top-level artifact and never extracts or edits nested JARs.

Some compatibility is intentionally not declared in loader metadata. The
planner therefore accepts explicit, evidence-backed runtime families:

```text
--compatibility-group render=iris,sodium
--compatibility-group create-ui=bits_n_bobs,create
```

When a member is selected, the whole family is included in that closure. A
host-specific root can be omitted from broad partitions with
`--exclude-root analogaudio`; the exclusion is recorded rather than silently
treated as a fast measurement. The hosted workflow exposes these as
`scaling_compatibility_groups` and `scaling_excluded_roots` (or the matching
`exact-pack-scaling-compatibility-group` / `exact-pack-scaling-exclude-root`
PR directives).

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

### Run 34247227311 disposition

The optional-dependency revision did not make partition-2 runnable: Iris 1.8.14
does not declare Sodium in `neoforge.mods.toml`, although its runtime classes
reference Sodium's API. Partition-3 showed the same class of issue for the
CreateBitsNBobs integration. This is evidence for explicit compatibility
families, not evidence against Iris or Create. The next planner revision also
recognizes nested `META-INF/jarjar` mods, which removes false missing-dependency
reports for Create's embedded Flywheel/Ponder. The Linux `libflite.so` failure
from AnalogAudio remains a host capability issue and must be excluded and
recorded for hosted partition attribution.

### Run 34248612360 disposition

The next broad run used four deterministic partitions plus `baseline` and
`full`, excluded `analogaudio`, and kept the already-known Iris/Sodium and
Bits'n'Bobs/Create families together. The valid variants reached the same
process-origin/main-menu endpoint with `resource_contract_valid=true`,
`diagnostic_only=false` and zero BootOptim/Mixin errors:

| variant | TTMM / startup (ms) | mod entrypoint (ms) | post-mod (ms) | reload → FancyMenu (ms) |
| --- | ---: | ---: | ---: | ---: |
| baseline | 14,509 | 9,798 | 4,711 | — |
| partition-4 | 40,691 | 20,842 | 19,849 | — |
| partition-1 | 58,622 | 27,289 | 31,333 | 21,913 |
| full | 96,055 | 32,637 | 63,418 | 44,462 |

The two remaining partitions were not admitted to the performance ledger.
Partition-2 selected `we_companion` without its undeclared runtime partner
`tfmg`; its Mixin targets were absent, and Observable also failed because the
reduced closure did not carry the Kotlin serialization runtime
(`kotlinx.serialization.json.JsonElement`). Partition-3 copied the
MoreCulling artifact through its co-located `conditional_mixin` mod without
selecting MoreCulling's declared `cloth_config` dependency. These are
materialization/contract failures, not measurements.

This run exposed an important planner invariant: selecting a top-level JAR
must close *all* loader mod IDs declared by that artifact, not only the root
ID that caused the artifact to be selected. The planner now expands
co-located IDs during transitive closure and has a regression test for a
combined artifact whose hidden helper requires a second JAR. The next
validation dispatch must also pass the evidence-backed runtime families
`tfmg-bridge=we_companion,tfmg` and
`observable-runtime=observable,kotlinforforge`; until those variants reach a
valid menu endpoint, no partition timing is interpreted.

### Run 34250484156 disposition

The co-located-artifact fix was exercised with the four compatibility families
(`render`, `create-ui`, `tfmg-bridge` and `observable-runtime`). It removed the
previous dependency omissions: both failed partitions now materialized
MoreCulling with ClothConfig, TFMG with `we_companion`, and Observable with
KotlinForForge. They still did not produce a valid endpoint, so their times are
not admitted.

The remaining failure is a runtime-family boundary. Partition-2 contained
FancyMenu but not MCEF; it completed FancyMenu's resource reload and then
remained on the loading/error-screen path without emitting the title marker.
Partition-3 contained MCEF but not FancyMenu and likewise never emitted the
title marker. Partition-3 also reintroduced AnalogAudio through a present
optional edge even though `analogaudio` was excluded as a partition root; the
hosted Linux run logged its incompatible Create registration and OpenAL
environment. This proves that a root-only exclusion is insufficient when
optional edges are materialized.

The planner is therefore being tightened in two ways before another matrix:
explicit partition exclusions block the same ID when reached through optional
dependencies, and the next dispatch keeps the runtime family
`menu-browser=fancymenu,mcef` together. The run remains contract evidence only;
no p2/p3 startup value is used for attribution or promotion.

### Run 34251855587 disposition

The follow-up run used all five evidence-backed families (`menu-browser`,
`tfmg-bridge`, `observable-runtime`, `render`, and `create-ui`) and excluded
`analogaudio`. Both selected subset partitions materialized the expected
families, reached `Minecraft resource reload: FINISHED`, and then remained
alive for the 300-second diagnostic timeout without emitting the `TitleScreen`
marker. Their render threads were idle in `GLFW.glfwWaitEventsTimeout`, not
busy doing startup work. This is a reduced-pack lifecycle/semantic-contract
failure, not a slow startup measurement.

The baseline in the same hosted workflow reached `main_menu` at 17,034 ms and
also logged the hosted OpenAL failure, proving OpenAL is not sufficient to
explain the partition outcome. The subset variants also emitted a TFMG model
parse error, but that error is not by itself causal because the full pack has
shown the same model warning while reaching the menu. No p2/p3 time is admitted
to the performance ledger. The next matrix should select `baseline`, `full`,
and `complement-*` variants; subset partitions remain useful only as explicitly
diagnostic evidence for phase disappearance.

The first real complement run additionally exposed an omitted loader contract:
LibIPN uses `modLoader="kotlinforforge"`, which NeoForge treats as a hard
language-provider requirement rather than a `[[dependencies]]` entry. The
planner now converts non-built-in language providers into required closure
edges, so a selected Kotlin mod carries KotlinForForge even when its metadata
has no ordinary dependency table. The failed pre-fix complement is discarded;
it is contract evidence only.

For a future diagnostic rerun, the benchmark-only JVM property
`-Dboot_optim.benchmark.screenProbe=true` records every NeoForge
`ScreenEvent.Opening` class as `BOOTOPTIM_SCREEN`. It is intentionally opt-in
and must not be enabled for timed optimization A/Bs; its purpose is to tell a
reduced variant apart when resource reload completes but the client is parked
on a loading/error/custom screen instead of opening `TitleScreen`.

`-Dboot_optim.benchmark.exitOnLoadingError=true` is a second diagnostic-only
property. If NeoForge actually opens `LoadingErrorScreen`, BootOptim records the
screen and stops the client immediately. It exists to prevent an already-invalid
reduced variant from consuming the full hosted timeout; it is never enabled in
a performance comparison.

Scaling-mode launches have a three-minute ceiling. The complete hosted pack has
already reached the same menu endpoint in roughly 90 seconds, so a reduced
variant that does not reach it within 180 seconds is not a comparable
attribution point; its timeout is recorded as a contract/lifecycle failure, not
as a startup time.

### Run 34255651108 disposition and Fabric-port closure fix

The post-language-provider complement dispatch still produced invalid variants,
but its logs yielded a concrete planner defect rather than performance data.
Complement 2 retained Furnish and MusicMaker; complement 3 retained Furnish and
Joy of Painting. NeoForge then rejected each variant because the retained
Fabric-port mods required `fabric-api`, while the planner had removed Forgified
Fabric API. The cause was that these artifacts expose only `fabric.mod.json`;
the planner only read FML TOML and invented filename-derived roots, so it could
not see either their real IDs or their dependency contracts. Forgified Fabric
API itself declares `provides = ["fabric", "fabric-api"]`, an alias which must
resolve to the same artifact but must not become an additional partition root.

The planner now reads `fabric.mod.json` (top-level and nested), treats
`depends` as required closure edges, treats `fabricloader` and `java` as
runtime-provided, and resolves NeoForge `provides` aliases without inflating the
root count. A regression fixture proves that a Fabric mod requiring
`fabric-api` carries the one Forgified API artifact. These failed timings remain
invalid. The next complement run must use this parser before any attribution is
attempted.

Complement 1 exposed a separate, genuine undeclared execution edge:
CreateStuffNAdditions (`create_sa`) was retained while Create was removed, then
failed with `NoClassDefFoundError` for
`com/simibubi/create/content/processing/sequenced/SequencedAssemblyItem`.
This is not repaired by the Fabric metadata fix. It is evidence that the next
planner increment needs an explicit, auditable mechanism for runtime-symbol
provider edges (rather than accumulating accidental timing results or silently
declaring the complement valid).

That mechanism is now available as the repeatable planner/workflow input
`runtime-symbol-provider` / `scaling_runtime_symbol_providers` in the form
`MODID=internal/jvm/package/`. It scans `.class` payloads (including nested
JARs) and adds a required edge only when a selected artifact actually contains
that internal-name prefix. The provider is explicitly named by the operator:
the scanner does not guess arbitrary library ownership. The generated plan
records every applied artifact/mod/provider edge. The exact local
CreateStuffNAdditions artifact does contain `com/simibubi/create/`, confirming
that `create=com/simibubi/create/` will close the observed failure and also
cover other exact-pack artifacts with the same real bytecode reference.
