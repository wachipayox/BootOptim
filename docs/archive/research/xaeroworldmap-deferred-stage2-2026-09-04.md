# Xaero World Map deferred Stage 2/2 — 2026-09-04

Status: **REJECTED ATTRIBUTION FROM BOOTOPTIM / NO OPTIMIZATION OR DEFER CLAIM**

Base: refreshed `agent/integration-current` @ `ad39b13824d71f6308050e8932f249dd18238923`.

PR: #100.

## Initial evidence and limit

The exact pack contains Xaero's World Map `1.41.0` and XaeroLib `1.1.15`. One uninstrumented hosted exact-pack run (`33917611497`) emitted:

```text
Mod 'xaeroworldmap' took 1.026 s to run a deferred task.
```

This is an inclusive wall measurement for one FML deferred task. It is not a 1.026 s recoverable-TTMM claim and does not identify the task's internal cause.

The exact current runtime is NeoForge `21.1.248` with FML loader `4.0.43`. Runtime stacks place the task invocation at `DeferredWorkQueue.java:67`, matching the inspected FML source shape where `ti.task.run()` is inside `makeRunnable` after `setActiveContainer(ti.owner)` and before the owner clear.

Xaero World Map is not a repository controlled by the authenticated `wachipayox` account. Any future BootOptim compatibility would therefore need exact-version guards, fail-open behavior, and semantic equivalence.

## Rejected boundary #1 — Stage marker to slow-warning text

The first diagnostic started a 5 ms Render-thread sampler from Xaero's Stage 2/2 log and attempted to stop it by parsing FML's slow-task warning.

Exact-pack run `33924496490` reached title with zero BootOptim Mixin errors but ended:

```text
BOOTOPTIM_XAERO_DEFERRED_PROFILE status=complete reason=timeout wall_ms=5003 samples=866
```

The thresholded warning disappeared in that instrumented run. The five-second safety window therefore mixed unrelated later work, including hundreds of `glBlitFramebuffer` samples and additional `Net.poll` samples. All stacks from that window are invalid for Xaero attribution.

Do not reuse log/warning parsing as a completion boundary.

## Rejected boundary #2 — normal mixin on FML owner state

The second diagnostic observed the existing `ModLoadingContext.setActiveContainer(owner/null)` calls with a non-required client mixin.

Exact-pack run `33926828559` reached title with zero BootOptim Mixin errors, but the diagnostic ended:

```text
reason=title_without_observation samples=0
```

Xaero still executed Stage 2/2. The FML loader class was already outside the normal game/mod mixin lifecycle when BootOptim attempted to observe it.

Do not retry an ordinary mod/client mixin against this loader-side boundary.

## Rejected boundary #3 — SERVICE-layer transformer of DeferredWorkQueue

The third diagnostic moved the structural hook to BootOptim's existing ModLauncher `ITransformationService`. It targeted only `net/neoforged/fml/DeferredWorkQueue`, required the stock `Runnable.run()` shape, and would have inserted timestamp markers without wrapping or replacing the runnable.

Exact-pack run `33927425430` completed successfully:

- main menu: `99.675 s`;
- BootOptim Mixin errors: `0`;
- Stage 1/2 still occurred on `Worker-ResourceReload-1`;
- Stage 2/2 still occurred on `Render thread`;
- profiler result: `status=unavailable reason=boundary_fields_missing`.

Artifact: `exact-pack-result-smoke-1`, id `9957398952`, SHA-256 `00ad4d280fde50796750d12302e32a093cbe219736bc556aef54070b60fbd470`.

No `BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=applied` marker was produced. The SERVICE transformer did not receive this already-bootstrap FML class. This smoke is therefore an instrumentation rejection, not Xaero attribution.

The transformer and runtime profiler were removed from the branch after this result.

## Boundary #4 evaluation — observe the stock TaskInfo CompletableFuture

The fourth proposal was to locate the exact `DeferredWorkQueue.TaskInfo` whose owner is `xaeroworldmap`, observe its stock `CompletableFuture`, and derive an exact start/complete window without wrapping, replacing, reordering, or modifying the runnable.

### Exact FML object graph

In FML 4.0.43's inspected `DeferredWorkQueue` shape:

- `tasks` is a private `ConcurrentLinkedDeque<TaskInfo>`;
- `TaskInfo` is a private static nested class;
- `TaskInfo.owner`, `task`, and `future` are private;
- `enqueueWork(ModContainer, Runnable)` creates a `CompletableFuture.runAsync(...)` using a custom executor that stores the generated internal runnable in `taskInfo.task` rather than executing it immediately;
- the future is assigned to `taskInfo.future`, the `TaskInfo` is added to the private deque, and only the future is returned to the caller;
- later `runTasks()` synchronously reaches the exact start boundary at `ti.task.run()`.

