# Remote laptop benchmark operation audit — 2026-09-07

Base authority: `agent/integration-current` at `2bccf5f4fa221c78e286d052beb78636fa4c317b`.

Status: tooling/documentation only. No Minecraft/NeoForge runtime, production optimization, Windows setting, Java installation, driver, power-plan, gameplay behavior, profiler, or resource selection is changed by this branch.

## Decision

Remote laptop operation must be a recoverable transaction, not an ad-hoc sequence of SSH/PowerShell commands. A retained physical result must prove all of these conditions:

1. Prism is fully stopped before `instance.cfg` is edited.
2. No prior target `java.exe` or `javaw.exe` survives.
3. Exactly one BootOptim JAR exists in live `mods`, and it is the packaged early-service wrapper, not the inner regular-mod JAR.
4. The candidate wrapper SHA-256 equals the expected artifact SHA-256.
5. Prism is launched through Task Scheduler using the intended logged-on interactive account/session, not SYSTEM/S4U/an SSH service session.
6. The new Java process is bound to instance path + PID + `CreationDate` + Windows `SessionId`; PID alone is never ownership proof.
7. The effective Java command line is tokenized and validated in memory: every required JVM token occurs exactly once, forbidden tokens are absent, duplicate `-Dboot_optim.*` property keys are rejected, and singleton families such as `-Xmx`, `-Xms`, and `-XX:ActiveProcessorCount=` may not be duplicated.
8. The raw effective command line is **not persisted**, because launcher arguments may contain account/access-token material. The state keeps only a SHA-256 of the raw line plus safe validated BootOptim property keys/required tokens.
9. After identity validation the runner performs a blocking process wait; it does not poll logs, resources, WMI/process state, Task Scheduler, or JVM profilers during the accepted run.
10. A timeout is invalid/inconclusive, never TTMM. Prism must be closed before exact original `instance.cfg` and BootOptim bytes are restored and hash-verified.

The scripts in `tools/laptop-bench/` implement those boundaries and are default-off. The CI workflow only parses their Windows PowerShell syntax; it does not execute a physical benchmark.

## Confirmed failures from project evidence

### Stale JVM provenance

PR #130 records a physical campaign with `mod_entrypoint=985056 ms` while visible FML/ModernFix phases were ordinary: the JVM had remained alive during Prism preparation. PR #147 quantified an unobserved `842.733 s` prefix. Such runs are invalid before aggregation.

This is why the transaction checks both `java.exe` and `javaw.exe` and refuses any pre-existing process whose command line identifies the target instance.

### Prism overwriting intended JVM arguments

`AGENTS.md`, PR #157 and the corrected P0.2 variance record establish the operational rule: stop Prism before editing `instance.cfg`, then verify the *effective* Java command line on the next launch. The first P0.2 launch was discarded because Prism rewrote the JVM options without the intended variance property. Only the corrected run is evidence.

Correct sequence:

`Prism stopped -> Preflight -> Stage -> interactive launch -> effective command-line validation -> blocking Java wait -> Prism close -> Postflight/Recover`

Do not restore `instance.cfg` while Prism is alive; it may subsequently save stale in-memory values over the restored file.

### Wrong/duplicate BootOptim JAR

`AGENTS.md` requires exactly one BootOptim JAR and identifies `bootstrap/build/libs/` as the distributable artifact. The transaction recognizes the wrapper by `dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class` and separately recognizes the inner mod by `dev/wachipayox/bootoptim/BootOptim.class`. One inner JAR, two BootOptim JARs, or an ambiguous artifact is rejected.

This is stronger than filename matching and prevents a renamed inner JAR from accidentally being accepted as the packaged benchmark artifact.

### Resource files on disk are not workload proof

Historical resource-selection work already proved that resource ZIPs may exist while not being selected. `tools/laptop-bench/check_resource_selection.py` remains a post-run gate. Counts of files/JARs/resources are correctness checks, not TTMM improvements.

## Hypotheses / prevented failure classes

### Invisible or wrong Windows session

