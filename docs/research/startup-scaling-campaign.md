# One-shot startup scaling campaign

Status: **DIAGNOSTIC ONLY**. This instrumentation is intentionally not production code.

## Purpose

This campaign build is designed for a scarce run on substantially slower hardware. The goal is not only to find the largest phase on that machine, but to determine **what scales badly as hardware quality drops** so future optimizations target mechanisms that matter across the real user base.

A single run combines structured phase tracing with low-frequency JVM telemetry and Java Flight Recorder sampling. Absolute startup time from this build includes profiling overhead; compare the **same campaign build and settings** across machines whenever possible.

The PR #65 artifact is self-profiling: if `boot_optim.profileStartup` is not supplied, the bootstrap sets it to `true` automatically. This avoids wasting a remote-machine run because of a forgotten JVM argument. Explicit `-Dboot_optim.profileStartup=false` remains an emergency disable switch for this diagnostic artifact.

## Instrumentation layers

### Early bootstrap / discovery

Existing BootOptim startup probes are active in the campaign:

- transformation-service construction / initialize / onLoad milestones;
- root mod discovery wall time;
- dependency discovery wall time;
- per-JAR mod-scan cache outcome and elapsed time;
- asynchronous cache-write timing.

These cover work before the normal BootOptim mod entrypoint and expose slow-storage / classpath-scan scaling.

### Resource reload critical path

The proven PR #47 `SimpleReloadInstance` tracer is reused without changing listener ordering or results. For every listener it records:

- preparation barrier completion;
- wait for the global preparation gate;
- wait for ordered apply turn;
- post-turn completion time;
- prepare/apply executor task CPU wall time;
- task queue sum/max delay.

It also reports the actual preparation gate, worst order wait and worst post-turn listener. Listener timings overlap and must not be summed.

### ModelManager / model pipeline

Coarse timers cover:

- blockstate resource load;
- block-model resource load;
- atlas preparation;
- `ModelBakery` construction;
- `bakeModels`;
- enclosing `loadModels`.

During `bakeModels`, only two generic residual scopes receive per-call wall timers:

- `ElementsModel.addQuads`;
- production `DirectGeneratedItemBaker.tryBake`.

There is deliberately no per-face, per-quad or recursive `ModelBakerImpl` timer in this campaign. The old pack executes millions of those operations and intrusive instrumentation would distort scaling on the slow machine. Decocraft and FancyMenu production optimizations already emit their own aggregate markers.

### One-hertz system/JVM sampler

A daemon sampler emits approximately once per second:

- cumulative and delta process CPU time;
- equivalent CPU cores consumed during the interval (`process CPU delta / wall delta`);
- process and system CPU load;
- heap / committed heap / non-heap usage;
- GC count/time totals and deltas;
- live/peak threads;
- loaded classes;
- cumulative JIT compilation time;
- free physical memory.

This distinguishes single-thread CPU saturation, useful parallelism, low-CPU waiting, GC pressure and memory pressure.

### Java Flight Recorder

A JFR starts from the bootstrap layer after authoritative game-directory/config resolution and stops automatically at the first title screen. The standard JFR `profile` configuration is augmented with thresholded/sampled events for:

- Java/native execution samples;
- allocation samples;
- file reads/writes;
- socket reads/writes;
- thread parks / monitor contention;
- CPU/load/memory/class-loading/compiler statistics;
- garbage collections.

Default output:

`.bootoptim/profiles/startup-scaling-<timestamp>.jfr`

The recording is capped at 256 MiB and is fail-open: inability to start/stop JFR must not prevent Minecraft startup.

## Interpretation across machines

The primary comparison is a scaling factor per phase, not only total startup time.

- **Single-thread CPU bound:** wall and process CPU grow together while equivalent cores stay near one.
- **Parallel CPU bound:** wall grows, process CPU grows faster than wall, and equivalent cores remain above one.
- **Storage / IO bound:** wall grows disproportionately while process CPU stays low and JFR FileRead/FileWrite latency rises.
- **GC / allocation bound:** GC deltas/time and allocation samples grow disproportionately, often alongside heap pressure.
- **Queue / synchronization bound:** reload executor queue delays, ThreadPark or JavaMonitorEnter become prominent.
- **Network bound:** SocketRead time explains a wall gap (important for MCEF or other startup-time online work).
- **JIT/classloading bound:** compiler time or class-loading samples scale significantly before the regular mod entrypoint/reload.

## Recommended one-shot protocol

1. Use the exact same campaign JAR and modpack on both PCs.
2. Prefer a warm run: launch the pack once first if practical so the persistent BootOptim scan cache exists. Do not deliberately delete caches immediately before only one scarce slow-PC measurement.
3. No profiling JVM argument is required for PR #65; the campaign enables itself. Do not set `boot_optim.benchmark.exitOnTitle` for the friend's manual run.
4. Start Minecraft and wait until the main menu appears. The JFR stops automatically at that point.
5. Quit normally after the title screen is visible.
6. Collect:
   - `latest.log`;
   - `logs/bootoptim-startup.log`;
   - the `.jfr` file whose path is printed by `BOOTOPTIM_CAMPAIGN_JFR event=start`.

A same-build campaign run on the fast reference PC is strongly recommended. The slow PC still needs only this one profiling build/run; the fast run supplies the denominator needed to identify which subsystems actually scale worst.
