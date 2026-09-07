[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Stage', 'Run', 'Status', 'Postflight', 'Recover', 'InteractiveRun')]
    [string] $Action,

    [string] $RunId,
    [string] $InstanceRoot,
    [string] $PrismExe,
    [string] $PrismRoot,
    [string] $InstanceId,
    [string] $InteractiveUser,
    [string] $ArtifactJar,
    [string] $ExpectedJarSha256,
    [string] $JvmArgs,
    [string[]] $RequiredJvmArg = @(),
    [string[]] $ForbiddenJvmArg = @(),
    [string] $ExpectedJavaExe,
    [int] $TimeoutSeconds = 900,
    [string] $StateRoot = (Join-Path $env:LOCALAPPDATA 'BootOptimBench'),
    [string] $StateFile,
    [switch] $ForceStopOwned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$BootOptimWrapperMarker = 'dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Fail([string] $Message) {
    throw "BOOTOPTIM_REMOTE_INVALID: $Message"
}

function Resolve-FullPath([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    return [System.IO.Path]::GetFullPath($Path)
}

function Get-FileSha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Save-JsonAtomic([object] $Value, [string] $Path) {
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temp = "$Path.tmp-$PID"
    [System.IO.File]::WriteAllText($temp, ($Value | ConvertTo-Json -Depth 8), $Utf8NoBom)
    Move-Item -LiteralPath $temp -Destination $Path -Force
}

function Load-State([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail "state file does not exist: $Path" }
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

function Get-StateFilePath {
    if (-not [string]::IsNullOrWhiteSpace($StateFile)) { return (Resolve-FullPath $StateFile) }
    if ([string]::IsNullOrWhiteSpace($RunId)) { Fail 'RunId or StateFile is required' }
    return (Join-Path (Resolve-FullPath $StateRoot) (Join-Path $RunId 'state.json'))
}

function Test-BootOptimWrapper([string] $JarPath) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            foreach ($entry in $zip.Entries) {
                if ($entry.FullName -eq $BootOptimWrapperMarker) { return $true }
            }
            return $false
        } finally {
            $zip.Dispose()
        }
    } catch {
        return $false
    }
}

function Get-BootOptimWrappers([string] $ModsDir) {
    if (-not (Test-Path -LiteralPath $ModsDir -PathType Container)) { return @() }
    return @(Get-ChildItem -LiteralPath $ModsDir -File -Filter '*.jar' | Where-Object { Test-BootOptimWrapper $_.FullName } | ForEach-Object {
        [pscustomobject]@{
            path = $_.FullName
            name = $_.Name
            sha256 = Get-FileSha256 $_.FullName
        }
    })
}

