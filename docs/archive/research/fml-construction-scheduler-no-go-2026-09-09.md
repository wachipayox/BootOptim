# FML mod-construction scheduler audit — 2026-09-09

Status: **REJECTED / NO RUNTIME CANDIDATE**

## Scope and authority

Integration authority at start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This audit is limited to NeoForge 21.1.248 / FancyModLoader 4.0.43 `fml_gather_and_initialize_mods`, specifically the dependency-gated mod-construction phase. It does not change resource reload, ModelBakery, GL/render ownership, gameplay, discovery, or later lifecycle phases.

Prior evidence is PR #218. Its valid hosted exact-pack profile (`34294584017`, artifact `10082706038`, origin `hosted_exact_pack`, endpoint `main_menu`) measured:

- `fml_gather_and_initialize_mods`: 5,146.160 ms inclusive wall;
- pre-construction: 104.218 ms;
- `fml_mod_construction_parallel`: 5,039.885 ms inclusive dispatch-gate wall;
- deferred queue: 2.021 ms;
- menu: 89,666 ms process-origin.

The 5,039.885 ms value contains future-graph setup, overlapping worker execution and dependency waits. It is not CPU time, a sum of mod costs, a critical-chain duration, or a savings ceiling.

## Exact scheduler contract

FancyModLoader's public `1.21.1` branch matches the scheduler shape established by #218 and by the 4.0.43 runtime contract:

- `ModLoader.gatherAndInitializeMods(...)` builds/publishes all `ModContainer`s before construction.
- `constructMods(...)` dispatches one task whose body is `modContainer.constructMod()` followed by `modContainer.acceptEvent(new FMLConstructModEvent(...))`.
- `dispatchParallelTask(...)` walks `ModList.getSortedMods()`, creates one future per mod, obtains `LoadingModList.getDependencies(modInfo)`, and schedules `CompletableFuture.allOf(depFutures).handleAsync(..., parallelExecutor)`.
- a dependent is therefore eligible immediately when its last direct predecessor completes; there is no level/batch barrier.
- if any predecessor future fails, the dependent does not run and becomes exceptional with the internal dependent-failure marker. The final gather preserves root failures while filtering those marker exceptions.
- `waitForFuture(...)` ticks the existing periodic callback and calls `future.get(50 ms)`. The timeout does not delay a future that has already completed and does not gate dependent scheduling; workers/future completions proceed independently of the waiting caller.

The dependency graph is not the set of all REQUIRED/OPTIONAL version dependencies. `ModSorter` adds scheduling edges only for explicit ordering constraints and configured ordering overrides:

- dependency declaration `BEFORE`: owner -> target;
- declaration `AFTER`: target -> owner;
- `Ordering.NONE`: no construction edge;
- absent soft dependency: no edge;
- an added dependency override creates an ordering edge.

`LoadingModList.getDependencies` returns exactly those direct predecessors. Thus removing an existing edge would remove an explicit mod load-order contract; conversely, a required dependency with no ordering constraint is already allowed to construct concurrently.

## Node semantics: why generic prepare/commit is unsafe

For JavaFML, `FMLModContainer` construction happens before the parallel gate and already performs event-bus creation, module resolution and `Class.forName(layer, entrypoint)`. #218 measured the entire pre-construction prefix at only 104.218 ms inclusive hosted wall, so moving that work cannot explain the ~5.040 s construction gate and has at most the prefix's measured hosted ceiling.

Inside each dependency-gated node, `FMLModContainer.constructMod()`:

1. resolves the public constructor and constructor argument types;
2. invokes the actual mod constructor;
3. after all entrypoints, calls `AutomaticEventSubscriber.inject(...)`;
4. the enclosing FML task then posts `FMLConstructModEvent` to that container.

Only small reflection/argument bookkeeping before constructor invocation is plausibly local preparation. Constructor invocation, class/static initialization reached from it, event-bus registrations, automatic subscriber injection and `FMLConstructModEvent` are observable side effects and arbitrary mod code. Moving any of them before predecessor completion or publishing them later changes the declared ordering contract unless that individual mod proves independence/cooperation.

