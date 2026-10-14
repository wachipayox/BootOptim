# Boot pipeline program — objective and methodology

Status: **ACTIVE PROGRAM / architectural scope**

## Objective

BootOptim is not limited to shaving isolated milliseconds from individual
listeners. Its objective is to make the exact modpack reach a visually usable
main menu in a time that is reasonable for both the fast development PC and
the old HDD laptop, while preserving the final game behavior.

The working objective is to make startup cost scale with the data that really
has to be processed, rather than superlinearly with the number of mods,
callbacks, transformations, repeated resource opens or artificial barriers.
The provisional direction-of-travel budgets are under one minute on the fast
PC and roughly two to three minutes on the old laptop; these are not acceptance
claims until the new trace establishes the real budget and critical path. Six
minutes on the laptop and 80–120 seconds on the fast PC are explicitly treated
as architectural failures, not normal variance.

## What “the same game” means

An invasive implementation may reorder, split or replace internals of
NeoForge, ModLauncher or Minecraft, and may ship an early transformation
service/coremod inside the modpack. It is acceptable to change lifecycle
implementation when all of the following remain true:

- registry, tag, recipe, model, texture, shader and entity results are
  equivalent;
- observable mod callbacks keep their required order and receive the current
  generation of objects;
- GL/render-thread work remains on its owner thread;
- entering a world and the first use of deferred content do not acquire an
  unacceptable hitch;
- unsupported or unknown mods use a stock-compatible fallback;
- a missing, changed or corrupt cache cannot prevent stock loading;
- steady-state gameplay, visuals, interactions and performance do not regress.

The optimization target is therefore the implementation of loading, not the
contents of the game.

## Working model of the boot pipeline

Every startup phase must eventually be classified into one of these lanes:

1. **Early loader:** process/JVM origin, ModLauncher, discovery, class
   transformation and FML bootstrap.
2. **Pure preparation:** parsing, indexing, dependency resolution and other
   work that consumes immutable inputs and produces immutable data.
3. **Resource/model preparation:** blockstates, models, ModelBakery, CIT,
   atlases and pack I/O; this is currently the largest physical gate.
4. **Ordered commit:** event callbacks, registry publication, lifecycle state
   and mutable compatibility APIs.
5. **GL/render commit:** shader/program/texture creation and owner-thread
   publication.
6. **Menu-only work:** title-screen resources and first visual readiness.
7. **World-only/first-use work:** entity/render state, MCEF consumers and
   other content that is not needed to show the menu.
8. **Unknown stock lane:** anything whose purity, lifecycle or callback
   contract has not been proved.

The long-term runtime architecture is a `prepare → barrier → commit` pipeline.
Pure preparation may be parallelized, cached or moved before a barrier. Commit
keeps the stock observable order. Unknown work is never guessed into a worker.

## Measurement and observability

The project needs a first-class boot trace, not only a text log. In development
mode BootOptim should emit structured events with a task ID, parent/dependency
IDs, phase, thread, start/end timestamps and resource/mod identity:

```text
phase_begin / phase_end
task_begin / task_end
barrier_wait / barrier_open
commit_begin / commit_end
blocked_on
mod_callback
resource_open / resource_parse
error / fallback
```

The external development window is a sidecar, not part of the benchmark
critical path. It should consume a named pipe, local socket or append-only
JSONL stream and show:

- current phase and active task;
- task/dependency graph and current critical path;
- what the main thread is waiting for;
- mod/resource/model currently being processed;
- CPU, GC, heap, I/O and process/JVM age;
- errors, fallbacks and repeated work;
- title readiness and first-world/first-use endpoints.

The same trace must be available as `bootoptim-trace.jsonl` for offline agent
analysis. Benchmark mode disables the sidecar and detailed per-task events,
leaving only fixed low-overhead markers. No timed run may read its own log or
query the sidecar while Java is alive.

Every result records:

- exact process/BootOptim origin;
- exact endpoint (`main_menu_opening` and `main_menu_presented` separately);
- launcher/setup/JVM preparation outside the clock;
- phase wall, CPU and GC fields separately;
- inclusive/task-sum versus ordered critical-path wall;
- JVM identity, pack/config/JAR hashes and cache state.

## Mod-scaling methodology

The modpack must be treated as a controlled experiment, not a single opaque
binary.

### 1. Establish a reproducible baseline

Freeze the exact enabled-mod/resource-pack/config snapshot. Verify one
distributable BootOptim JAR, one JVM argument set and one measurement origin.
Run the full pack in hosted exact-pack CI first, then use the physical laptop
only for effects that are storage, native, GPU or low-end CPU sensitive.

