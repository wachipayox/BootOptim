# Physical laptop host-pressure trace (2026-09-07)

Status: **DIAGNOSTIC TOOLING / NO RUNTIME CHANGE**

## Motivation

The corrected P0.2 run shows a CPU/GC-heavy ModelManager critical path but one
run cannot explain the laptop's large wall-time distribution. Java-only
snapshots cannot distinguish process CPU saturation from Windows page reads,
disk pressure or descheduling. A bounded host trace is therefore useful before
trying another worker-count or model-cache experiment.

## Tool

`tools/laptop-bench/remote_laptop_host_trace.ps1` attaches to an already
identified `java.exe`/`javaw.exe` PID and exact `CreationDate`, then writes one
JSON object per sample. It records read-only WMI performance counters for:

- target-process CPU, I/O bytes, page faults and private working set;
- system memory page input/read rates;
- total physical-disk read bytes/operations;
- total CPU utilization.

It never reads Minecraft logs, controls Prism, kills Java, changes the instance,
or changes the startup measurement boundary. The PID/creation tuple prevents a
later process from being mistaken for the target. It stops when that process
exits or after a bounded timeout and writes a completion summary to stdout.

## Use and interpretation

Start it only after the transaction runner has validated the target Java PID,
or pass the PID/creation tuple captured from an already running diagnostic.
Keep the trace outside candidate/control A/B claims: WMI snapshots add a small
observer cost and provide attribution evidence, not an optimization result.

Interpret process CPU, memory/page-read and disk fields with the existing
monotonic ModelManager/resource-reload boundaries. Do not call `wall - CPU`
“disk time”, and do not infer a removable bottleneck from one sample. The next
P0.2 decision requires at least one representative trace whose phase-local
counter changes align with the known ModelManager gate.