## Candidate designs evaluated

### 1. Replace CompletableFuture DAG with an indegree/ready-queue scheduler

A hand-written adjacency/indegree scheduler can preserve graph topology and root-failure propagation, but it cannot make any node runnable earlier than stock: stock already schedules each node as soon as its last predecessor future completes. The only theoretical saving is O(V+E) allocation/bookkeeping (`IdentityHashMap`, dependency arrays and CompletableFuture continuations). That is scheduler micro-overhead, not a new critical-path overlap mechanism, and #218 did not measure it as material. Rewriting a mature failure/progress/context scheduler for that ceiling adds high compatibility risk without evidence of multi-second leverage.

### 2. Split each mod into local prepare -> ordered publish/construct

The only generic pure-looking work is constructor metadata/argument preparation. It is small by source shape and is not separately measured as material. The expensive/unknown body is arbitrary constructor/subscriber/event code and cannot be moved across ordering edges. Moving serial `Class.forName` from container build into workers would change class-initialization order and `ModLoadingContext` timing; it also attacks a prefix whose entire hosted inclusive wall is 104.218 ms.

### 3. Weaken dependency edges

Rejected semantically. An existing scheduler edge represents `BEFORE`/`AFTER` or an explicit dependency override, not an inferred conservative dependency. Removing it contradicts metadata. FML already runs `Ordering.NONE` dependencies concurrently, so there is no generic over-serialization to relax.

### 4. Remove/relax the 50 ms wait loop

Rejected as a false premise. `CompletableFuture.get(timeout)` returns immediately when complete, and dependent `handleAsync` continuations are scheduled by completion threads, not by the Render-thread polling loop. The loop is a UI/error-observation mechanism rather than a dependency release barrier.

## Compatibility risk

The pack has prior evidence that stock construction parallelism can expose mutable-global races: PR #25/#28 documents the Create Railways Navigator / Ponder `StitchedSprite` race and the narrow thread-safety backport. Increasing concurrency or changing ready-task ordering without a demonstrated dependency is therefore not only semantically unproven but concretely compatibility-sensitive in this workload.

## Decision

**REJECT generic FML scheduler reconstruction / generic prepare-commit split for NeoForge 21.1.248.** No runtime code is added, so there is no exact-pack smoke or A/B to run. A hosted A/B of a ready-queue rewrite would test micro scheduler overhead while assuming equal task readiness; it would not test the requested invasive critical-path premise.

The established ~5.040 s dispatch wall remains a container for real mod construction work plus waits, not evidence of artificial scheduler serialization.

## Reopening criterion

Reopen only with one of these materially different premises:

1. an exact-pack critical-chain attribution identifies a specific mod or ordered edge that dominates wall, and the affected mod's source proves a substantial side-effect-free preparation product independent of its predecessor's constructor/publication;
2. a mod cooperates explicitly by exposing prepare/commit APIs with generation/order/failure semantics;
3. direct measurement proves scheduler bookkeeping itself is a material fraction of the critical path (not merely total allocations/counts), large enough to justify replacing FML's failure/progress/context machinery.

Any future candidate must preserve exact predecessor topology, `ModLoadingContext` active-container scope, constructor -> automatic subscriber -> construct-event order, dependent short-circuit on failure, progress/periodic callback behavior and stock deferred-queue ordering, and must be default-off/version-shape gated/fail-open before exact-pack smoke and hosted A/B.

## References

- BootOptim PR #218: https://github.com/wachipayox/BootOptim/pull/218
- BootOptim PR #202: https://github.com/wachipayox/BootOptim/pull/202
- BootOptim PR #99: https://github.com/wachipayox/BootOptim/pull/99
- BootOptim PR #28: https://github.com/wachipayox/BootOptim/pull/28
- FancyModLoader `ModLoader` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/ModLoader.java
- FancyModLoader `LoadingModList` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/loading/LoadingModList.java
- FancyModLoader `ModSorter` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/loading/ModSorter.java
- FancyModLoader `FMLModContainer` 1.21.1: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/javafmlmod/FMLModContainer.java
