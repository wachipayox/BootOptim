# Low-noise startup variance harness — 2026-09-06

Status: **DIAGNOSTIC ONLY / NOT PRODUCTION**

Base: `agent/integration-current` @ `b0aa2472d58e3afc56a380e026c99ffe87000f22`.

Branch: `agent37/low-noise-variance-harness`. PR: #148.

## Purpose

This harness is for one question: when the exact pack is much slower or more variable on the old Windows laptop, which serial boundary actually grows, and does that wall growth track Minecraft-JVM CPU, the owner thread, GC/memory pressure, a reload barrier/apply turn, or a residual external wait bucket?

It is not an optimization. It does not cache, defer, parallelize, replace FancyMenu/MCEF work, change Java/OS/driver settings, or alter resource ordering. The MCEF owner/reentry hardening harness remains separate in PR #144.

## Activation and packaging

The diagnostic is default-off and uses one property:

```text
-Dboot_optim.profileStartupVariance=true
```

A normal `./gradlew build` packages the diagnostic into the normal distributable bootstrap JAR under `bootstrap/build/libs/`. Build CI uploads that packaged JAR as the normal `bootoptim-<sha>` artifact. No second private/local JAR format is required.

The existing benchmark property is compatible. Normally `-Dboot_optim.benchmark.exitOnTitle=true` stops when `TitleScreen` opens. When the variance probe is also enabled, only that diagnostic auto-exit is delayed until the first title-screen tick returns after `Window.updateDisplay`, so the report can distinguish screen opening from a frame that has actually passed the display/present path.

## Boundaries

Full snapshot rows use the prefix `BOOTOPTIM_VARIANCE` and include a monotonic `mono_ns` timestamp. Scopes have a stable `scope` id shared by their start/end rows.

Early/service layer:

- transformation-service construction;
- transformation-service initialize/onLoad;
- root mod discovery;
- dependency discovery.

Client/mod layer:

- vanilla `Bootstrap.bootStrap()` aligned to the first `isBootstrapped` write used by ModernFix's stopwatch;
- BootOptim mod entrypoint;
- first resource reload start/end;
- global reload `allPreparations` completion;
- block-model and blockstate aggregate futures;
- aggregate `AtlasSet.scheduleLoad` futures;
- ModelBakery construction;
- `bakeModels`;
- synchronous `loadModels`;
- final `ModelManager.reload` future;
- FancyMenu `ResourcePreLoader.preLoadAll` entry/return only;
- `TitleScreen` opening;
- first completed `runTick` with a `TitleScreen`, after the tick's `Window.updateDisplay` path.

Listener/apply attribution deliberately has a cheaper data path. Each reload listener stores only `System.nanoTime()` plus atomics for first preparation-barrier arrival, stock apply-turn future completion and returned listener-future completion. It takes **no MXBean snapshot and writes no listener log line while reload is on the startup critical path**. After `main_menu_presented`, the buffered rows are emitted as `BOOTOPTIM_VARIANCE_LISTENER`, including global wait, ordered apply wait and post-turn wall. Executors/tasks are never wrapped.

FancyMenu markers do not start suppliers or alter waits. The listener tracer delegates the original `StateFactory.create`, preparation barrier, executors and futures; unlike historical #47 it does **not** collect per-task queue/runtime counters.

## Snapshot fields and semantics

Major boundary rows sample:

- JVM start epoch and uptime;
- `System.nanoTime()` and wall epoch for process-age consistency;
- cumulative Minecraft-process CPU from `OperatingSystemMXBean`;
- current-thread CPU when supported;
- aggregate GC count/time;
- heap used/committed/max;
- OS free/available physical-memory counter exposed by the JDK.

End rows also report wall, process-CPU, same-owner-thread CPU, GC, heap and available-memory deltas.

Interpretation is deliberately narrow:

- **wall** is monotonic elapsed time for that scope;
- **process CPU** includes every Minecraft JVM thread and is not decoder/listener-exclusive CPU;
- **owner-thread CPU** is reported as a delta only when the same Java thread executes both scope endpoints;
- **async/future scopes are inclusive/overlapping and must never be summed**;
- listener `global_wait`, `order_wait` and `post_turn` are scheduler/lifecycle wall categories, not CPU time;
- **available memory is not a hard-fault, page-cache, disk or GPU counter**;
- low process CPU plus high wall leaves filesystem/page cache, native wait, OS descheduling, GPU/driver and external contention unresolved until a narrower probe names one of them.

