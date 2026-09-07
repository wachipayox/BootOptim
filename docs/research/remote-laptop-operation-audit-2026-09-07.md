# Remote laptop benchmark operation audit — 2026-09-07

Base authority: `agent/integration-current` at `2bccf5f4fa221c78e286d052beb78636fa4c317b`.

Status: tooling/documentation only. No Minecraft/NeoForge runtime, production optimization, Windows setting, Java installation, driver, power-plan, game behavior, profiler, or resource selection is changed by this branch.

## Decision

The remote laptop operation should be treated as a transaction, not as a sequence of ad-hoc SSH commands. The benchmark is valid only if the transaction proves all of these before Java enters the measured run:

1. Prism is fully stopped before `instance.cfg` is edited.
2. No prior target `java.exe` or `javaw.exe` survives.
3. Exactly one packaged BootOptim early-service wrapper is present in the live `mods` directory.
4. The candidate wrapper SHA-256 is the expected artifact SHA-256.
5. The launch task runs as the intended user with Task Scheduler `InteractiveToken`, in the same active Windows desktop session identified before launch.
6. The newly-created Java process is identified by PID + creation time + instance path, not by process name alone.
7. `Win32_Process.CommandLine` contains every required JVM argument, contains no forbidden/stale argument, and contains each required `-D...=` key exactly once. Duplicate `-Dboot_optim.*=` keys invalidate the run.
8. No benchmark log/resource file is polled while Java is measuring.
9. Java exits (normally via the already-established benchmark auto-exit contract); a timeout is an invalid run, not a timing result.
10. Prism is fully closed before the exact original bytes of `instance.cfg` and the exact original BootOptim wrapper are restored and hash-verified.

The new scripts in `tools/laptop-bench/` implement those transaction boundaries. They are not automatically invoked by CI or production code.

## Confirmed failure classes in existing evidence

### 1. Stale JVM provenance is a real invalidation, not a theoretical risk

PR #130 records a physical campaign where `mod_entrypoint=985056 ms` while the visible FML/ModernFix phases were ordinary: the JVM had remained alive while Prism was being prepared. PR #147 quantified an unobserved `842.733 s` prefix. This run class must be rejected before aggregation.

Therefore process matching must cover **both** `java.exe` and `javaw.exe`, and the accepted process must be newly created for the selected Prism instance. A process-name-only `Get-Process java` check is insufficient.

### 2. Prism can silently defeat an intended JVM-property change

`AGENTS.md` and the corrected P0.2 variance run record the operational rule: stop Prism before editing `instance.cfg`, because Prism can write its in-memory settings on exit. The first P0.2 launch was discarded after the intended variance property was absent from the effective run; only the corrected launch was evidence.

The correct sequence is therefore **Prism exit -> edit/stage -> launch -> inspect effective Java command line -> measure -> Java exit -> Prism exit -> restore**. Restoring `instance.cfg` while Prism is still alive is unsafe because Prism may rewrite it again afterward.

### 3. Resource packs existing on disk does not prove the exact workload was selected

PR #103 proved that an isolated physical instance had resource ZIPs on disk but did not select them. Those historical timings cannot establish exact-pack performance. `tools/laptop-bench/check_resource_selection.py` remains the post-run selection gate; do not replace it with a directory count.

### 4. Duplicate/wrong BootOptim packaging is a concrete harness hazard

`AGENTS.md` requires exactly one BootOptim JAR in the instance `mods` directory before every benchmark and identifies `bootstrap/build/libs/` as the distributable wrapper. `bootstrap/src/main/java/dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.java` identifies the wrapper by the entry:

`dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class`

The transaction script uses that exact marker rather than a filename glob. This catches renamed wrappers and avoids mistaking the root project's inner regular-mod JAR for the standalone benchmark artifact.

### 5. Invisible-session launch is a credible methodology failure, but is not proven as the cause of a retained run

Microsoft Task Scheduler documents `TASK_LOGON_INTERACTIVE_TOKEN`: the user must already be logged on and the task runs only in an existing interactive session. `schtasks /IT` has the corresponding semantics. SYSTEM has no interactive logon, and S4U is not an interactive desktop token.

