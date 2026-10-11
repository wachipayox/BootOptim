# Lazy parent-cycle local — 2026-10-11

## Premise and history

Current integration058ac544. Diagnostic PR336 repaired hosted37149301601
validated3,292,876parent calls,3,250,040already linked (98.70%), and actual
collection-factory count equality. Counts are not savings. Raw inclusive tiny
per-call CPU remains dominated by instrumentation/clock boundaries and is NOT
a full owner budget; do not extrapolate samples or assume escaped allocations.
The first unrepaired37148156904 CPU interval also included a native allocation
query; its counts remain usable, not its cost estimates.

Prior #66/#152/#170/#171/#181/#196/#248 graph/material/scheduler experiments
are not reopened. #177 temporary material-list pool is not incorporated: its
HEAD/RETURN lifecycle lacks exception cleanup and has unbounded retention.
Current candidate targets parent cycle SET only, not the material list.

## Candidate

Default OFF: -Dboot_optim.lazyParentCycleSet=true.
Final Mixin extension recognizes one exact Guava Sets.newLinkedHashSet factory
and its sole local in a nonstatic Function-to-void BlockModel method. All reads
must be recognized original Set.add/contains/stream, counts1/1/1; reassignment,
escaping or unfamiliar consumers/branch entry between receiver and consumer
fail open. A logging line label is allowed only if not a branch/switch/handler
entry. No live graph identity cache, callback skipping, dependency cache,
texture cache, threads or render work changed.

Initialize the local to null and ensure the same concrete LinkedHashSet just
before each recognized use, retaining it in that invocation's own local.
The method still runs all original resolvers, custom geometry, overrides,
missing-parent fallback, cyclic logging and exceptions. Separate invocations
need no pool cleanup. Ensure branch overhead on real unresolved chains and
JIT escape-analysis effects must be INCLUDED in the measurement, not waived.

Guarded transform occurs after normal Mixin application, including renamed
original bodies; unsupported actual transformed shape reports fallback and
is not a performance vote. Existing strict-path transform remains independent.

## Measurement

Default OFF: -Dboot_optim.profileParentCycleStage=true.
One CPU/wall/thread-allocation clock around the original ModelBakery constructor
Collection.forEach top-level parent traversal. No individual-call observer,
duplicate resolver lookup, or replay of live mutable models. A single completed
positive CPU/allocation marker per run is required; absent/duplicate/failed
marker invalidates. This is inclusive parent-traversal CPU, not whole constructor
CPU and not an additive reload-listener duration. Allocation queries outside
CPU interval; one constant clock overhead per real traversal in both arms.

Exact-pack two controls/two candidates with identical profile stage enabled,
candidate true/control false. Check same ordered packs, atlas, reload/main-menu
markers and no BootOptim/Mixin/reload error. Candidate must report active,
control must not report activation. CPU segment first, allocation and real
startup/reload wall separately because GC/JIT interactions may affect them.
Do not claim a general startup win from factory counts or isolated synthetic
cases. Small/noisy hardware-sensitive evidence still needs physical assessment.

## Local validation and runtime boundary

Local gradlew build passed before interruption:89 executable checks recognize
actual NeoForge-patched BlockModel, compare original/transformed synthetic
callback/cycle/exception outcomes, unknown owner/consumer fallback, and helper
identity. Existing strict-path transform26checks passed. Synthetic cases are
not a full callback/modpack equivalence proof; hosted runtime gate is still
required. Diagnostic ancestry PR336 is not merged into this candidate.
No physical PC/laptop files or config are changed by this experiment.
Promotion, if warranted, must remove stage telemetry and its anchor from the
clean production branch and add a production catalog entry; otherwise retire
the candidate with evidence on integration. Never forget a default-off switch.
