# Atomic laptop state sharing recovery — 2026-10-03

The real-candidate path-prefix-reuse-20261003 campaign failed BEFORE a Java
measurement. The interactive runner had launched Prism and captured its PID,
but File.Replace failed with a Windows sharing violation while publishing that
state. The original error was preserved; directed recovery used recorded Prism
PID/creation/session and restored the original config/JAR. Zero valid runs.
This is a controller failure, not game/candidate evidence. No performance claim.

Atomic JSON publication from #328 remains correct, but short-lived readers can
hold a Windows handle without delete-sharing and temporarily prevent Replace.
Both transaction and interactive Save now retry the SAME atomic Replace up to
100 times at100 ms intervals, ONLY for IOException native sharing/lock errors
32/33. All other errors and exhausted retries still propagate. No non-atomic
write, lost ownership guard, indefinite wait or blind process termination.
This does not serialize multiple writers or fix stale-snapshot writer races;
the existing staged/run/finished/recovery handoff remains responsible for those.

The identical helper was deployed in the r2 bundle. All four C/B/B/C transactions
then completed valid and restored; independent original hashes verified.
Local actual read-handle contention test passed: old state remains readable
while locked, after release new bytes replace it atomically. Permanent tooling
sources now include the fix so later bundles cannot silently omit it.