A direct SSH-created GUI process is therefore not accepted merely because its process exists. The robust contract is: discover one active Explorer/WTS session for the intended user, register the task with `LogonType Interactive`, and inside the scheduled runner assert that its own `SessionId` and the new Java process `SessionId` equal the preflight session. `Win32_Process` exposes `CommandLine`, `CreationDate`, and `SessionId`, so this does not require a Java profiler.

This is a prevention rule. Current public evidence does not prove that a retained BootOptim physical run actually executed invisibly.

## Why encoded PowerShell is the SSH boundary

Microsoft documents that `powershell.exe -EncodedCommand` takes Base64 of UTF-16LE text and is intended for commands with complex nested quoting. Use the SSH command line only to transport that one Base64 token. Put Windows paths, usernames, arrays, JVM flags and script invocation syntax inside the encoded script text.

Example controller-side PowerShell:

```powershell
$remote = @'
& 'C:\bench\BootOptim\tools\laptop-bench\remote_laptop_transaction.ps1' `
  -Action Preflight `
  -RunId 'agent41-example-001' `
  -InstanceRoot 'C:\path with spaces\Prism\instances\Exact Pack' `
  -PrismExe 'C:\path with spaces\PrismLauncher.exe' `
  -InstanceId 'Exact Pack' `
  -InteractiveUser 'wachi' `
  -ArtifactJar 'C:\bench\staging\bootoptim.jar' `
  -ExpectedJarSha256 '<SHA256>' `
  -JvmArgs '-Dboot_optim.startupLog=true -Dboot_optim.autoExit=true' `
  -RequiredJvmArg '-Dboot_optim.startupLog=true','-Dboot_optim.autoExit=true' `
  -ForbiddenJvmArg '-Dboot_optim.profileStartupVariance=true' `
  -TimeoutSeconds 900
'@
$b64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remote))
ssh <host> "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand $b64"
```

Do not build an SSH command such as `ssh host powershell -Command "... 'C:\path with spaces' ..."` with three quoting grammars interleaved. Do not put secrets in the encoded command: Base64 is transport encoding, not encryption.

## Transaction interface

The state root defaults to `%LOCALAPPDATA%\BootOptimBench\<RunId>\state.json`. A pre-existing transaction blocks another `Stage`; recovery is explicit.

### `Preflight` — read only

Checks the instance, `.minecraft`, `mods`, `instance.cfg`, Prism executable, candidate artifact and expected SHA-256; candidate wrapper marker; exactly one existing live BootOptim wrapper; no Prism; no target `java/javaw`; and exactly one WTS-active Explorer session for the requested user.

It reports the active session ID and original/candidate/config hashes. It does not touch the instance.

### `Stage` — reversible mutation before launch

Repeats preflight, copies the exact original `instance.cfg` and original wrapper into the transaction backup, writes the transaction state **before** live mutation, replaces the live wrapper atomically enough to verify its SHA, requires exactly one live wrapper afterward, sets `OverrideJavaArgs=true`, and replaces (not appends to) `JvmArgs`.

`JvmArgs` is deliberately a complete benchmark value. Do not append a diagnostic flag to whatever Prism happened to retain: that is how obsolete arguments survive. The helper rejects quoted/newline/NUL JVM strings; keep path-bearing quoted arguments out of this benchmark contract rather than relying on ad-hoc QSettings escaping.

### `Run` — interactive on-demand task

Rechecks no Prism/target Java, the active session ID, and exactly-one candidate JAR. It registers an on-demand Scheduled Task with:

- the specified user;
- `LogonType Interactive`;
- `RunLevel Limited` (no hidden elevation change);
- `MultipleInstances IgnoreNew`;
- a bounded task execution limit;
- a PowerShell `-EncodedCommand` action that invokes `remote_laptop_interactive_run.ps1`.

The runner launches Prism with `-l <instance ID>` (and `-d <Prism root>` when explicitly supplied), waits only for the new target Java to appear, captures the effective `Win32_Process` identity/command line once, validates it, then performs a blocking OS process wait. It does **not** poll `latest.log`, resource packs, filesystem counters, JVM MXBeans, WMI samples, or another profiler during the measurement.