`ParallelDispatchEvent` also stores its `DeferredWorkQueue` in a private final `workQueue` field. Its public `enqueueWork(...)` method only delegates to `workQueue.enqueueWork(getContainer(), work)` and returns the future.

`ModLoader.dispatchParallelEvent(...)` creates the queue as a local variable, constructs each per-mod event with that same queue, finishes parallel event dispatch, and then executes `workQueue::runTasks` on the synchronous executor. FML exposes no public queue/task-info enumeration or task-start callback.

### What code can see the future

A game-layer hook on Xaero's own setup callsite could, in principle, duplicate the return value of `ParallelDispatchEvent.enqueueWork(...)` and give Xaero the exact same `CompletableFuture` object unchanged. Because the event's container is passed directly to `DeferredWorkQueue.enqueueWork`, that callsite could also prove the enqueued owner is exactly `xaeroworldmap`.

That is still insufficient for the requested boundary.

The returned `CompletableFuture` exists before deferred execution begins. It remains incomplete while waiting in the queue and remains incomplete while the task body is running. Public `CompletableFuture` state therefore does not expose the transition from **queued** to **executing**. The exact start state lives at `ti.task.run()` / active-owner assignment inside `DeferredWorkQueue`, which is the loader-side boundary already shown to be unavailable to BootOptim's normal mixins and SERVICE transformers.

### Why completion observation does not repair the missing start

Possible completion observers do not satisfy the gate:

- `whenComplete(...)` adds a dependent completion action to the stock future. It executes on the future's completion path and adds diagnostic work to the FML-timed task tail; it still supplies no exact execution-start transition.
- polling `isDone()` observes completion only after watcher scheduling/poll latency and still cannot distinguish queued from executing before completion;
- a dedicated `join()` waiter adds a waiter to the future and timestamps only after it is unparked/scheduled; it also supplies no start boundary.

A hook at `WorldMap.loadClient` HEAD/RETURN would be a different diagnostic premise: it instruments Xaero's runnable body rather than observing the stock TaskInfo future, and its return still precedes `CompletableFuture`'s own completion bookkeeping. It does not satisfy the requested "before/after the same future, finish exactly at completion" gate.

### Reflection route rejected

BootOptim could only discover the queue/TaskInfo after the fact by opening private FML internals, for example:

1. reflect `ParallelDispatchEvent.workQueue`;
2. reflect `DeferredWorkQueue.tasks`;
3. inspect private `TaskInfo.owner` / `future` / `task`;
4. then still invent a separate execution-start signal because the future has no public started transition.

That is multiple levels of private-loader reflection against classes whose exact private layout is not API. It is version-fragile, may interact with module access, and still does not eliminate the missing start boundary without instrumenting/wrapping the task. It is rejected under BootOptim's fail-open/compatibility bar and under this experiment's explicit constraints.

## Decision

**PR #100 is closed as not attributable from BootOptim with an exact non-invasive boundary.**

No fourth exact-pack smoke is requested because there is no implementation that can satisfy all required gates simultaneously:

- exact owner `xaeroworldmap`;
- start before execution of the same stock future/task;
- finish exactly on that future's completion;
- nonzero samples only inside that interval;
- zero Mixin errors;
- no private fragile reflection;
- no wrapping/replacing/reordering/modifying the runnable.

The historical 1.026 s warning remains useful only as a one-run inclusive-wall observation. It is not enough to select a network, Patreon, cache, filesystem, registry, or defer mechanism. Earlier mentions of `Patreon.checkPatreon` / `Internet.checkModVersion` came from the rejected five-second mixed window and remain hypotheses only.

## Reopening criteria

Reopen this front only if a materially new premise supplies a supported exact task boundary, for example:

- FML exposes a public owner/task lifecycle callback or task token for deferred work; or
- BootOptim gains a supported loader instrumentation point that is demonstrably invoked before `DeferredWorkQueue` definition and can observe the stock owner/runnable boundary without private reflection or task replacement.

Do not reopen merely because a future run emits another slow-task warning.

Any eventual behavior-changing candidate must measure all of:

1. TTMM / visually usable menu;
2. first singleplayer and multiplayer world readiness / first playable frame;
3. first Xaero map opening and first meaningful map use.

Moving work from startup into world entry, first playable frame, first map opening, or later gameplay is a failure.