Public Task Scheduler documentation defines `TASK_LOGON_INTERACTIVE_TOKEN` as requiring an already logged-on user and running only in an existing interactive session. Therefore a GUI process created directly by the SSH service, SYSTEM, or S4U is not accepted merely because it exists.

The tooling resolves one `WTSActive` Explorer session for the intended account before launch, uses `New-ScheduledTaskPrincipal -LogonType Interactive -RunLevel Limited`, and the runner rechecks its own, Prism's and Java's `SessionId` against that session. This prevents invisible-session launches, but public BootOptim evidence does **not** prove that this was the cause of any already-retained physical run.

### PID reuse during recovery

A recovery script that stores only a PID can kill an unrelated later process if Windows reuses the number. The tooling records `CreationDate` and `SessionId`; `Recover -ForceStopOwned` kills only when the live process still matches the recorded identity and expected executable class. A PID mismatch simply fails safe.

### Staging collisions / partial copies

Candidate staging refuses an already-existing target path rather than overwriting it. Candidate copy uses a GUID-suffixed temporary file, verifies SHA-256, moves to the final `.jar`, and removes any leftover temporary in `finally`. Recovery refuses to delete unexpected BootOptim artifacts.

## SSH / PowerShell quoting boundary

Use SSH only to transport one Base64 token. Microsoft documents `powershell.exe -EncodedCommand` as Base64 over UTF-16LE and specifically intended for commands with complex nested quoting.

Controller-side example:

```powershell
$remote = @'
& 'C:\bench\BootOptim\tools\laptop-bench\remote_laptop_transaction.ps1' `
  -Action Preflight `
  -RunId 'agent41-example-001' `
  -InstanceRoot 'C:\path with spaces\Prism\instances\Exact Pack' `
  -PrismExe 'C:\path with spaces\PrismLauncher.exe' `
  -PrismRoot 'C:\path with spaces\Prism' `
  -InstanceId 'Exact Pack' `
  -InteractiveUser 'MACHINE\wachi' `
  -ArtifactJar 'C:\bench\staging\bootoptim.jar' `
  -ExpectedJarSha256 '<64-hex-sha256>' `
  -JvmArgs '-Xmx6G -XX:ActiveProcessorCount=4 -Dboot_optim.startupLog=true -Dboot_optim.autoExit=true' `
  -RequiredJvmArg '-Xmx6G','-XX:ActiveProcessorCount=4','-Dboot_optim.startupLog=true','-Dboot_optim.autoExit=true' `
  -ForbiddenJvmArg '-Dboot_optim.profileStartupVariance=true' `
  -TimeoutSeconds 900
