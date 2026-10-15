<#
Physical laptop benchmark transaction driven through BootOptimPandora instead of Prism.

Same guarantees as remote_laptop_transaction.ps1 (see docs/research/remote-laptop-operation-audit-2026-09-07.md):
launcher and target Java stopped before any mutation, exact backups restored and hash-verified, exactly one
BootOptim wrapper, effective Java command line validated (never persisted raw), blocking wait without log polling.

Pandora keeps the per-instance JVM flags and Java binary in <instance>\info_v1.json (jvm_flags / jvm_binary)
and is launched with `--run-instance-normal <name> --no-update` (Pandora PR #66/#100), so no launcher config
can be rewritten behind our back the way Prism rewrites instance.cfg, and a pending profile update never
changes the pack mid-series (it is only reported). Update the instance deliberately, before a series.

Actions (all take -RunId; state lives in <StateRoot>\<RunId>\state.json):
  Stage      back up info_v1.json + live wrapper, install candidate wrapper, write jvm_flags/jvm_binary
  Run        register an Interactive scheduled task in the active session that runs -Action Measure, start it
  Measure    (inside the task) start Pandora, bind the target Java, validate args, block until it exits
  Status     print state.json
  Postflight stop owned Pandora/Java, restore exact backups, verify hashes
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('Stage','Run','Measure','Status','Postflight')][string]$Action,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')][string]$RunId,
    [string]$InstanceDir,
    [string]$InstanceName,
    [string]$PandoraExe,
    [string]$ArtifactJar,
    [string]$ExpectedJarSha256,
    [string]$JvmFlags,
    [string]$JavaPath,
    [string[]]$RequiredJvmArg = @(),
    [string[]]$ForbiddenJvmArg = @(),
    [string]$InteractiveUser,
    [int]$TimeoutSeconds = 1200,
    [int]$LaunchGraceSeconds = 600,
    [string]$StateRoot = 'C:\BootOptimBench\state\pandora'
)
$ErrorActionPreference = 'Stop'
$Utf8 = New-Object Text.UTF8Encoding $false

function Fail([string]$m) { throw "BENCH_FAIL: $m" }
function Sha([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToUpperInvariant() }
function Save($st, [string]$f) { $t = "$f.tmp"; [IO.File]::WriteAllText($t, ($st | ConvertTo-Json -Depth 10), $Utf8); Move-Item -LiteralPath $t -Destination $f -Force }
function Load([string]$f) { Get-Content -LiteralPath $f -Raw | ConvertFrom-Json }
function Pandora-Procs { @(Get-CimInstance Win32_Process -Filter "Name like 'BootOptimPandora%' or Name='pandora_launcher.exe' or Name='WachilandLauncher.exe'") }
function Target-Java([string]$gameRoot, [string]$marker) {
    # The launcher starts Java with the game directory as working directory and does not always pass it on the
    # command line, so a per-run marker property is the reliable identity; without a marker, match any Java that
    # references the game directory or the launcher data directory (natives/libraries).
    $launcherData = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $gameRoot))).ToLowerInvariant()
    $game = $gameRoot.TrimEnd('').ToLowerInvariant()
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | Where-Object {
        if (-not $_.CommandLine) { return $false }
        if ($marker) { return ([Bench.Argv]::Split($_.CommandLine) -ccontains $marker) }
        $c = $_.CommandLine.ToLowerInvariant().Replace('/', '')
        $c.Contains($game) -or $c.Contains($launcherData)
    })
}
function Wrappers([string]$mods) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    @(Get-ChildItem -LiteralPath $mods -Filter *.jar -File | Where-Object {
        $z = $null
        try { $z = [IO.Compression.ZipFile]::OpenRead($_.FullName); [bool]($z.Entries | Where-Object FullName -eq 'dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class') }
        catch { $false } finally { if ($z) { $z.Dispose() } }
    })
}
Add-Type -Namespace Bench -Name Argv -MemberDefinition @'
[DllImport("shell32.dll", SetLastError = true)] static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)] string cmd, out int n);
[DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
public static string[] Split(string cmd) {
    int n; IntPtr p = CommandLineToArgvW(cmd, out n); if (p == IntPtr.Zero) return new string[0];
    try { var a = new string[n]; for (int i = 0; i < n; i++) a[i] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(p, i * IntPtr.Size)); return a; }
    finally { LocalFree(p); }
}
'@

