# Adaptive resource-reload scheduling on constrained hardware (2026-09-05)

Status: **CANDIDATE FOR HOSTED A/B; no production change yet**.

## Scope

This note investigates scheduling/backpressure for the exact-pack startup path without changing listener order, resource semantics, render/OpenGL ownership, Java/OS configuration, or the user's JVM arguments. It deliberately does not duplicate the post-FancyMenu boundary profiler in PR #122 and does not reopen the cooperative-wait implementation rejected in PR #117.

The useful question is not whether a waiting render thread can consume less CPU. PR #117 already showed that local CPU reduction is not a TTMM proof. The useful question is whether the **producer-side preparation executor** is oversubscribed on a four-processor budget and can be constrained without replacing tasks, callbacks, futures, or executor identity.

## Exact-pack scheduler that actually exists

The exact-pack control artifact from run `33979159742` (`exact-pack-result-control-1`, artifact `9973269818`) establishes all of the following in one run:

- hosted `nproc = 4`;
- runtime JVM arguments include `-XX:ActiveProcessorCount=4`;
- ModernFix is `5.27.14+mc1.21.1`;
- ModernFix logs `Configuring Minecraft's max.bg.threads option with 1 threads`;
- the resource reload nevertheless executes substantial work on `Worker-ResourceReload-*`, including workers `0`, `1`, and `2`.

That is not contradictory. ModernFix has two different policies:

1. `ModernFixMixinPlugin.computeBetterThreadCount()` sets `max.bg.threads = max(1, availableProcessors - 3)` when the user has not already supplied it. On a four-processor JVM this makes Minecraft's normal `Worker-Main` pool effectively one background worker.
2. With `perf.dedicated_reload_executor` enabled, ModernFix does **not** use that pool for client resource reload. `ReloadExecutor.createCustomResourceReloadExecutor()` creates a separate `ForkJoinPool` with `parallelism = ForkJoinPool.getCommonPoolParallelism()`, its own worker factory/context class loader, `asyncMode=true`, and names workers `Worker-ResourceReload-*`. `MinecraftMixin` redirects the initial client reload and `reloadResourcePacks` to that dedicated executor. ModernFix also routes server/world reload sites to the same service.

For a four-processor runtime with no separate common-pool override, the intended normal target is therefore three resource-reload workers even though `Worker-Main` is limited to one. This distinction is important: changing `max.bg.threads` is not a resource-reload scheduling experiment in this pack.

ModernFix also lowers Minecraft `Util.makeExecutor` workers to Java priority 4. The dedicated reload workers are created separately and retain the normal Java thread priority. A new priority experiment would therefore be a different mechanism, but Java priority is platform-dependent and is not the preferred first lever.

## Minecraft / NeoForge contract

`SimpleReloadInstance` receives a `prepareExecutor` and an `applyExecutor`, tracks `allPreparations` and `allDone`, and passes the two executors to every `PreparableReloadListener.reload(...)`. `SimplePreparableReloadListener` describes `prepare(...)` as work that may run off-thread (for example file I/O), while `apply(...)` is the ordered application side.

This gives one narrow scheduling lever that can preserve the observable callback dependency graph: change the **target parallelism of the already-selected prepare executor before submissions begin**, while leaving the executor object, task objects, `CompletableFuture`s, preparation barrier, apply executor, and listener registration/order untouched.

It does **not** make the FancyMenu waiter itself a resource-reload-pool problem. FancyMenu `ResourcePreLoader.preLoadAll()` is reached from its listener's apply side on the render thread after preparation-barrier work. Consequently a lower `Worker-ResourceReload` target cannot be claimed to remove the 18.234 s waiter CPU observed on `variance-fixed-021`. The candidate below targets the earlier reload preparation critical path and aggregate CPU competition only. FancyMenu's own asynchronous preload work/common-pool behavior remains unchanged.

## Designs rejected before code

### Global common-pool tuning — no-go

Changing `java.util.concurrent.ForkJoinPool.common.parallelism` would alter both the value ModernFix copies when constructing its reload executor **and** unrelated `CompletableFuture`/common-pool work from Minecraft and mods. FancyMenu and other mods may use that common pool. This cannot isolate the resource-reload hypothesis and would amount to changing a user/JVM-wide scheduler knob.