'@
$b64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remote))
ssh <host> "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand $b64"
```

Prefer a qualified account such as `COMPUTER\user` or `DOMAIN\user` when available. Do not splice Windows paths/JVM flags directly through nested `ssh ... powershell -Command "..."` quoting. Base64 is transport encoding, not encryption; do not place secrets in the encoded command.

## Interface

### `Preflight` — read-only

Validates paths, `instance.cfg`, Prism executable, candidate SHA-256/type, exactly-one live packaged wrapper, absence of Prism/target Java, and exactly one matching `WTSActive` Explorer session. It reports hashes and session identity without modifying the instance.

### `Stage` — reversible mutation

Repeats preflight, backs up exact original `instance.cfg` and wrapper, persists transaction state **before** live mutation, replaces the wrapper with the candidate, hash-verifies it, and sets `OverrideJavaArgs=true` plus a complete `JvmArgs` value.

`JvmArgs` is replaced, not appended to Prism's previous value. QSettings-sensitive backslashes/quotes are escaped; newline and NUL are rejected. Duplicate `OverrideJavaArgs`/`JvmArgs` keys in `instance.cfg` are rejected rather than silently editing one of several conflicting values.

### `Run` — interactive launch

Rechecks no Prism/target Java, same active session, unchanged staged `instance.cfg`, and exact candidate wrapper. It registers/uses an on-demand task with `LogonType Interactive`, `RunLevel Limited`, `MultipleInstances IgnoreNew`, and a bounded execution limit.

The runner launches Prism with documented `-l <instance ID>` and optional `-d <application root>`. It performs low-rate, filtered Java discovery only until the target process appears, then refreshes CIM identity to close the PID-reuse race, tokenizes the raw command line with `CommandLineToArgvW`, validates it, stores only a hash/safe summary, and blocks on the verified Java handle.

The 1-second discovery query is an unavoidable small observer effect before Java identity is known; there is no continuing sampler after acceptance. No claim should treat this tooling as measurement-zero-cost.

Default Java timeout is `900 s`; values below `600 s` are rejected because valid physical startup is already around `350–380 s`.

### `Status`

Reads transaction JSON. While phase is `launching`, `validating`, or `measuring`, the script suppresses the Task Scheduler query and reports `taskState=suppressed_during_run`. Operationally, do not poll `Status` during the timed startup; inspect it after the normal auto-exit horizon.

### `Postflight`

Allowed only after `finished`/`invalid`. It refuses restoration if the target Java or any Prism remains, unregisters the task, removes only the exact staged candidate, restores the exact original wrapper/config, and verifies hashes and exactly-one-wrapper invariant.

Resource-selection parsing and other artifact reads happen after this point.

### `Recover`

For interrupted transactions. Default behavior refuses to kill a still-owned process. `-ForceStopOwned` is recovery-only and requires matching PID + `CreationDate` + session + process class before killing. Unexpected BootOptim JARs block recovery instead of being deleted.

## What must not happen during the timed startup

- no Prism restart/close/edit after launch;
- no `instance.cfg`, JAR or resource-pack copy/move/hash scan;
- no repeated `latest.log`/startup-log reads;
- no resource-selection checker until exit;
- no repeated WMI/process/Task Scheduler polling over SSH after Java identity acceptance;
- no cache purge, reboot, Defender/AV manipulation, Windows counter reset, Java/driver/power-plan change;
- no second Java profiler/JFR for this remote-operation question;
- no interpretation of timeout, task counts, marker counts, inclusive listener sums or instrumentation overhead as TTMM improvement.

A run that used instrumentation exceeding the intended fixed-memory envelope is diagnostic evidence, not a clean production timing comparison.

## Acceptance criteria for a retained physical result

All must pass:

- origin/start marker/endpoint are explicit (`main_menu` vs `main_menu_presented` is not mixed);
- original and staged config/JAR hashes exist;
- one intended `WTSActive` session is identified;
- one packaged wrapper with expected SHA-256 is live;
- no stale Prism/target Java exists at dispatch;
- Java PID + `CreationDate` + instance path + session are consistent;
- effective JVM tokens match the required contract exactly and contain no forbidden/duplicate BootOptim property keys or duplicate singleton memory/processor options;
- semantic endpoint is reached; timeout is excluded;
- effective resource-selection/order checker passes;
- postflight restores exact original `instance.cfg` and wrapper hashes after Prism exits;
- inclusive/task-count diagnostics are not converted into TTMM savings.

The corrected P0.2 result remains `350330 ms` to `main_menu` and `356274 ms` to `main_menu_presented`. Later `355582/361195 ms` diagnostic values should not be treated as clean production A/B evidence when their instrumentation exceeded the intended memory envelope; they remain useful diagnostic observations only.

## Residual risks

- Task Scheduler/WTS/session correctness is strongly checked, but this tooling has not itself been exercised on the private physical laptop from this PR.
- Java discovery still requires a small number of filtered CIM reads before ownership can be established; after acceptance there is no polling.
- `ForbiddenJvmArg` is an exact-token deny list. If a future benchmark needs to forbid a property key regardless of value, add an explicit key-level deny parameter rather than assuming substring semantics.
- The command-line SHA proves which raw line was validated within a run but is not intended to reconstruct potentially sensitive launcher arguments.

## Public references

- PowerShell `-EncodedCommand`: https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-5.1
- Task Scheduler interactive token: https://learn.microsoft.com/en-us/windows/win32/taskschd/taskfolder-registertask
- `New-ScheduledTaskPrincipal`: https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal
- `Win32_Process`: https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process
- Prism CLI: https://prismlauncher.org/wiki/getting-started/command-line-interface/
