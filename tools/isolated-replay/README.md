# Isolated phase replay

`phase_replay.py` is the first cheap layer of the isolated-loading methodology.
It consumes a JSON fixture containing measured phase tasks and dependency edges,
then deterministically replays the graph with a chosen worker count. It reports
critical-path wall, makespan, queue wait and task placement.

This is a scheduling model, not a Minecraft implementation. It must not be used
to claim a time-to-main-menu win. A policy that survives this filter still needs
the exact-pack smoke/A-B and, for hardware-sensitive effects, the physical gate.

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

Run it with `python phase_replay.py fixture.json --workers 2`. The next step is to
generate a fixture from the existing ModelManager boundary profiler while keeping
task-sum, CPU and critical-path fields distinct.