### 2. Build an incremental mod-scaling matrix

Create manifests for:

- vanilla/loader baseline;
- loader plus dependency closure;
- the full pack;
- individual mods or dependency bundles;
- high-risk interaction groups (for example Decocraft + model consumers,
  CITResewn + item models, EMF + entity renderers).

Disabling a mod must also disable or preserve its required dependency closure;
otherwise the result is invalid. Each variant records phase vectors, not just
total startup:

```text
root/FML, discovery, transform, mod entrypoint,
resource reload, ModelManager, ModelBakery, CIT,
ordered apply, renderer/GL, menu and first-world endpoints
```

Use one-at-a-time runs for initial attribution, binary/factorial groups for
interactions, and repeated paired runs only after a phase has been shown to
move. A mod that makes ModelBakery grow by 500% becomes a source-level target:
identify its callsites, repeated reads/allocations/callbacks, and minimal
reproducer before writing an optimization.

### 3. Follow a fixed optimization ladder

For every confirmed scaling source, attempt in this order:

1. configuration/resource-selection fix;
2. simple direct source fix in a user-owned fork, when applicable;
3. reload-local cache/index with exact invalidation;
4. BootOptim mixin or transformation-service bridge;
5. detached immutable prepare/ordered commit;
6. persistent fingerprinted cache;
7. invasive NeoForge/Minecraft lifecycle or scheduler rewrite;
8. hardware-adaptive policy with stock fallback.

Do not skip directly to a cache because a counter is large. Do not abandon a
family merely because one implementation failed; close the specific premise
and record the materially different reopening condition.

### 4. Validate behavior, not only startup

Every candidate that reaches the physical gate must pass:

- main-menu visual/resource validation;
- world entry and first-world frame;
- representative blocks/models/entities from the affected mod;
- recipes, tags, interactions and resource reload;
- WebDisplay/MCEF if the candidate can affect its consumer path;
- no new BootOptim/Mixin errors or fallback storms;
- no measured steady-state gameplay/FPS regression attributable to the change.

For a mod-specific candidate, the minimum proof is: stock mod versus candidate
mod, earlier menu, same phase markers, same affected content rendered and
usable in-world, and no semantic mismatch. A faster menu with broken Decocraft
blocks is a failure.

## Agent methodology

Agents are secondary research workers, not substitutes for the local authority.
Each task is bounded to one subsystem and must include the current integration
SHA, relevant PRs/docs, physical measurements and explicit no-go traps. Agents
may create branches, add probes and run GitHub Actions, but may not assume
private files or laptop state. The 25-minute response rule remains mandatory:
an incomplete task must return exactly `incompleted task for time`.

The primary agent maintains the local relay memory, checks overlap before each
assignment, processes every response into the ledger, and never promotes an
agent's microphase result without critical-path and semantic evidence.

## Acceptance gates

### Diagnostic gate

The mechanism is active, attributed to the intended mod/phase, and has no
measurement-origin or instrumentation contamination.

### Hosted candidate gate

Build, startup and exact-pack smoke pass; the candidate/control have the same
pack/config/JVM origin; markers prove activation; at least three fresh paired
repetitions show a coherent movement in the relevant critical-path metric.

### Physical gate

Use the laptop only when hosted evidence is coherent or the mechanism is
explicitly HDD/page-cache/native/GPU sensitive. Do not repeatedly reboot or
purge caches unless that is the specific experimental variable. The physical
run must use one verified artifact, one effective config, no duplicate JARs,
and offline log analysis after Java exits.

### Promotion gate

Promote only when the full-pack startup improves and affected gameplay remains
equivalent. Production code is default-on only when its safety domain is
proved; otherwise it is opt-in or remains diagnostic. Every retained change
gets a catalog entry with kill-switch, invalidation and version assumptions.

## Immediate program priorities

1. Build the structured trace/sidecar and use it to map the complete critical
   path from loader origin to menu and first world.
2. Build the mod-scaling matrix and identify which mods/families create the
   largest phase deltas, starting with ModelManager/CIT/Decocraft/EMF and
   early FML/ModLauncher.
3. Reconstruct the largest confirmed bottleneck with prepare/commit or
   persistent-structure techniques rather than another global micro-cache.
4. Treat Flywheel and other small isolated candidates as opportunistic work,
   never as substitutes for the architectural rewrite.
5. Re-test the final candidate on the physical laptop and verify in-world
   behavior before calling the project complete.