### Replacing ModernFix's executor — no-go

ModernFix already provides the dedicated pool. Replacing it with a BootOptim `ThreadPoolExecutor`/new `ForkJoinPool` would change executor identity, work-stealing behavior, thread factory, context class loader, names, exception handling and possibly assumptions made by nested `ForkJoinTask`s. There is no need to incur those compatibility risks merely to test parallelism.

### Semaphore/backpressure wrapper — no-go

A wrapper that acquires permits around arbitrary listener tasks can deadlock when a running task holds a permit, submits a child to the same wrapped executor, and waits for that child. Re-entrant bypass avoids one form of deadlock only by allowing the cap to be exceeded and still does not cover cross-thread/nested-future cases. It also adds a new queue/admission ordering in front of the stock pool. This is not a safe general solution for third-party listeners.

### Per-task thread priority — no-go for first experiment

Worker priority is advisory and OS/JVM dependent. Changing a shared worker's priority around tasks also creates a new observable thread property for mod code and does not bound parallel execution. ModernFix already has a separate priority policy for `Worker-Main`; the resource-reload hypothesis can be tested without adding another priority layer.

### Continuous CPU-load controller — no-go until a static budget wins

Sampling process CPU, queue depth, GC, native renderer load, or cgroup state and continuously retuning the pool would introduce feedback lag and a new control loop before there is evidence that even one lower target improves TTMM. It also risks oscillation between CPU-heavy model work and I/O-heavy resource work. First prove the single scheduling lever.

## Candidate architecture: temporary target-parallelism lease

Java 21+ exposes `ForkJoinPool.setParallelism(int)`, specifically to change a pool's target parallelism while preserving the pool. It controls future creation/use/termination of workers; it is a **target**, not a strict hard maximum, and compensating workers may still exist when tasks block.

A BootOptim experiment can therefore operate on ModernFix's existing dedicated pool rather than replacing or wrapping it.

### Activation and worker count

The first experiment should be explicitly default-off, for example `boot_optim.experimentAdaptiveReloadParallelism=true`.

Let:

- `visible = Runtime.getRuntime().availableProcessors()`;
- `stock = dedicatedPool.getParallelism()`.

`visible` is **not** treated as a physical-core count. It is only the JVM's scheduling-budget ceiling. That is intentional: cgroup/container limits and a user's `ActiveProcessorCount` should constrain the experiment rather than be bypassed. BootOptim must not attempt to recover hidden host CPUs or infer `2C/4T` from this value.

For the first hosted discriminator only:

- if `visible <= 4` and `stock > 1`, set `target = max(1, min(stock, visible - 2))`;
- otherwise do nothing.

Thus the exact hosted configuration is `visible=4`, `stock=3`, `target=2`: reserve one logical slot relative to stock for the render/client/native/common-pool ecosystem without serializing resource preparation. The experiment must **never increase** parallelism.

This is deliberately conservative rather than a claim that two workers are universally optimal. A one-worker candidate should not be tested until two workers demonstrate a phase-local win.

### Lifetime

Apply the target before the **initial client startup reload** submits preparation work. Restore the exact previous target when that reload's `ReloadInstance.done()` future reaches any terminal state.

Do not leave the lower target installed for gameplay/world loading. BootOptim's objective is TTMM and ModernFix intentionally reuses the same service at later world/server reload sites.

### Compatibility contract for a future implementation

A candidate implementation is acceptable only with all of these guards:

