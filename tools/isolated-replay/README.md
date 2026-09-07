# Isolated phase replay

`pack_graph.py` builds the first pack-derived fixture by scanning model and
blockstate JSON from the exact-pack extract (including mod/resource-pack
archives), resolving parent/model references and emitting enumeration, parse and
approximate parent-aware bake tasks. The Actions workflow scales the three task
families to the inclusive totals from the single physical ModelManager reference
run, while retaining the warning that those totals overlap and are not additive
wall time.
The task durations are deliberately labelled relative model-complexity work
units; they are not wall milliseconds and are not a startup claim.

`phase_replay.py` then deterministically replays that graph with a chosen worker
count and policy (`stock`, `critical-first` or `small-first`). It reports
critical-path work, makespan, queue wait and task placement.

This is a graph/scheduling model, not a Minecraft implementation. It must not be
used to claim a time-to-main-menu win. A policy that survives this filter still
needs the exact-pack smoke/A-B and, for hardware-sensitive effects, the physical
gate. The `isolated-phase-replay` Actions workflow creates the graph once and
replays each policy in an independent VM, then publishes a comparison summary.

Example fixture shape:

```json
{
  "schema": 1,
  "fixture_id": "exact-pack-model-reload-2026-09-07",
  "variant": "control",
  "metadata": {
    "origin": "model_reload_start",
    "endpoint": "model_manager_barrier",
    "source_commit": "<commit>",
    "source_log_hash": "<sha256>"
  },
  "tasks": [
    {"id": "parse", "duration_ms": 10.0},
    {"id": "bake", "duration_ms": 50.0, "depends_on": ["parse"]}
  ]
}
```

Run it with `python phase_replay.py fixture.json --workers 2 --policy critical-first`.
The next fidelity step is to add captured per-task durations and executor/barrier
membership from the existing ModelManager boundary profiler while keeping
task-sum, CPU and critical-path fields distinct.
