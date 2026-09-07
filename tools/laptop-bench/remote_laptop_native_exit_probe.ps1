[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$JavaPid,
    [Parameter(Mandatory = $true)][string]$JavaCreationDate,
    [Parameter(Mandatory = $true)][string]$OutputFile,
    [ValidateRange(50, 1000)][int]$PollMilliseconds = 100,
    [ValidateRange(0, 10000)][int]$PostExitEventGraceMilliseconds = 3000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$ErrorFile = "$OutputFile.observer-error.json"
$FallbackErrorFile = Join-Path ([IO.Path]::GetTempPath()) ("bootoptim-native-exit-probe-{0}-{1}.observer-error.json" -f $JavaPid, $PID)

function Exit-Hex([int]$code) {
    $bits = [BitConverter]::ToUInt32([BitConverter]::GetBytes($code), 0)
    '0x{0:X8}' -f $bits
}

function Cef-Type([string]$commandLine) {
    if ([string]::IsNullOrWhiteSpace($commandLine)) { return $null }
    $match = [regex]::Match($commandLine, '(?:^|\s)--type=([^\s"]+)')
    if ($match.Success) { return $match.Groups[1].Value }
    return 'browser-or-unknown'
}

function Write-JsonAtomic([string]$path, [object]$value) {
    $dir = Split-Path -Parent $path
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $tmp = "$path.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
    try {
        [IO.File]::WriteAllText($tmp, ($value | ConvertTo-Json -Depth 10), $Utf8)
        Move-Item -LiteralPath $tmp -Destination $path -Force
    } finally {
        if (Test-Path -LiteralPath $tmp) {
            Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        }
    }
}

function Error-Snapshot([System.Management.Automation.ErrorRecord]$record, [string]$stage, [bool]$primarySaved) {
    [ordered]@{
        schema = 1
        diagnosticOnly = $true
        observerPid = $PID
        javaPid = $JavaPid
        javaCreationDate = $JavaCreationDate
        outputFile = $OutputFile
        stage = $stage
        utc = [DateTime]::UtcNow.ToString('o')
        primaryResultSaved = $primarySaved
        exceptionType = if ($record.Exception) { $record.Exception.GetType().FullName } else { $null }
        message = if ($record.Exception) { $record.Exception.Message } else { [string]$record }
        category = [string]$record.CategoryInfo
        scriptStackTrace = [string]$record.ScriptStackTrace
    }
}

function Write-ErrorSidecar([System.Management.Automation.ErrorRecord]$record, [string]$stage, [bool]$primarySaved) {
    $snapshot = Error-Snapshot $record $stage $primarySaved
    try {
        Write-JsonAtomic $ErrorFile $snapshot
        Write-Error ("BOOTOPTIM_NATIVE_EXIT_OBSERVER_ERROR sidecar={0} stage={1}: {2}" -f $ErrorFile, $stage, $snapshot.message) -ErrorAction Continue
        return
    } catch {
        $sidecarFailure = $_
    }

    try {
        $snapshot['preferredSidecarFailure'] = if ($sidecarFailure.Exception) { $sidecarFailure.Exception.Message } else { [string]$sidecarFailure }
        Write-JsonAtomic $FallbackErrorFile $snapshot
        Write-Error ("BOOTOPTIM_NATIVE_EXIT_OBSERVER_ERROR fallbackSidecar={0} stage={1}: {2}" -f $FallbackErrorFile, $stage, $snapshot.message) -ErrorAction Continue
    } catch {
        Write-Error ("BOOTOPTIM_NATIVE_EXIT_OBSERVER_ERROR sidecar_write_failed preferred={0} fallback={1} stage={2}" -f $ErrorFile, $FallbackErrorFile, $stage) -ErrorAction Continue
    }
}

$state = [ordered]@{
    schema = 3
    diagnosticOnly = $true
    observerEffect = ("{0}ms Win32_Process child polling plus checkpoint writes; do not use this run for clean startup timing" -f $PollMilliseconds)
    status = 'starting'
    stage = 'starting'
    observerPid = $PID
    javaPid = $JavaPid
    javaCreationDate = $JavaCreationDate
    actualJavaCreationDate = $null
    startedUtc = [DateTime]::UtcNow.ToString('o')
    attachedUtc = $null
    exitedUtc = $null
    postExitEventGraceMilliseconds = $PostExitEventGraceMilliseconds
    eventQueryCompletedUtc = $null
    exitCode = $null
    exitCodeHex = $null
    children = @()
    timeline = @()
    applicationEvents = @()
    warnings = @()
    error = $null
}

$handle = $null
$childHandles = @{}
$children = @{}
$timeline = New-Object System.Collections.Generic.List[object]
$fatalError = $null

try {
    foreach ($stale in @($ErrorFile, $FallbackErrorFile)) {
        try { Remove-Item -LiteralPath $stale -Force -ErrorAction SilentlyContinue } catch {}
    }

    # The first checkpoint happens before process lookup so even attach/identity failures leave a primary result.
    Write-JsonAtomic $OutputFile $state

    $state.stage = 'identity_query'
    $java = Get-CimInstance Win32_Process -Filter "ProcessId=$JavaPid" -ErrorAction Stop
    if (-not $java) { throw "PID $JavaPid does not exist" }
    if ($java.Name -notin @('java.exe', 'javaw.exe')) { throw "PID $JavaPid is not Java" }
    $actualCreation = ([DateTime]$java.CreationDate).ToString('o')
    $state.actualJavaCreationDate = $actualCreation
    if ($actualCreation -ne $JavaCreationDate) { throw "PID $JavaPid creation identity mismatch: expected $JavaCreationDate actual $actualCreation" }

    $state.stage = 'process_handle'
    $handle = [Diagnostics.Process]::GetProcessById($JavaPid)
    $state.status = 'attached'
    $state.stage = 'observing'
    $state.attachedUtc = [DateTime]::UtcNow.ToString('o')
    Write-JsonAtomic $OutputFile $state

    while (-not $handle.WaitForExit($PollMilliseconds)) {
        $observed = @()
        try {
            $observed = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$JavaPid" -ErrorAction Stop)
        } catch {
            $state.warnings += [pscustomobject]@{stage='child_poll';utc=[DateTime]::UtcNow.ToString('o');message=$_.Exception.Message}
            continue
        }

        foreach ($child in $observed) {
            $id = [int]$child.ProcessId
            if ($children.ContainsKey($id)) { continue }
            $childHandle = $null
            try { $childHandle = [Diagnostics.Process]::GetProcessById($id) } catch {}
            if ($childHandle) { $childHandles[$id] = $childHandle }
            $creation = $null
            if ($null -ne $child.CreationDate) {
                try { $creation = ([DateTime]$child.CreationDate).ToString('o') } catch {}
            }
            $record = [pscustomobject]@{
                pid = $id
                name = [string]$child.Name
                creationDate = $creation
                cefType = Cef-Type ([string]$child.CommandLine)
                firstSeenUtc = [DateTime]::UtcNow.ToString('o')
            }
            $children[$id] = $record
            $timeline.Add([pscustomobject]@{event='child_seen';pid=$id;name=$record.name;cefType=$record.cefType;utc=$record.firstSeenUtc})
        }
    }

    # Checkpoint immediately after the parent disappears, before child inspection or WER grace.
    $state.exitedUtc = [DateTime]::UtcNow.ToString('o')
    $state.status = 'parent_exited'
    $state.stage = 'parent_exited'
    try {
        $state.exitCode = [int]$handle.ExitCode
        $state.exitCodeHex = Exit-Hex ([int]$state.exitCode)
    } catch {
        $state.warnings += [pscustomobject]@{stage='parent_exit_code';utc=[DateTime]::UtcNow.ToString('o');message=$_.Exception.Message}
    }
    $state.children = @($children.Values)
    $state.timeline = $timeline.ToArray()
    Write-JsonAtomic $OutputFile $state

    $childResults = @()
    foreach ($entry in $children.Values) {
        $childExit = $null
        $childExitHex = $null
        $stillRunning = $false
        $childHandle = $null
        if ($childHandles.ContainsKey([int]$entry.pid)) { $childHandle = $childHandles[[int]$entry.pid] }
        if ($childHandle) {
            try {
                if ($childHandle.WaitForExit(0)) {
                    $childExit = [int]$childHandle.ExitCode
                    $childExitHex = Exit-Hex $childExit
                } else {
                    $stillRunning = $true
                }
            } catch {
                $state.warnings += [pscustomobject]@{stage='child_exit_code';pid=$entry.pid;utc=[DateTime]::UtcNow.ToString('o');message=$_.Exception.Message}
            }
        }
        $childResults += [pscustomobject]@{
            pid=$entry.pid;name=$entry.name;creationDate=$entry.creationDate;cefType=$entry.cefType;
            firstSeenUtc=$entry.firstSeenUtc;exitCode=$childExit;exitCodeHex=$childExitHex;stillRunning=$stillRunning
        }
    }
    $state.children = @($childResults)

    if ($PostExitEventGraceMilliseconds -gt 0) {
        Start-Sleep -Milliseconds $PostExitEventGraceMilliseconds
    }

    try {
        $state.applicationEvents = @(Get-WinEvent -FilterHashtable @{LogName='Application';StartTime=([DateTime]$state.startedUtc);Id=1000,1001} -ErrorAction Stop |
            Where-Object { $_.Message -match '(?i)(javaw?\.exe|jcef_helper\.exe|libcef\.dll|OpenAL\.dll|soft_oal\.dll)' } |
            Select-Object -First 20 |
            ForEach-Object { [pscustomobject]@{id=$_.Id;provider=$_.ProviderName;timeCreated=$_.TimeCreated.ToUniversalTime().ToString('o');message=$_.Message} })
    } catch {
        $state.warnings += [pscustomobject]@{stage='application_event_query';utc=[DateTime]::UtcNow.ToString('o');message=$_.Exception.Message}
    }

    $state.eventQueryCompletedUtc = [DateTime]::UtcNow.ToString('o')
    $state.status = 'complete'
    $state.stage = 'complete'
    Write-JsonAtomic $OutputFile $state
} catch {
    $fatalError = $_
    $failedStage = [string]$state.stage
    $state.status = 'observer_error'
    $state.error = [pscustomobject]@{
        stage = $failedStage
        utc = [DateTime]::UtcNow.ToString('o')
        exceptionType = if ($_.Exception) { $_.Exception.GetType().FullName } else { $null }
        message = if ($_.Exception) { $_.Exception.Message } else { [string]$_ }
    }
    $state.children = @($children.Values)
    $state.timeline = $timeline.ToArray()
    $primarySaved = $false
    try {
        Write-JsonAtomic $OutputFile $state
        $primarySaved = $true
    } catch {
        # The dedicated sidecar below carries both the original observer error and whether primary save failed.
    }
    Write-ErrorSidecar $fatalError $failedStage $primarySaved
} finally {
    foreach ($childHandle in @($childHandles.Values)) {
        try { $childHandle.Dispose() } catch {}
    }
    try { if ($handle) { $handle.Dispose() } } catch {}
}

if ($fatalError) {
    throw $fatalError
}