- **Exact executor guard:** act only when ModernFix is present and `ModernFix.resourceReloadExecutor()` is a non-common `ForkJoinPool`; otherwise no-op.
- **Version/shape guard:** the optional ModernFix hook must be fail-open (`require=0`/equivalent plus an exact known method-shape guard). If the 5.27.14 integration point is absent, BootOptim logs one concise marker and leaves stock behavior.
- **First-startup-reload scope:** an atomic state machine permits one startup lease only. Re-entrant/overlapping later reloads do not acquire another lease.
- **No replacement:** retain the same pool object, worker factory, class loader, names, async mode, uncaught-exception handler and queue/work-stealing implementation.
- **No future substitution:** do not return a replacement completion future to Minecraft. Observe the original `done()` only to restore state.
- **Normal completion:** restore the captured original parallelism exactly once.
- **Exceptional completion:** restore it exactly once and propagate the original exception unchanged.
- **Cancellation:** restoration runs on cancellation as a terminal completion; cancellation status/cause is not swallowed or translated.
- **Interruption:** no waits are added, no interrupt flag is cleared, and no task interruption policy changes.
- **Timeout:** no timeout is introduced or extended. Existing reload/FancyMenu timeout behavior remains stock.
- **Failure while applying/restoring:** catch `IllegalArgumentException`, `UnsupportedOperationException`, `SecurityException`, linkage/version mismatch, and optional-mod absence. If applying fails, remain stock. If restoration fails, emit an error marker but do not alter the reload result.
- **Worker target:** exact first A/B is 3 -> 2 only under the four-visible-processor guard. Never derive a target from host CPU counts outside the JVM's visible budget.
- **Renderer:** do not call OpenGL or move renderer work to detect a software renderer. Software rendering strengthens the need for the hosted surrogate, but renderer-string heuristics are not a safe production activation rule.

Because `setParallelism` is a target rather than hard backpressure, this design does not promise `poolSize <= target`. That property must not be used as a semantic invariant.

## Hosted discriminator before any physical run

Do not wait for or duplicate PR #122's post-FancyMenu timestamp/barrier instrumentation. This hypothesis can be tested with existing exact-pack outputs plus one scheduler marker.

### Smoke

One hosted exact-pack smoke with the candidate enabled should require:

- exact fixture integrity, one normal startup reload and the existing 8192x8192x2 atlas invariants;
- zero BootOptim mixin errors;
- main menu reached;
- marker similar to `BOOTOPTIM_RELOAD_BUDGET visible=4 stock=3 target=2 applied=true pool=ForkJoinPool`;
- a matching restore marker showing the original target was restored on terminal completion;
- no change to listener count/order diagnostics and no new timeout/exception/cancellation path.

No additional PR #122-style phase timestamps are needed.

### 3x3 A/B

If smoke is semantic-green, run the normal hosted exact-pack 3x3 interleaved candidate/control gate.

Primary metrics already emitted by the harness:

1. TTMM (`main_menu_ms`);
2. `reload_to_fancymenu_finish_ms`.

Secondary sanity metrics:

- `mod_entrypoint_ms` / `post_mod_entrypoint_ms` to detect cohort drift outside the changed phase;
- FancyMenu panorama preload time should not be treated as the mechanism target and should not materially regress;
- atlas dimensions and mixin errors remain exact semantic gates.

A candidate is worth the hardware gate only if the **reload-local interval improves and TTMM does not regress**, with the improvement larger than unrelated pre-reload cohort drift. A reduction in worker CPU or pool size alone is not a win. If hosted shows a slower reload-local interval, close the candidate: the constrained pool is starving ModelManager/other preparation work.

Only after a hosted-positive result should the same 3 -> 2 policy be tested on the physical 2C/4T/software-renderer machine. The physical test exists to answer the hardware-dependent part; it must not be the first performance gate.

## Conclusion

There is **no justification for a general adaptive scheduler/backpressure layer** in BootOptim today. The exact pack already contains two distinct ModernFix scheduling policies, and generic wrapping/replacement would add deadlock and compatibility surfaces.

There is, however, one narrow architecture worth a hosted A/B: temporarily reduce the target parallelism of ModernFix's **existing dedicated resource-reload `ForkJoinPool`** from 3 to 2 on the four-visible-processor startup budget, using Java's `ForkJoinPool.setParallelism`, then restore it at terminal completion. This preserves executor identity and the `SimpleReloadInstance` future/barrier/callback graph and is materially different from PR #117's waiter parking.

It is only a candidate. It does not claim to fix the FancyMenu wait CPU directly, and it must be rejected without a phase-local hosted TTMM/reload improvement.