`main_menu_presented` is closer to a visible-menu endpoint than `ScreenEvent.Opening`, but it is still not pixel equivalence or interaction validation.

## Offline parser and invalidation

After Java has exited, run:

```text
python tools/laptop-bench/variance_probe.py <completed-console-or-latest.log>
```

The parser never polls `latest.log` while Java is running. It pairs full scopes by explicit id rather than wall-log timestamps and parses deferred listener rows separately.

A run is invalid for variance comparison when any of these holds:

1. the first probe is not transformation-service construction;
2. the first probe's JVM uptime exceeds 60,000 ms (configurable offline);
3. JVM start epoch, wall epoch and uptime disagree by more than 5 s;
4. a required boundary is missing, including vanilla Bootstrap and `main_menu_presented`;
5. more than one initial resource-reload scope appears before the menu boundary;
6. listener lifecycle rows are absent from an analyzed completed run;
7. required start/end scope structure is truncated or impossible (reported for review).

The 60 s early-probe gate intentionally rejects the known stale-JVM contamination shape from #130 while retaining the normal roughly 23–30 s unobserved startup prefix seen in comparable physical runs.

## Observer cost

There is no sampling thread, JFR recording, stack walking, sleep, resource/file wrapper, ZIP enumeration, hot method counter or repeated task instrumentation. With the property absent, snapshot collection/logging is skipped. Listener hot-path observation is nanoTime/atomics only; its formatting is moved after the first-present boundary.

The actual observer cost is measured rather than inferred. PR #148 requests hosted exact-pack 1×1 same-branch A/B with only `-Dboot_optim.profileStartupVariance=true/false` changing. Candidate-control TTMM/reload deltas are treated solely as diagnostic overhead estimates. Because hosted timing is noisy and hardware differs from the laptop, a small single-pair delta is only a rejection guard against gross observer effect, not a precise laptop overhead model.

## Hosted gate and single physical-run criterion

Hosted exact-pack is an instrumentation/semantic gate, not the laptop. Before asking for one physical run, all of the following must hold:

1. Build/tests/package succeed and the packaged bootstrap artifact is downloadable;
2. normal Startup CI succeeds with the property absent;
3. exact-pack candidate reaches the presented title boundary with zero new BootOptim/Mixin failures;
4. exact resource selection remains valid, one initial reload is observed and atlas dimensions remain normal;
5. required discovery/Bootstrap/ModelManager/atlas/reload/FancyMenu/title markers are non-empty and monotonic;
6. deferred listener lifecycle rows are non-empty and emitted only after `main_menu_presented`;
7. enabled-vs-disabled diagnostic delta is small enough that the probe is not dominating the boundary being classified.

Only then request **one fixed-selection physical diagnostic launch**, not an A/B. That run is justified because the physical question is hardware-sensitive and cannot be answered by llvmpipe: determine which boundary reproduces the large wall variance and whether its wall growth is CPU-dense, owner-thread-dense, GC/memory-associated, barrier/apply-queue-associated, or still in the unresolved external bucket.

No production candidate follows from a single inclusive/task-sum number. Any optimization requires a separate branch, semantic argument and performance promotion gate.

## Related evidence

- #111 — fixed-selection physical variance and offline coarse partitions.
- #130 — stale JVM/process-age contamination and invalidation requirement.
- #135 — exact vanilla `Bootstrap.bootStrap()`/ModernFix stopwatch alignment reused as one aggregate boundary.
- #145 — early FML physical-variance audit and separation of root locator/discovery from vanilla Bootstrap.
- #47 — correct preparation-barrier/apply-turn semantics; its per-task executor instrumentation is deliberately not copied here.
- #138 — aggregate ModelManager/atlas/bake/load future boundaries reused as the safe low-cardinality pattern.
- #141 — ZIP enumeration is hardware/page-cache sensitive but inclusive totals are not critical-path savings.
- #144 — separate MCEF owner/reentry hardening harness; intentionally not duplicated.
- `laptop-fancymenu-wait-cpu-variance-2026-09-05.md` — run 021 wall/owner CPU/process CPU/GC/heap/memory evidence.
- `post-fancymenu-critical-tail-audit-2026-09-05.md` — reason to distinguish title opening from first presented frame and reload barrier/apply work.
