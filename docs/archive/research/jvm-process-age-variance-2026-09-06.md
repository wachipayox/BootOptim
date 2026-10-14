# JVM process-age contamination in laptop startup runs — 2026-09-06

Status: **DIAGNOSTIC / METHODOLOGY FIX**, not a startup optimization.

## Finding

The physical FancyMenu campaign contains one run whose BootOptim report reached
`mod_entrypoint` at `985,056 ms`, while the first ModLauncher log line appeared
only about two minutes before that marker. The other comparable runs reached the
same marker at `122,774–143,105 ms`. The run was already known to have left a
Java process alive during Prism/JVM preparation; its total `1,272.745 s` must not
be used as an A/B measurement.

The visible FML/ModernFix log phases do not show a matching 14-minute operation:
root discovery is about 18–21 s, ModernFix configuration follows within about
16–28 s, and the bootstrap marker follows within about 28–34 s in the normal
runs. This separates the hidden prefix from the NeoForge resource-reload path.

For the four comparable candidate runs, subtracting the wall-clock interval
from the first ModLauncher line to `mod_entrypoint` from the BootOptim JVM
uptime gives this approximate unobserved prefix:

| run | JVM uptime at entrypoint | visible first-log → entrypoint | unobserved prefix |
| --- | ---: | ---: | ---: |
| active-layout-001 | 143.105 s | 113.323 s | 29.782 s |
| active-layout-002 | 131.202 s | 107.913 s | 23.289 s |
| active-layout-003 | 985.056 s | 142.323 s | 842.733 s |
| active-layout-006 | 122.774 s | 99.204 s | 23.570 s |

The isolated 842.733-second prefix is therefore a process/harness boundary
failure, not a measured NeoForge phase.

## Change

The opt-in startup report now records `jvm_started` and
`jvm_uptime_at_report_ms` in addition to its wall-clock `started` field. A
benchmark runner can reject a run when the JVM start precedes the intended
launch boundary by an unexpected amount, instead of attributing the prefix to
ModernFix or BootOptim.

The fields are diagnostic only, written with the existing report and disabled
when startup reporting/profiling is disabled. They do not change scheduling,
class loading, resource reloads, or gameplay behavior.

## Gate and reopening

Build and bootstrap tests must pass. The next runner change should compare the
recorded JVM start/uptime with the runner's process/PID start and mark the run
invalid before any A/B aggregation when a previous Java process was reused.
Only after that gate is in place should a long pre-entrypoint interval be
treated as a NeoForge/ModernFix optimization target.
