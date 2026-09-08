# Structured boot trace foundation — 2026-09-08

Status: **ACTIVE / diagnostic infrastructure**

This entry records the first implementation step of the architectural boot rewrite. It is not a
startup optimization and must not be accepted as one merely because it produces more counters.

## Purpose

BootOptim needs one event stream that can explain what the client is doing before the title screen,
without confusing inclusive listener time with the critical path. The stream is shared by the early
ModLauncher/bootstrap layer and the regular NeoForge mod through a deliberately duplicated,
dependency-free writer. The duplication avoids making the early service depend on the regular mod or
on NeoForge classes that are not available when ModLauncher constructs the service.

## Opt-in modes

The trace is disabled by default. Enable it with a JVM property:

```text
-Dboot_optim.trace.mode=profile
```

Supported modes:

- `off` (default): no trace filesystem work.
- `profile`: append each JSONL event immediately; useful for development diagnosis.
- `development`: same JSONL contract, intended for a future local sidecar/visual window.
- `benchmark`: keep a bounded in-memory ring and write it only at an explicit flush, avoiding one
  disk write per marker during a timed launch.

Optional properties:

```text
-Dboot_optim.trace.path=<instance>/logs/bootoptim-trace.jsonl
-Dboot_optim.trace.maxEvents=8192
```

The bootstrap layer resolves the default path from ModLauncher's authoritative game directory. The
regular layer falls back to the startup-report directory when it is present. All writes are
best-effort and fail open.

## JSONL schema

Every line is an independent JSON object with:

- `schema`: integer schema version, currently `1`;
- `sequence`: writer-local monotonic event sequence;
- `uptime_ns`: JVM-uptime-aligned monotonic timestamp;
- `monotonic_ns`: raw `System.nanoTime()` value for same-process ordering;
- `thread_id`, `thread`: producer identity;
- `kind`: `phase_begin`, `phase_end`, `milestone`, `diagnostic`, `optimization`, `error`, or a
  future task/barrier/commit event;
- optional `phase`, `task`, `parent`, `mod`, `resource`, and `detail` fields.

The current integration emits bootstrap milestones and diagnostics, the mod entrypoint boundary,
the main-menu boundary, optimization decisions and failures. Later instrumentation must use the
same event vocabulary for resource opens, parses, preparation barriers, ordered commits and
first-consumer/world work.

## Measurement rules

The trace is explanatory evidence, not a replacement for the startup report. A run must still state
its measurement origin and endpoint. Do not add task-sum or inclusive listener values to obtain a
time-to-menu claim. In benchmark mode, flush only after the measured endpoint or at a diagnostic
milestone outside the timing boundary; profile/development streaming is intentionally unsuitable
for a low-noise absolute benchmark.

The buffer is bounded. If it fills, a `trace_drop` event reports the number of discarded events;
the run remains valid for behavior but incomplete for detailed attribution.

## Sidecar direction

`development` establishes a stable, append-only JSONL contract for a local external viewer. A future
sidecar may tail this file or subscribe through a loopback endpoint without requiring a GUI change
inside Minecraft. No sidecar process is started in production, and no scheduling or resource-load
behavior changes in this foundation.

## Validation performed

- bootstrap and regular-mod source sets compile together;
- JUnit verifies JSON escaping, benchmark buffering, and bounded-drop reporting;
- existing bootstrap tests remain green;
- no model/resource executor, NeoForge ordering, GL ownership, or gameplay behavior is changed.

Next gate: attach richer phase/task/barrier events, then use the trace to build the exact-pack
per-mod/per-phase scaling matrix before attempting an invasive scheduling rewrite.