The default Java timeout is `900 s`. A value below `600 s` is rejected because the established physical regime is about `350–380 s` to menu; a five-minute guard can kill a valid slow run before the endpoint. Timeout remains an invalid diagnostic outcome and is never treated as TTMM.

### `Status`

Reads the transaction JSON and Task Scheduler state. Do not poll this repeatedly during the benchmark on the old HDD. Prefer no remote reads while `phase=measuring`; check after the normal auto-exit horizon.

### `Postflight`

Accepted only after the runner says `finished` or `invalid`. It refuses restoration while Prism or the target Java still exists, unregisters the task, removes the staged wrapper, restores the original wrapper and exact original `instance.cfg`, and verifies both hashes plus the exactly-one-JAR invariant.

Run the existing resource-selection checker and parsers only after this point, on completed logs/artifacts.

### `Recover`

Uses the same exact-hash restoration path after an interrupted transaction. By default it refuses to kill a recorded process. `-ForceStopOwned` is an explicit recovery-only escape hatch and targets only the PIDs recorded by this transaction; it must never be used as a normal auto-close mechanism during timing.

## Effective command-line acceptance

Use a required list containing the complete benchmark contract relevant to the run, for example the established auto-exit/startup marker property and any one diagnostic property that is intentionally enabled. Put known obsolete/foreign diagnostic properties in `-ForbiddenJvmArg`.

The runner also rejects repeated `-Dboot_optim.<key>=` keys even when the values happen to match. This prevents Java's last-property-wins behavior from hiding stale Prism arguments.

When a particular Oracle JDK executable is part of the comparison contract, pass `-ExpectedJavaExe`; otherwise the tool records, but does not assume, the executable path. This distinguishes `java.exe` from `javaw.exe` without requiring one particular launcher choice.

## What not to do during the measured interval

From Java process validation until Java exits:

- no `Get-Content -Wait` / repeated reads of `latest.log`;
- no recursive hashing or resource-selection scans;
- no copying/moving BootOptim JARs or resource packs;
- no edits/restoration of `instance.cfg`;
- no Prism restart/close command;
- no repeated WMI/process/Task-Scheduler polling from SSH;
- no cache purge, antivirus manipulation, reboot, Windows counter reset, Java/driver/power-plan change;
- no additional Java profiler/JFR solely for this remote-operation problem.

A single blocking wait on the already-identified Java PID is sufficient for orchestration. Offline evidence collection happens after exit.

## Acceptance checklist for a retained physical result

A result may enter a timing table only when all are true:

- measurement origin and endpoint are stated per `AGENTS.md`;
- preflight and stage hashes are saved;
- one WTS-active intended user/session is resolved;
- exactly one packaged BootOptim wrapper is live and has the expected SHA-256;
- no stale target Java or Prism process exists at launch;
- the new Java PID/creation time belongs to the instance and the expected session;
- effective command line contains every required JVM flag exactly as intended and no forbidden/duplicate BootOptim property;
- the run reaches its semantic endpoint and is not a timeout;
- the normal resource selection/order checker passes every effective reload;
- stale JVM age/provenance gates pass;
- `instance.cfg` and the original BootOptim JAR are restored byte/hash-exactly after Prism exits;
- inclusive/task-count diagnostics are not converted into TTMM savings.

The `350330 ms` corrected P0.2 result remains a physical observation from its documented origin. The supplied later `355582/361195 ms` diagnostic values can only be compared after their memory/instrumentation and transaction provenance satisfy the same contract; an instrumentation run exceeding the intended fixed-memory envelope is diagnostic evidence, not a clean production TTMM comparison.

## External references

- Microsoft PowerShell `-EncodedCommand`: https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-5.1
- Microsoft Task Scheduler interactive token: https://learn.microsoft.com/en-us/windows/win32/api/taskschd/nf-taskschd-itaskfolder-registertask
- Microsoft `schtasks /create` `/IT`: https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/schtasks-create
- Microsoft `Win32_Process`: https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process
- Prism CLI: https://prismlauncher.org/wiki/getting-started/command-line-interface/
