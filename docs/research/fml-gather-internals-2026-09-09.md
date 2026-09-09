# FML gather internals — 2026-09-09

Status: **DIAGNOSTIC / STACKED / HOSTED VALIDATION PENDING**

This experiment only attributes the inclusive wall inside `fml_gather_and_initialize_mods`. It is not an optimization, an A/B, or a claim of saved time.

## Base and scope

Integration authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

The diagnostic branch is intentionally stacked on PR #212 head `9c1d6f3d7b25aeed099eee4b78c1818d94625b8d`, which carries the #200/#201/#202 structured-trace stack and the validated single-writer regular-mod bridge. The implementation here continues to write through the bootstrap-owned `StructuredBootTrace`; it does not introduce another writer, schema, sequence, or output file.

Out of scope: Discovery/ModLauncher before `CommonModLoader.begin`, post-gather registry/config/setup phases, resource reload/ModelManager, gameplay, GL/render work, and any performance optimization.

## Source map for `gatherAndInitializeMods`

For the pinned NeoForge/FML 1.21.1 line, `CommonModLoader.begin` obtains the sync executor and directly invokes:

`ModLoader.gatherAndInitializeMods(syncExecutor, ModWorkManager.parallelExecutor(), periodicTask)`.

The FML implementation then executes, in order:

1. consume current `LoadingModList` issues and fail if already invalid;
2. register the Java-version feature and wait for `backgroundScanHandler.waitForScanToComplete(periodicTask)`;
3. create the `ModList`, validate declared Forge features, and fail if invalid;
4. synchronously build `ModContainer`s from each mod file/mod info through the language loader, including loader validation, then publish the loaded containers;
5. create a `DeferredWorkQueue("Mod Construction")`;
6. call `dispatchParallelTask("Mod Construction", ...)`;
7. after that parallel gate opens, execute `Mod Construction: Deferred Queue` on the supplied sync executor and wait for it;
8. return to `CommonModLoader.begin`.

`dispatchParallelTask` is dependency-gated rather than a flat fan-out. It iterates the sorted mod list, looks up each declared loading dependency's already-created future, builds `CompletableFuture.allOf(depFutures).handleAsync(..., parallelExecutor)`, and only then calls `modContainer.constructMod()` followed by `FMLConstructModEvent`. A failed dependency short-circuits its dependents with `DependentFutureFailedException`. FML then gathers all futures and the main thread repeatedly runs `periodicTask` while waiting in 50 ms timed waits.

Therefore the `Mod Construction` wall is a **parallel critical-gate wait**. It is not CPU time, not task-sum, and not the sum of per-mod construction durations. This diagnostic intentionally does not emit per-mod events.

## Hook decision

Direct transformation of `net.neoforged.fml.ModLoader` remains a no-go: #202 already demonstrated that this class lives in `MC-BOOTSTRAP/fml_loader` and ordinary BootOptim transformation-service matching does not reliably instrument it. This experiment does not repeat or widen that matcher.

The safe observable boundary is the already validated GAME-layer callsite in `net.neoforged.neoforge.internal.CommonModLoader`. The call descriptor is pinned exactly to:

`(Executor, Executor, Runnable)void`.

The transformer now fails open unless exactly one owner/name/descriptor match exists. At that one callsite it leaves both executors untouched and replaces only the operand-stack value for the third argument with a trace-only delegating `Runnable`. The original local variable is not rewritten, so later registry/config/setup calls continue receiving the original callback object.

The wrapper calls the original `periodicTask.run()` exactly once, first, on the same thread. Only after normal callback return does it read `StartupNotificationManager.getCurrentProgress()` and observe exact progress-bar names. It emits no event on ordinary ticks and no event per mod.

Observed transitions create three child tasks under the existing outer gather task:

- `fml_gather_pre_construction`: gather entry until the first observed `Mod Construction` progress gate. By source this contains the background-scan join, ModList creation, feature validation, synchronous container construction/loader validation/publication, and small construction-dispatch setup. There is **no safe public boundary in this design to split those components further**.
- `fml_mod_construction_parallel`: first observed `Mod Construction` gate until the first observed `Mod Construction: Deferred Queue` gate. This is inclusive wall while dependency-gated mod futures execute and the caller waits/ticks.
- `fml_mod_construction_deferred_queue`: deferred-queue gate until normal return from gather. The deferred queue itself is scheduled on the existing sync executor exactly as stock FML does.

The progress bars are existing FML API state created by `dispatchParallelTask`/`waitForTask`; BootOptim does not create, complete, reorder, or mutate them.

## Semantic and overhead constraints

- default/off: no diagnostic transformer is installed, inherited from #202/#212;
- profile callback: original `Runnable` invoked once before any observation; exceptions propagate before instrumentation executes;
- executors/futures: never wrapped or replaced;
- dependency graph: never modified or awaited separately;
- callback order/thread: unchanged; observation runs after the existing callback on the same caller thread;
- classloading: the new FML progress API references live only in the bootstrap hook that is reached when trace mode installed the transformer;
- trace ownership: bootstrap/SERVICE remains the sole `StructuredBootTrace` owner/writer;
- event volume: only gather begin/end plus at most three coarse child task begin/end pairs; no per-mod emission;
- progress observation allocates the existing API's small `getCurrentProgress().toList()` snapshot on periodic ticks only in explicit trace mode. This overhead is diagnostic and is not present in production/off mode.

## Interpretation rules

The three child walls are sequential coarse attribution buckets under one inclusive gather wall. They may be compared to that parent's monotonic duration as coverage, but they are not optimization savings. `fml_mod_construction_parallel` contains overlapping worker work; it must not be added to any future per-mod task-sum. The source establishes real dependency gates, but without per-mod task IDs this trace does not identify which dependency chain is the critical chain.

A finer split of `fml_gather_pre_construction` is currently **no-go** without a new safe premise: FML exposes no progress/event boundary between background scan completion, feature validation and synchronous container construction, and the implementation class itself remains outside the reliable transformation domain.

## Tests and hosted gate

Unit coverage asserts:

- exact order `begin gather -> wrap periodic callback -> original gather -> end gather`;
- exact FML progress names map only to the construction/deferred stages;
- descriptor drift fails open;
- multiple candidate callsites fail open;
- direct FML `ModLoader` remains ignored.

Requested hosted gates:

- build/package;
- normal startup with trace default/off;
- exact-pack smoke with structured trace profile, origin `hosted_exact_pack`, endpoint `main_menu`;
- JSONL must contain one header/summary, balanced parent/child task pairs, no dropped events or sink failures, and no BootOptim/Mixin startup errors.

Physical status: **sin evidencia física**. No laptop run is requested for diagnostic attribution.
