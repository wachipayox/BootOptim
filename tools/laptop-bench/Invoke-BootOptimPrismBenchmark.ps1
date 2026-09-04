<#
.SYNOPSIS
Runs one isolated BootOptim Prism benchmark from the logged-in Windows session.

.DESCRIPTION
This script is intended to be the action of an InteractiveToken scheduled task.
It launches the isolated instance offline, discovers only the newly created
Java process in that same session, waits for that PID to exit, and then records
the BootOptim title-screen summary. It deliberately does not poll game logs
while Java is running.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,80}$')]
    [string]$RunId,

    [ValidateNotNullOrEmpty()]
    [string]$BenchRoot = 'C:\BootOptimBench',

    [ValidateNotNullOrEmpty()]
    [string]$InstanceId = 'BootOptimBench',

    [ValidateRange(60, 1800)]
    [int]$TimeoutSeconds = 900,

    # Prism resolves the instance's component metadata asynchronously on its
    # first portable start. This is outside the measured Java startup and must
    # complete before a CLI launch request is sent.
    [ValidateRange(0, 300)]
    [int]$PrismReadyDelaySeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$prismRoot = Join-Path $BenchRoot 'prism'
$launcherPath = Join-Path $prismRoot 'prismlauncher.exe'
$gameDirectory = Join-Path (Join-Path (Join-Path $prismRoot 'instances') $InstanceId) '.minecraft'
$instanceConfigPath = Join-Path (Join-Path (Join-Path $prismRoot 'instances') $InstanceId) 'instance.cfg'
$startupLogPath = Join-Path (Join-Path $gameDirectory 'logs') 'bootoptim-startup.log'
$resultsDirectory = Join-Path $BenchRoot 'results'
$statePath = Join-Path $resultsDirectory "$RunId.json"

if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
    throw "Prism launcher is missing: '$launcherPath'."
}
if (-not (Test-Path -LiteralPath $gameDirectory -PathType Container)) {
    throw "Benchmark game directory is missing: '$gameDirectory'."
}
if (-not (Test-Path -LiteralPath $instanceConfigPath -PathType Leaf)) {
    throw "Prism instance metadata is missing: '$instanceConfigPath'."
}
$instanceUuidLine = Get-Content -LiteralPath $instanceConfigPath | Where-Object { $_ -match '^uuid=([0-9a-fA-F-]+)$' } | Select-Object -First 1
if (-not $instanceUuidLine -or $instanceUuidLine -notmatch '^uuid=([0-9a-fA-F-]+)$') {
    throw "Prism instance metadata has no valid UUID: '$instanceConfigPath'."
}
$instanceUuid = $Matches[1]
New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
if (Test-Path -LiteralPath $statePath) {
    throw "Run result already exists: '$statePath'. Choose a unique RunId."
}

$sessionId = (Get-Process -Id $PID).SessionId
$priorJavaProcessIds = @(
    Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $sessionId } |
        Select-Object -ExpandProperty Id
)
if ($priorJavaProcessIds.Count -gt 0) {
    throw "Refusing to start while Java is already running in benchmark session ${sessionId}: $($priorJavaProcessIds -join ', ')."
}

$state = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    status = 'starting'
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $sessionId
    instanceId = $InstanceId
    prismInstanceUuid = $instanceUuid
    offline = $true
    timeoutSeconds = $TimeoutSeconds
    prismReadyDelaySeconds = $PrismReadyDelaySeconds
}
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8

try {
    $launchStart = Get-Date
    $prismAlreadyRunning = @(Get-CimInstance Win32_Process -Filter "Name = 'prismlauncher.exe'" |
        Where-Object { $_.ExecutablePath -eq $launcherPath -and $_.SessionId -eq $sessionId })
    if ($prismAlreadyRunning.Count -eq 0) {
        Start-Process -FilePath $launcherPath -ArgumentList @('-d', $prismRoot) | Out-Null
        $prismDeadline = (Get-Date).AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 250
            $prismAlreadyRunning = @(Get-CimInstance Win32_Process -Filter "Name = 'prismlauncher.exe'" |
                Where-Object { $_.ExecutablePath -eq $launcherPath -and $_.SessionId -eq $sessionId })
        } while ($prismAlreadyRunning.Count -eq 0 -and (Get-Date) -lt $prismDeadline)
        if ($prismAlreadyRunning.Count -eq 0) {
            throw 'Prism did not start in the interactive benchmark session.'
        }
        # The laptop needs tens of seconds to resolve the instance profile on a
        # cold portable Prism start. A launch request received earlier is not
        # queued by Prism. This pre-Java delay is deliberately outside the
        # benchmark timing and avoids polling any game log.
        Start-Sleep -Seconds $PrismReadyDelaySeconds
    }
    Start-Process -FilePath $launcherPath -ArgumentList @(
        '-d', $prismRoot, '--launch', $instanceUuid, '--offline', 'BootOptimBench'
    ) | Out-Null

    $deadline = $launchStart.AddSeconds($TimeoutSeconds)
    $java = $null
    while ((Get-Date) -lt $deadline) {
        $java = Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
            Where-Object { $_.SessionId -eq $sessionId -and $_.Id -notin $priorJavaProcessIds } |
            Select-Object -First 1
        if ($java) {
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $java) {
        throw "No new Java process appeared within $TimeoutSeconds seconds."
    }

    $state.status = 'running'
    $state.javaProcessId = $java.Id
    $state.javaStartTime = $java.StartTime.ToUniversalTime().ToString('o')
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8

    $remainingMilliseconds = [Math]::Max(1, [int](($deadline - (Get-Date)).TotalMilliseconds))
    $java.WaitForExit($remainingMilliseconds)
    if (-not $java.HasExited) {
        throw "Java process $($java.Id) exceeded the $TimeoutSeconds-second benchmark timeout."
    }

    if (-not (Test-Path -LiteralPath $startupLogPath -PathType Leaf)) {
        throw "BootOptim startup report was not created: '$startupLogPath'."
    }
    $summaryLines = @(
        Get-Content -LiteralPath $startupLogPath |
            Where-Object { $_ -like 'SUMMARY *' } |
            ForEach-Object { [string]$_ }
    )
    $summary = $summaryLines | Select-Object -Last 1
    if (-not $summary) {
        throw 'BootOptim startup report has no terminal SUMMARY line.'
    }

    $state.status = if ($summary -like '*status=main_menu_reached') { 'completed' } else { 'completed_without_main_menu' }
    $state.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $state.bootOptimSummary = $summary
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
}
catch {
    $state.status = 'failed'
    $state.failedAt = (Get-Date).ToUniversalTime().ToString('o')
    $state.error = $_.Exception.Message
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    throw
}
