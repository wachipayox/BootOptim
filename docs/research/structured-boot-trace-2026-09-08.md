# Structured boot trace base — 2026-09-08

Status: **PROFILED INFRASTRUCTURE / NO RUNTIME OPTIMIZATION**

This change defines a common structured trace contract for early ModLauncher/FML, mod callbacks, resource/model reload and menu work. It does not change scheduling, callback order, ModelBakery, renderer work or gameplay behavior.

## Context and boundary

At integration authority `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`, `StartupProfiler` and `EarlyStartupProbeService` emit process/JVM-uptime console markers, `StartupReport` appends a linear text report, and `DiscoveryProfiler` combines `System.nanoTime()` elapsed time with JVM uptime markers. PR #47's diagnostic `ResourceReloadCriticalPathProfiler` proved why a graph is needed: inclusive listener timings overlap and cannot be summed.

The requested `docs/research/boot-pipeline-program-2026-09-08.md` was not present in this integration tree and public repository search did not expose a copy at investigation time. Its contents were not invented.

## Module boundary

`trace-core` is a JDK-only Java 21 module used by both the root mod and `bootstrap`. The packaged bootstrap embeds the `trace-core` classes so SERVICE-layer code can use the schema before the nested NeoForge mod is loaded. This avoids two schemas and avoids a root -> bootstrap dependency cycle.

Primary API: `dev.wachipayox.bootoptim.trace.StructuredBootTrace`.

No startup callsite is instrumented by this PR. The trace base remains separate from runtime optimizations and diagnostic mixins.

## Modes

System property: `-Dboot_optim.bootTrace.mode=off|benchmark|profile|development`.

- `off` (default): no file, no shutdown hook and no expensive runtime/JVM identity initialization. Once the class is referenced it owns only the small no-op object/counter structure.
- `benchmark`: typed counters/task ids only. Per `record(...)` call the path is mode/closed checks plus one `LongAdder.increment()`; task allocation also uses one `AtomicLong.incrementAndGet()`. It does **not** call `nanoTime`, capture the thread, allocate/clone an event, serialize JSON, perform file I/O or contact IPC. A compact header/summary may be flushed only at process shutdown. This overhead is code-path bounded but not nanobenchmarked yet; do not invent a numeric savings or subtract it from TTMM.
- `profile`: bounded in-memory event buffer plus one local JSONL snapshot at explicit `flush()`/JVM shutdown. No per-event disk serialization.
- `development`: profile behavior plus an explicitly installed `DevelopmentSink`. BootOptim does not create a pipe/socket/window on its own. Sink failure disables only the sink and fails open.

The same measurement-origin and endpoint strings are present in benchmark/profile/development. Complete profile telemetry must not be enabled during a timed benchmark A/B.

## Schema v1

JSONL schema id: `bootoptim.boottrace`, version `1`.

The first record is `trace_header`, followed by zero or more `event` records, followed by `trace_summary`.

Header identity/origin fields:

- `jvm_id`: process-unique identity derived from PID, JVM start epoch and trace monotonic origin;
- `pid`;
- `jvm_start_epoch_ms`;
- `trace_origin_epoch_ms`;
- `trace_origin_mono_ns`;
- `clock_kind=monotonic`, `clock_source=System.nanoTime`, `clock_origin=trace_init`;
- `measurement_origin` and `endpoint` (for example `hosted_exact_pack` / `main_menu`);
- `development_endpoint`: descriptive tooling metadata only; it does not open IPC;
- `dropped_events`.

Every event has `v`, `jvm_id`, strictly increasing producer `seq`, `type`, trace-relative `mono_ns`, `thread_id` and `thread`. Optional fields are `task_id`, `parent_task_id`, `dependency_ids`, `phase`, producer-supplied `cpu_ns`, `mod`, `resource`, `reload_generation` and `detail`.

Version-1 event types are exactly:

`phase_begin`, `phase_end`, `task_begin`, `task_end`, `barrier_wait`, `barrier_open`, `commit_begin`, `commit_end`, `blocked_on`, `mod_callback`, `resource_open`, `resource_parse`, `fallback`, `error`.

`parent_task_id` is structural ownership/nesting. `dependency_ids` are causal prerequisites. `blocked_on` adds causal dependencies discovered after task begin. Do not encode a guessed progress percentage; a task waiting on an unknown dependency should emit `blocked_on` with `detail=unknown` or no dependency id rather than a fabricated completion fraction.

`cpu_ns` is optional producer data and is not inferred from wall time. Producers must document whether their CPU value is exclusive or inclusive. The generic consumer reports CPU task-sum separately and never treats it as critical-path wall.

## Loss and fail-open behavior