function Get-TargetJavaProcesses([string] $GameRoot, [string] $Root) {
    $all = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe', 'javaw.exe') })
    return @($all | Where-Object {
        $cmd = [string]$_.CommandLine
        $cmd -and (($cmd.IndexOf($GameRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or
            ($cmd.IndexOf($Root, [StringComparison]::OrdinalIgnoreCase) -ge 0))
    })
}

function Get-PrismProcesses([string] $ExePath) {
    $names = @('prismlauncher.exe', 'PrismLauncher.exe')
    if ($ExePath) { $names += ([System.IO.Path]::GetFileName($ExePath)) }
    $names = @($names | Select-Object -Unique)
    return @(Get-CimInstance Win32_Process | Where-Object { $names -contains $_.Name })
}

function Ensure-WtsApi {
    if ('BootOptimWts' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BootOptimWts {
    public enum WTS_INFO_CLASS { WTSInitialProgram, WTSApplicationName, WTSWorkingDirectory, WTSOEMId, WTSSessionId, WTSUserName, WTSWinStationName, WTSDomainName, WTSConnectState }
    [DllImport("Wtsapi32.dll", SetLastError=true)] static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, WTS_INFO_CLASS info, out IntPtr buffer, out int bytes);
    [DllImport("Wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr buffer);
    static string QueryString(int sid, WTS_INFO_CLASS info) { IntPtr p; int n; if (!WTSQuerySessionInformation(IntPtr.Zero, sid, info, out p, out n)) return null; try { return Marshal.PtrToStringUni(p); } finally { WTSFreeMemory(p); } }
    public static string User(int sid) { return QueryString(sid, WTS_INFO_CLASS.WTSUserName); }
    public static string Domain(int sid) { return QueryString(sid, WTS_INFO_CLASS.WTSDomainName); }
    public static int State(int sid) { IntPtr p; int n; if (!WTSQuerySessionInformation(IntPtr.Zero, sid, WTS_INFO_CLASS.WTSConnectState, out p, out n)) return -1; try { return Marshal.ReadInt32(p); } finally { WTSFreeMemory(p); } }
}
'@
}

function Get-ActiveInteractiveSession([string] $UserSpec) {
    Ensure-WtsApi
    $expectedDomain = $null
    $expectedUser = $UserSpec
    if ($UserSpec.Contains('\')) {
        $parts = $UserSpec.Split('\', 2)
        $expectedDomain = $parts[0]
        $expectedUser = $parts[1]
    }
    $sessionIds = @(Get-Process explorer -ErrorAction SilentlyContinue | Select-Object -ExpandProperty SessionId -Unique)
    $matches = @()
    foreach ($sid in $sessionIds) {
        $u = [BootOptimWts]::User([int]$sid)
        $d = [BootOptimWts]::Domain([int]$sid)
        $state = [BootOptimWts]::State([int]$sid)
        if ($state -ne 0) { continue } # WTSActive
        if (-not $u -or -not $u.Equals($expectedUser, [StringComparison]::OrdinalIgnoreCase)) { continue }
        if ($expectedDomain -and (-not $d -or -not $d.Equals($expectedDomain, [StringComparison]::OrdinalIgnoreCase))) { continue }
        $matches += [pscustomobject]@{ sessionId = [int]$sid; user = $u; domain = $d }
    }
    if ($matches.Count -ne 1) { Fail "expected exactly one active interactive session for '$UserSpec', found $($matches.Count)" }
    return $matches[0]
}

function Quote-ProcessArgument([string] $Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    if ($Value.Contains('"')) { Fail 'argument contains a double quote and cannot be safely passed to Prism' }
    return '"' + $Value.TrimEnd('\') + '"'
}

function ConvertTo-QSettingsString([string] $Value) {
    if ($Value.Contains("`r") -or $Value.Contains("`n") -or $Value.Contains([char]0)) { Fail 'JvmArgs contains a newline or NUL' }
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Set-InstanceCfgKey([string] $Text, [string] $Key, [string] $Value) {
    $newline = if ($Text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $pattern = '(?m)^' + [regex]::Escape($Key) + '=.*$'
    $replacement = $Key + '=' + $Value
    if ([regex]::IsMatch($Text, $pattern)) {
        return [regex]::Replace($Text, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $replacement }, 1)
    }
    if ($Text.Length -gt 0 -and -not $Text.EndsWith("`n")) { $Text += $newline }
    return $Text + $replacement + $newline
}

function Assert-CleanPreflight([object] $Cfg) {
    if (-not (Test-Path -LiteralPath $Cfg.instanceRoot -PathType Container)) { Fail "instance root missing: $($Cfg.instanceRoot)" }
    if (-not (Test-Path -LiteralPath $Cfg.instanceCfg -PathType Leaf)) { Fail "instance.cfg missing: $($Cfg.instanceCfg)" }
    if (-not (Test-Path -LiteralPath $Cfg.prismExe -PathType Leaf)) { Fail "Prism executable missing: $($Cfg.prismExe)" }
    if (-not (Test-Path -LiteralPath $Cfg.artifactJar -PathType Leaf)) { Fail "candidate JAR missing: $($Cfg.artifactJar)" }
    if (-not (Test-BootOptimWrapper $Cfg.artifactJar)) { Fail 'candidate JAR is not a packaged BootOptim early-service wrapper' }
    $candidateHash = Get-FileSha256 $Cfg.artifactJar
    if ($Cfg.expectedJarSha256 -and ($candidateHash -ne $Cfg.expectedJarSha256.ToUpperInvariant())) { Fail "candidate SHA-256 mismatch: $candidateHash" }
    $active = @(Get-BootOptimWrappers $Cfg.modsDir)
    if ($active.Count -ne 1) { Fail "expected exactly one active BootOptim wrapper before staging, found $($active.Count)" }
    $prism = @(Get-PrismProcesses $Cfg.prismExe)
    if ($prism.Count -ne 0) { Fail "Prism must be fully stopped before instance.cfg/JAR staging; found $($prism.Count) process(es)" }
    $java = @(Get-TargetJavaProcesses $Cfg.gameRoot $Cfg.instanceRoot)
    if ($java.Count -ne 0) { Fail "target instance already has $($java.Count) java/javaw process(es)" }
    $session = Get-ActiveInteractiveSession $Cfg.interactiveUser
    return [pscustomobject]@{ candidateHash = $candidateHash; activeJar = $active[0]; session = $session }
}

function New-ConfigFromParameters {
    foreach ($required in @('RunId','InstanceRoot','PrismExe','InstanceId','InteractiveUser','ArtifactJar','JvmArgs')) {
        if ([string]::IsNullOrWhiteSpace((Get-Variable -Name $required -ValueOnly))) { Fail "$required is required" }
    }
    if ($TimeoutSeconds -lt 600) { Fail 'TimeoutSeconds below 600 is rejected for the 350-380 s physical startup regime' }
    $root = Resolve-FullPath $InstanceRoot
    $game = Join-Path $root '.minecraft'
    $mods = Join-Path $game 'mods'
    if (-not (Test-Path -LiteralPath $game -PathType Container)) { Fail "Prism .minecraft directory missing: $game" }
    return [pscustomobject]@{
        runId = $RunId
        instanceRoot = $root
        instanceCfg = Join-Path $root 'instance.cfg'
        gameRoot = $game
        modsDir = $mods
        prismExe = Resolve-FullPath $PrismExe
        prismRoot = if ($PrismRoot) { (Resolve-FullPath $PrismRoot).TrimEnd('\') } else { $null }
        instanceId = $InstanceId
        interactiveUser = $InteractiveUser
        artifactJar = Resolve-FullPath $ArtifactJar
        expectedJarSha256 = if ($ExpectedJarSha256) { $ExpectedJarSha256.ToUpperInvariant() } else { $null }
        jvmArgs = $JvmArgs
        requiredJvmArgs = @($RequiredJvmArg)
        forbiddenJvmArgs = @($ForbiddenJvmArg)
        expectedJavaExe = if ($ExpectedJavaExe) { Resolve-FullPath $ExpectedJavaExe } else { $null }
        timeoutSeconds = $TimeoutSeconds
    }
}

function Close-OwnedProcess([int] $Pid, [string] $Kind, [switch] $Force) {
    if ($Pid -le 0) { return }
    $p = Get-Process -Id $Pid -ErrorAction SilentlyContinue
    if (-not $p) { return }
    if (-not $Force) { Fail "$Kind PID $Pid is still alive; recovery refuses to kill it without -ForceStopOwned" }
    if ($Kind -eq 'Prism') {
        try { [void]$p.CloseMainWindow(); if ($p.WaitForExit(10000)) { return } } catch {}
    }
    Stop-Process -Id $Pid -Force -ErrorAction Stop
}

$resolvedStateFile = Get-StateFilePath

switch ($Action) {
    'Preflight' {
        $cfg = New-ConfigFromParameters
        if (Test-Path -LiteralPath $resolvedStateFile) { Fail "transaction already exists: $resolvedStateFile; use Status/Postflight/Recover" }
        $result = Assert-CleanPreflight $cfg
        [pscustomobject]@{
            status = 'ok'
            runId = $cfg.runId
            activeSessionId = $result.session.sessionId
            originalBootOptimJar = $result.activeJar.path
            originalBootOptimSha256 = $result.activeJar.sha256
            candidateSha256 = $result.candidateHash
            instanceCfgSha256 = Get-FileSha256 $cfg.instanceCfg
        } | ConvertTo-Json -Depth 5
        break
    }

    'Stage' {
        $cfg = New-ConfigFromParameters
        if (Test-Path -LiteralPath $resolvedStateFile) {
            $existing = Load-State $resolvedStateFile
            if ($existing.phase -eq 'staged') { $existing | ConvertTo-Json -Depth 8; break }
            Fail "transaction already exists in phase '$($existing.phase)'; recover it before staging again"
        }
        $pre = Assert-CleanPreflight $cfg
        $runDir = Split-Path -Parent $resolvedStateFile
        $backupDir = Join-Path $runDir 'backup'
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        $cfgBackup = Join-Path $backupDir 'instance.cfg.original'
        Copy-Item -LiteralPath $cfg.instanceCfg -Destination $cfgBackup -Force
        $jarBackup = Join-Path $backupDir $pre.activeJar.name
        Copy-Item -LiteralPath $pre.activeJar.path -Destination $jarBackup -Force
        if ((Get-FileSha256 $jarBackup) -ne $pre.activeJar.sha256) { Fail 'original BootOptim JAR backup hash mismatch' }

        $state = [ordered]@{
            schema = 1; phase = 'staging'; valid = $false; reason = $null
            runId = $cfg.runId; createdUtc = [DateTime]::UtcNow.ToString('o')
            scriptPath = $MyInvocation.MyCommand.Path; instanceRoot = $cfg.instanceRoot; instanceCfg = $cfg.instanceCfg
            gameRoot = $cfg.gameRoot; modsDir = $cfg.modsDir; prismExe = $cfg.prismExe; prismRoot = $cfg.prismRoot
            instanceId = $cfg.instanceId; interactiveUser = $cfg.interactiveUser; expectedSessionId = $pre.session.sessionId
            artifactJar = $cfg.artifactJar; candidateSha256 = $pre.candidateHash; expectedJavaExe = $cfg.expectedJavaExe
            timeoutSeconds = $cfg.timeoutSeconds; requiredJvmArgs = @($cfg.requiredJvmArgs); forbiddenJvmArgs = @($cfg.forbiddenJvmArgs)
            originalInstanceCfgSha256 = Get-FileSha256 $cfg.instanceCfg; instanceCfgBackup = $cfgBackup
            originalJarPath = $pre.activeJar.path; originalJarSha256 = $pre.activeJar.sha256; originalJarBackup = $jarBackup
            stagedJarPath = $null; stagedInstanceCfgSha256 = $null; taskName = ('BootOptimBench-' + ($cfg.runId -replace '[^A-Za-z0-9_.-]', '_'))
            prismPid = 0; javaPid = 0; javaCreationDate = $null; effectiveCommandLine = $null; effectiveJavaExe = $null
        }
        Save-JsonAtomic $state $resolvedStateFile

        Remove-Item -LiteralPath $pre.activeJar.path -Force
        $targetName = 'bootoptim-bench-' + $pre.candidateHash.Substring(0, 12) + '.jar'
        $targetJar = Join-Path $cfg.modsDir $targetName
        $tempJar = "$targetJar.partial-$PID"
        Copy-Item -LiteralPath $cfg.artifactJar -Destination $tempJar -Force
        if ((Get-FileSha256 $tempJar) -ne $pre.candidateHash) { Fail 'candidate JAR copy hash mismatch' }
        Move-Item -LiteralPath $tempJar -Destination $targetJar -Force
        $activeAfter = @(Get-BootOptimWrappers $cfg.modsDir)
        if ($activeAfter.Count -ne 1 -or $activeAfter[0].sha256 -ne $pre.candidateHash) { Fail 'staging did not leave exactly one expected BootOptim wrapper active' }

        $cfgText = [System.IO.File]::ReadAllText($cfg.instanceCfg)
        $cfgText = Set-InstanceCfgKey $cfgText 'OverrideJavaArgs' 'true'
        $cfgText = Set-InstanceCfgKey $cfgText 'JvmArgs' (ConvertTo-QSettingsString $cfg.jvmArgs)
        [System.IO.File]::WriteAllText($cfg.instanceCfg, $cfgText, $Utf8NoBom)

        $state.stagedJarPath = $targetJar
        $state.stagedInstanceCfgSha256 = Get-FileSha256 $cfg.instanceCfg
        $state.phase = 'staged'
        Save-JsonAtomic $state $resolvedStateFile
        $state | ConvertTo-Json -Depth 8
        break
    }

    'Run' {
        $state = Load-State $resolvedStateFile
        if ($state.phase -notin @('staged', 'task_registered')) { Fail "Run requires staged state, got '$($state.phase)'" }
        if (@(Get-PrismProcesses $state.prismExe).Count -ne 0) { Fail 'Prism became active after staging; abort before launch' }
        if (@(Get-TargetJavaProcesses $state.gameRoot $state.instanceRoot).Count -ne 0) { Fail 'target Java became active after staging; abort before launch' }
        $session = Get-ActiveInteractiveSession $state.interactiveUser
        if ([int]$session.sessionId -ne [int]$state.expectedSessionId) { Fail 'active desktop session changed after staging' }
        $active = @(Get-BootOptimWrappers $state.modsDir)
        if ($active.Count -ne 1 -or $active[0].sha256 -ne $state.candidateSha256) { Fail 'exactly-one candidate BootOptim JAR invariant failed immediately before launch' }

        $scriptPath = [string]$state.scriptPath
        if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { Fail "transaction script missing: $scriptPath" }
        $invoke = "& '" + $scriptPath.Replace("'", "''") + "' -Action InteractiveRun -StateFile '" + $resolvedStateFile.Replace("'", "''") + "'"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($invoke))
        $psExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
        $taskAction = New-ScheduledTaskAction -Execute $psExe -Argument "-NoLogo -NoProfile -NonInteractive -EncodedCommand $encoded"
        $principal = New-ScheduledTaskPrincipal -UserId $state.interactiveUser -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds ([int]$state.timeoutSeconds + 180))
        Register-ScheduledTask -TaskName $state.taskName -Action $taskAction -Principal $principal -Settings $settings -Force | Out-Null
        $state.phase = 'task_registered'
        Save-JsonAtomic $state $resolvedStateFile
        Start-ScheduledTask -TaskName $state.taskName
        [pscustomobject]@{ status = 'started'; runId = $state.runId; taskName = $state.taskName; expectedSessionId = $state.expectedSessionId } | ConvertTo-Json
        break
    }

    'InteractiveRun' {
        $state = Load-State $resolvedStateFile
        $self = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
        if ([int]$self.SessionId -ne [int]$state.expectedSessionId) { Fail "scheduled task landed in session $($self.SessionId), expected $($state.expectedSessionId)" }
        $session = Get-ActiveInteractiveSession $state.interactiveUser
        if ([int]$session.sessionId -ne [int]$state.expectedSessionId) { Fail 'interactive session is no longer the expected active desktop' }
        if (@(Get-PrismProcesses $state.prismExe).Count -ne 0) { Fail 'Prism was already running inside interactive launch' }
        if (@(Get-TargetJavaProcesses $state.gameRoot $state.instanceRoot).Count -ne 0) { Fail 'target Java was already running inside interactive launch' }

        $state.phase = 'launching'
        $state.launchStartedUtc = [DateTime]::UtcNow.ToString('o')
        Save-JsonAtomic $state $resolvedStateFile

        $args = @()
        if ($state.prismRoot) { $args += '-d'; $args += (Quote-ProcessArgument ([string]$state.prismRoot)) }
        $args += '-l'; $args += (Quote-ProcessArgument ([string]$state.instanceId))
        $prism = Start-Process -FilePath $state.prismExe -ArgumentList $args -PassThru
        $state.prismPid = $prism.Id
        Save-JsonAtomic $state $resolvedStateFile

        $java = $null
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        do {
            Start-Sleep -Milliseconds 250
            $candidates = @(Get-TargetJavaProcesses $state.gameRoot $state.instanceRoot | Where-Object { [int]$_.SessionId -eq [int]$state.expectedSessionId })
            if ($candidates.Count -gt 1) { Fail "multiple target java/javaw processes appeared: $($candidates.Count)" }
            if ($candidates.Count -eq 1) { $java = $candidates[0]; break }
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $java) { Fail 'no target java/javaw process appeared within 90 seconds' }

        $cmd = [string]$java.CommandLine
        if ([string]::IsNullOrWhiteSpace($cmd)) { Fail 'effective Java CommandLine is unavailable' }
        if ([int]$java.SessionId -ne [int]$state.expectedSessionId) { Fail 'Java is not in the expected interactive session' }
        if ($state.expectedJavaExe) {
            $actualExe = Resolve-FullPath ([string]$java.ExecutablePath)
            if (-not $actualExe.Equals([string]$state.expectedJavaExe, [StringComparison]::OrdinalIgnoreCase)) { Fail "effective Java executable mismatch: $actualExe" }
        }
        foreach ($required in @($state.requiredJvmArgs)) {
            if ($cmd.IndexOf([string]$required, [StringComparison]::Ordinal) -lt 0) { Fail "effective command line missing required JVM argument: $required" }
            if ([string]$required -match '^(-D[^=]+)=') {
                $key = $Matches[1] + '='
                if ([regex]::Matches($cmd, [regex]::Escape($key)).Count -ne 1) { Fail "effective command line has duplicate/missing property key: $key" }
            }
        }
        foreach ($forbidden in @($state.forbiddenJvmArgs)) {
            if ($cmd.IndexOf([string]$forbidden, [StringComparison]::Ordinal) -ge 0) { Fail "effective command line contains forbidden/stale JVM argument: $forbidden" }
        }
        $bootKeys = [regex]::Matches($cmd, '-Dboot_optim\.[A-Za-z0-9_.-]+=') | ForEach-Object { $_.Value }
        foreach ($group in @($bootKeys | Group-Object)) {
            if ($group.Count -gt 1) { Fail "duplicate BootOptim JVM property key in effective command line: $($group.Name)" }
        }

        $state.javaPid = [int]$java.ProcessId
        $state.javaCreationDate = ([DateTime]$java.CreationDate).ToString('o')
        $state.effectiveCommandLine = $cmd
        $state.effectiveJavaExe = [string]$java.ExecutablePath
        $state.phase = 'measuring'
        $state.valid = $true
        Save-JsonAtomic $state $resolvedStateFile

        $timedOut = $false
        try {
            Wait-Process -Id ([int]$state.javaPid) -Timeout ([int]$state.timeoutSeconds) -ErrorAction Stop
        } catch {
            $timedOut = $true
        }
        if ($timedOut) {
            $state.valid = $false
            $state.reason = 'java_timeout'
            $state.phase = 'timed_out'
            Save-JsonAtomic $state $resolvedStateFile
            Close-OwnedProcess -Pid ([int]$state.javaPid) -Kind 'Java' -Force
        } else {
            $state.phase = 'java_exited'
            $state.javaExitedUtc = [DateTime]::UtcNow.ToString('o')
            Save-JsonAtomic $state $resolvedStateFile
        }

        $ownedPrism = Get-Process -Id ([int]$state.prismPid -ErrorAction SilentlyContinue)
        if ($ownedPrism) {
            try { [void]$ownedPrism.CloseMainWindow(); [void]$ownedPrism.WaitForExit(15000) } catch {}
            $ownedPrism = Get-Process -Id ([int]$state.prismPid) -ErrorAction SilentlyContinue
            if ($ownedPrism) { Stop-Process -Id ([int]$state.prismPid) -Force }
        }
        $state.phase = if ($state.valid) { 'finished' } else { 'invalid' }
        $state.finishedUtc = [DateTime]::UtcNow.ToString('o')
        Save-JsonAtomic $state $resolvedStateFile
        break
    }

    'Status' {
        $state = Load-State $resolvedStateFile
        $taskState = $null
        try { $taskState = (Get-ScheduledTask -TaskName $state.taskName -ErrorAction Stop).State.ToString() } catch {}
        [pscustomobject]@{
            runId = $state.runId; phase = $state.phase; valid = $state.valid; reason = $state.reason
            taskState = $taskState; expectedSessionId = $state.expectedSessionId; prismPid = $state.prismPid; javaPid = $state.javaPid
            effectiveJavaExe = $state.effectiveJavaExe; effectiveCommandLine = $state.effectiveCommandLine
        } | ConvertTo-Json -Depth 5
        break
    }

    { $_ -in @('Postflight', 'Recover') } {
        $state = Load-State $resolvedStateFile
        if ($Action -eq 'Postflight' -and $state.phase -notin @('finished', 'invalid', 'timed_out', 'java_exited')) { Fail "Postflight refuses phase '$($state.phase)'" }
        Close-OwnedProcess -Pid ([int]$state.javaPid) -Kind 'Java' -Force:$ForceStopOwned
        Close-OwnedProcess -Pid ([int]$state.prismPid) -Kind 'Prism' -Force:$ForceStopOwned
        if (@(Get-PrismProcesses $state.prismExe).Count -ne 0) { Fail 'a Prism process is still running; close it before restoring instance.cfg' }
        if (@(Get-TargetJavaProcesses $state.gameRoot $state.instanceRoot).Count -ne 0) { Fail 'target java/javaw is still running; recovery will not mutate files' }

        try { Unregister-ScheduledTask -TaskName $state.taskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}

        $active = @(Get-BootOptimWrappers $state.modsDir)
        foreach ($jar in $active) { Remove-Item -LiteralPath $jar.path -Force }
        if (-not (Test-Path -LiteralPath $state.originalJarBackup -PathType Leaf)) { Fail 'original BootOptim JAR backup missing' }
        if ((Get-FileSha256 $state.originalJarBackup) -ne $state.originalJarSha256) { Fail 'original BootOptim JAR backup changed' }
        Copy-Item -LiteralPath $state.originalJarBackup -Destination $state.originalJarPath -Force

        if (-not (Test-Path -LiteralPath $state.instanceCfgBackup -PathType Leaf)) { Fail 'instance.cfg backup missing' }
        if ((Get-FileSha256 $state.instanceCfgBackup) -ne $state.originalInstanceCfgSha256) { Fail 'instance.cfg backup changed' }
        Copy-Item -LiteralPath $state.instanceCfgBackup -Destination $state.instanceCfg -Force

        $restored = @(Get-BootOptimWrappers $state.modsDir)
        if ($restored.Count -ne 1 -or $restored[0].sha256 -ne $state.originalJarSha256) { Fail 'restoration did not leave exactly the original BootOptim wrapper active' }
        $cfgHash = Get-FileSha256 $state.instanceCfg
        if ($cfgHash -ne $state.originalInstanceCfgSha256) { Fail 'instance.cfg restoration hash mismatch' }

        $state.phase = 'restored'
        $state.restoredUtc = [DateTime]::UtcNow.ToString('o')
        $state.restoredInstanceCfgSha256 = $cfgHash
        $state.restoredJarSha256 = $restored[0].sha256
        Save-JsonAtomic $state $resolvedStateFile
        [pscustomobject]@{ status = 'restored'; runId = $state.runId; valid = $state.valid; reason = $state.reason; instanceCfgSha256 = $cfgHash; bootOptimSha256 = $restored[0].sha256 } | ConvertTo-Json
        break
    }
}