$dir = Join-Path $StateRoot $RunId
$stateFile = Join-Path $dir 'state.json'

switch ($Action) {
'Stage' {
    if (Test-Path -LiteralPath $stateFile) { Fail 'transaction already exists' }
    if ((Pandora-Procs).Count) { Fail 'Pandora is running' }
    $info = Join-Path $InstanceDir 'info_v1.json'; if (-not (Test-Path -LiteralPath $info)) { Fail "missing $info" }
    $launcherData = Split-Path -Parent (Split-Path -Parent $InstanceDir)
    foreach ($d in 'libraries', 'assets', 'logconfigs') { if (-not (Test-Path -LiteralPath (Join-Path $launcherData $d))) { Fail "launcher data dir $launcherData lacks '$d' (data-dir rename without migration?)" } }
    $pref = (Get-Content -LiteralPath $info -Raw | ConvertFrom-Json).preferred_account
    $acc = Join-Path $launcherData 'accounts.json'
    if (-not (Test-Path -LiteralPath $acc)) { Fail "no accounts.json in $launcherData; the user must log in once" }
    if ($pref -and -not ((Get-Content -LiteralPath $acc -Raw | ConvertFrom-Json).accounts.PSObject.Properties.Name -contains $pref)) { Fail "instance preferred_account $pref is not in accounts.json; the launcher would wait for an interactive login" }
    $game = Join-Path $InstanceDir '.minecraft'; $mods = Join-Path $game 'mods'
    if ((Target-Java $game).Count) { Fail 'target Java is running' }
    if ((Sha $ArtifactJar) -ne $ExpectedJarSha256.ToUpperInvariant()) { Fail 'artifact hash mismatch' }
    $live = Wrappers $mods; if ($live.Count -ne 1) { Fail "expected exactly one BootOptim wrapper in mods, found $($live.Count)" }
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'backup') | Out-Null
    $infoBak = Join-Path $dir 'backup\info_v1.json'; Copy-Item -LiteralPath $info -Destination $infoBak
    $jarBak = Join-Path $dir ('backup\' + $live[0].Name); Copy-Item -LiteralPath $live[0].FullName -Destination $jarBak
    $st = [ordered]@{ runId = $RunId; phase = 'staging'; valid = $false; reason = $null; instanceDir = $InstanceDir; instanceName = $InstanceName
        gameRoot = $game; modsDir = $mods; info = $info; infoBackup = $infoBak; originalInfoSha256 = (Sha $info)
        originalJarPath = $live[0].FullName; jarBackup = $jarBak; originalJarSha256 = (Sha $jarBak)
        stagedJar = (Join-Path $mods ('bootoptim-bench-' + $RunId + '.jar')); candidateSha256 = $ExpectedJarSha256.ToUpperInvariant()
        pandoraExe = $PandoraExe; javaPath = $JavaPath; jvmFlags = $JvmFlags; runMarker = "-Dboot_optim.benchRunId=$RunId"; required = @($RequiredJvmArg); forbidden = @($ForbiddenJvmArg)
        interactiveUser = $InteractiveUser; timeoutSeconds = $TimeoutSeconds; launchGraceSeconds = $LaunchGraceSeconds
        taskName = ('BootOptimPandoraBench-' + $RunId); javaPid = 0; javaCreationDate = $null; pandoraPid = 0; javaExitCode = $null
        commandLineSha256 = $null; launchedUtc = $null; javaSeenUtc = $null; javaExitUtc = $null
        javaExe = $null; bootOptimKeys = @(); stagedInfoSha256 = $null; measuredPhase = $null }
    Save $st $stateFile
    Remove-Item -LiteralPath $live[0].FullName
    Copy-Item -LiteralPath $ArtifactJar -Destination $st.stagedJar
    if ((Sha $st.stagedJar) -ne $st.candidateSha256) { Fail 'staged copy hash mismatch' }
    $j = Get-Content -LiteralPath $info -Raw | ConvertFrom-Json
    $runMarker = "-Dboot_optim.benchRunId=$RunId"
    $flags = [pscustomobject]@{ enabled = $true; flags = ($JvmFlags + ' ' + $runMarker) }
    $bin = [pscustomobject]@{ enabled = $true; path = $JavaPath }
    if ($j.PSObject.Properties['jvm_flags']) { $j.jvm_flags = $flags } else { $j | Add-Member jvm_flags $flags }
    if ($JavaPath) { if ($j.PSObject.Properties['jvm_binary']) { $j.jvm_binary = $bin } else { $j | Add-Member jvm_binary $bin } }
    [IO.File]::WriteAllText($info, ($j | ConvertTo-Json -Depth 20), $Utf8)
    $st.stagedInfoSha256 = Sha $info; $st.phase = 'staged'; Save $st $stateFile
    $st | ConvertTo-Json -Depth 5; break
}
'Run' {
    $st = Load $stateFile; if ($st.phase -ne 'staged') { Fail "Run refuses phase $($st.phase)" }
    if ((Pandora-Procs).Count) { Fail 'Pandora appeared after staging' }
    if ((Sha $st.info) -ne $st.stagedInfoSha256) { Fail 'info_v1.json changed after Stage' }
    $self = $MyInvocation.MyCommand.Path
    $cmd = "& '" + $self.Replace("'", "''") + "' -Action Measure -RunId '" + $RunId + "' -StateRoot '" + $StateRoot.Replace("'", "''") + "'"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    $a = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument "-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand $b64"
    $p = New-ScheduledTaskPrincipal -UserId $st.interactiveUser -LogonType Interactive -RunLevel Limited
    $s = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Seconds ($st.timeoutSeconds + $st.launchGraceSeconds + 300))
    Register-ScheduledTask -TaskName $st.taskName -Action $a -Principal $p -Settings $s -Force | Out-Null
    $st.phase = 'dispatched'; Save $st $stateFile
    Start-ScheduledTask -TaskName $st.taskName
    [pscustomobject]@{ status = 'dispatched'; taskName = $st.taskName } | ConvertTo-Json; break
}
'Measure' {
    $st = Load $stateFile
    try {
        $st.phase = 'launching'; $st.launchedUtc = [DateTime]::UtcNow.ToString('o'); Save $st $stateFile
        $pd = Start-Process -FilePath $st.pandoraExe -ArgumentList @('--run-instance-normal', $st.instanceName, '--no-update') -PassThru
        $st.pandoraPid = $pd.Id; Save $st $stateFile
        $launcherLog = Join-Path (Split-Path -Parent (Split-Path -Parent $st.instanceDir)) 'launcher.log'
        $deadline = (Get-Date).AddSeconds($st.launchGraceSeconds); $java = $null; $launchError = $null; $i = 0
        while (-not $java -and -not $launchError -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2; $java = Target-Java $st.gameRoot $st.runMarker | Select-Object -First 1
            # Pandora exits without starting Java on 4 (update failed / instance busy) and 5 (no such instance).
            if (-not $java -and $pd.HasExited -and $pd.ExitCode -ne 0) { $launchError = "launcher exited with code $($pd.ExitCode) before starting Java" }
            if (-not $java -and (++$i % 5) -eq 0 -and (Test-Path -LiteralPath $launcherLog)) {
                $launchError = Get-Content -LiteralPath $launcherLog -Tail 200 | Where-Object { $_ -match 'Failed to launch|Auth Flow: Initial' } | Select-Object -Last 1
            }
        }
        if ($launchError) { $st.phase = 'invalid'; $st.reason = "launcher reported: $launchError"; Save $st $stateFile; return }
        if (-not $java) { $st.phase = 'invalid'; $st.reason = "no target Java within $($st.launchGraceSeconds) s"; Save $st $stateFile; return }
        $proc = Get-Process -Id $java.ProcessId
        $st.javaPid = $java.ProcessId; $st.javaCreationDate = $java.CreationDate.ToString('o'); $st.javaSeenUtc = [DateTime]::UtcNow.ToString('o')
        $argv = [Bench.Argv]::Split($java.CommandLine)
        $st.commandLineSha256 = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($Utf8.GetBytes($java.CommandLine))).Replace('-', '')
        $st.javaExe = $java.ExecutablePath
        $missing = @(); foreach ($r in @($st.required) + @($st.runMarker)) { if (@($argv | Where-Object { $_ -ceq $r }).Count -ne 1) { $missing += $r } }
        $present = @(); foreach ($f in $st.forbidden) { if ($argv -ccontains $f) { $present += $f } }
        $st.bootOptimKeys = @($argv | Where-Object { $_ -like '-Dboot_optim.*' } | ForEach-Object { ($_ -split '=')[0] } | Sort-Object -Unique)
        if ($st.javaPath -and -not $java.ExecutablePath.Equals(($st.javaPath -replace '/', '\'), [StringComparison]::OrdinalIgnoreCase)) { $missing += "java=$($st.javaPath)" }
        if ($missing.Count -or $present.Count) {
            $st.phase = 'invalid'; $st.reason = "arg check failed missing=[$($missing -join ',')] forbidden=[$($present -join ',')]"; Save $st $stateFile
            Stop-Process -Id $java.ProcessId -Force -ErrorAction SilentlyContinue; return
        }
        $st.phase = 'measuring'; Save $st $stateFile
        if (-not $proc.WaitForExit($st.timeoutSeconds * 1000)) {
            $st.phase = 'invalid'; $st.reason = "Java still running after $($st.timeoutSeconds) s"; Save $st $stateFile; return
        }
        $st.javaExitUtc = [DateTime]::UtcNow.ToString('o'); $st.javaExitCode = $proc.ExitCode
        $st.phase = 'finished'; $st.valid = $true; Save $st $stateFile
    } catch {
        $st.phase = 'invalid'; $st.reason = "runner error: $($_.Exception.Message)"; Save $st $stateFile
    }
    break
}
'Status' { Get-Content -LiteralPath $stateFile -Raw; break }
'Postflight' {
    $st = Load $stateFile
    if ($st.phase -in @('launching', 'measuring', 'dispatched')) { Fail "Postflight refuses phase $($st.phase); wait or inspect" }
    if ($st.javaPid) { $j = Get-CimInstance Win32_Process -Filter "ProcessId=$($st.javaPid)"; if ($j -and $j.CreationDate.ToString('o') -eq $st.javaCreationDate) { Stop-Process -Id $st.javaPid -Force } }
    foreach ($p in Pandora-Procs) { if ($p.ProcessId -eq $st.pandoraPid) { Stop-Process -Id $p.ProcessId -Force } }
    Start-Sleep -Seconds 3
    if ((Pandora-Procs).Count) { Fail 'a Pandora process is still running; restoration waits until it is closed' }
    if ((Target-Java $st.gameRoot).Count) { Fail 'target Java still running' }
    try { Unregister-ScheduledTask -TaskName $st.taskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}
    if ((Sha $st.jarBackup) -ne $st.originalJarSha256 -or (Sha $st.infoBackup) -ne $st.originalInfoSha256) { Fail 'backup changed' }
    if (Test-Path -LiteralPath $st.stagedJar) { if ((Sha $st.stagedJar) -ne $st.candidateSha256) { Fail 'staged JAR changed' }; Remove-Item -LiteralPath $st.stagedJar -Force }
    if (-not (Test-Path -LiteralPath $st.originalJarPath)) { Copy-Item -LiteralPath $st.jarBackup -Destination $st.originalJarPath }
    Copy-Item -LiteralPath $st.infoBackup -Destination $st.info -Force
    $w = Wrappers $st.modsDir
    if ($w.Count -ne 1 -or (Sha $w[0].FullName) -ne $st.originalJarSha256 -or (Sha $st.info) -ne $st.originalInfoSha256) { Fail 'restore verification failed' }
    $prev = $st.phase; $st.phase = 'restored'; $st.measuredPhase = $prev; Save $st $stateFile
    [pscustomobject]@{ status = 'restored'; valid = $st.valid; reason = $st.reason } | ConvertTo-Json; break
}
}