Profile/development use a fixed-capacity, non-overwriting buffer (default 131,072 events). Once full, later events are dropped and `dropped_events` increments. A partial slot visible during concurrent snapshot is also declared as loss. The consumer rejects traces with loss by default; `--allow-loss` is an explicit diagnostic escape hatch, not a performance-validity default.

File creation, directory creation, atomic rename and development-sink failures are caught. Trace failure never changes callbacks or aborts Minecraft.

## Consumer and critical path

`tools/boot-trace/analyze_boot_trace.py` validates:

- schema/JVM identity;
- separate JVM wall start, trace wall origin and monotonic origin;
- event sequence and declared loss;
- task begin/end ordering and same-thread lexical nesting;
- phase/commit/barrier begin/end pairs;
- missing parent/dependency ids;
- dependency cycles.

It reports three deliberately separate quantities:

1. `reported_task_cpu_sum_ms`: producer-supplied CPU values only;
2. `inclusive_task_wall_ms`: diagnostic sum showing how misleading inclusive totals can be;
3. `critical_path_wall_ms`: union of real intervals along the winning dependency chain.

The critical-path calculation unions overlapping intervals. If a 100 ms parent/wait interval contains a 60 ms child/dependency interval, that overlap is counted once, not as 160 ms.

## Development sidecar protocol

A future sidecar adapter implements `StructuredBootTrace.DevelopmentSink` and is installed only when mode is `development`. Installation receives one header, the buffered prefix in sequence order, then live event JSON lines; `close()` sends one summary. A named-pipe or local-domain-socket adapter belongs in development tooling, not in this core. It should connect only to an explicitly configured local endpoint, never listen/connect by default in production, and must treat disconnect/write failure as non-fatal.

The sidecar and offline analyzer consume the same JSON lines. No GUI-specific fields are required in the producer schema.

## Runner collection contract

For a profile run, the game JVM should receive at minimum:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
```

The default output is relative to the game directory: `logs/bootoptim-trace.jsonl`.

The exact-pack runner must collect the trace **after Java exits**, because the shutdown hook is the final fail-open flush boundary. Non-paired Actions should add `run-pack-benchmark/logs/bootoptim-trace.jsonl` to the existing diagnostics artifact. Paired mode must copy that file to `paired-results/<variant>-<pair>/bootoptim-trace.jsonl` before clearing runtime logs for the next JVM. The trace must never be tailed/read while the timed JVM is running.

This PR documents that runner contract but deliberately does not enable profile mode in existing A/B workflows: full tracing during a timed A/B would violate the measurement contract.

## Tests

`trace-core` JUnit tests cover:

- benchmark counters with a clock supplier that must never be called;
- schema/JVM identity and separate clock origins;
- bounded-buffer loss declaration;
- development replay/live ordering and sink fail-open;
- a real child JVM that exits normally and proves shutdown-hook JSONL flush.

The existing Build workflow's Python unittest discovery also runs `tools/laptop-bench/test_boot_trace.py`, which checks critical-path interval union, loss rejection, lexical nesting, unmatched barriers, JVM identity and clock-origin validation.

## Compatibility / cost decision

This is infrastructure, not an optimization. Default runtime behavior stays `off`. No scheduling, GL/render work, mod lifecycle ordering or resource/model semantics change.

Benchmark per-event cost is intentionally limited to counter operations; there is no per-event timestamp/thread capture/allocation/serialization/IPC. The one-time active-mode initialization and shutdown summary flush are outside the per-event path. A dedicated microbenchmark should measure the exact counter cost before a very high-frequency resource-open probe is enabled in benchmark mode.

## First instrumentation steps

1. **Root/FML:** replace/bridge only the existing root/dependency discovery diagnostic boundaries with `phase/task` events. Assign stable task ids to root discovery, dependency discovery and mod callbacks; emit `blocked_on` only for proven dependency joins. Keep existing console benchmark markers until runner parity is demonstrated.
2. **Resource/model reload:** adapt the semantics already proven by #47/#184: one reload generation id; listener task ids; `barrier_wait` when a listener reaches the preparation barrier; `barrier_open` for global preparation/ordered turn; `blocked_on` dependency ids for the actual gating listener/tasks; `commit_begin/end` around owner-thread apply/publication. ModelManager preparation children can then describe parse/parents/material/atlas/bake without adding inclusive durations.
3. Run one **profile smoke**, analyze `bootoptim-trace.jsonl`, and require zero loss, valid nesting/dependencies and agreement with existing root/reload markers before using the schema for broader instrumentation. Do not run profile as an optimization A/B.

Relevant history: PR #47 (reload barrier/turn semantics), #184 (exclusive ordered reload slots), current `StartupProfiler`, `StartupReport`, `EarlyStartupProbeService` and `DiscoveryProfiler`.
