[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$JavaPid,
    [Parameter(Mandatory = $true)][string]$JavaCreationDate,
    [Parameter(Mandatory = $true)][string]$OutputFile,
    [ValidateRange(50, 1000)][int]$PollMilliseconds = 100
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Save([object]$value) {
    $dir = Split-Path -Parent $OutputFile
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $tmp = "$OutputFile.tmp-$PID"
    [IO.File]::WriteAllText($tmp, ($value | ConvertTo-Json -Depth 8), $Utf8)
    Move-Item -LiteralPath $tmp -Destination $OutputFile -Force
}

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

$java = Get-CimInstance Win32_Process -Filter "ProcessId=$JavaPid" -ErrorAction Stop
if ($java.Name -notin @('java.exe', 'javaw.exe')) { throw "PID $JavaPid is not Java" }
$actualCreation = ([DateTime]$java.CreationDate).ToString('o')
if ($actualCreation -ne $JavaCreationDate) { throw "PID $JavaPid creation identity mismatch" }

$handle = [Diagnostics.Process]::GetProcessById($JavaPid)
$startedUtc = [DateTime]::UtcNow
$children = @{}
$timeline = New-Object System.Collections.Generic.List[object]

try {
    while (-not $handle.WaitForExit($PollMilliseconds)) {
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$JavaPid" -ErrorAction SilentlyContinue)) {
            $id = [int]$child.ProcessId
            if ($children.ContainsKey($id)) { continue }
            $childHandle = $null
            try { $childHandle = [Diagnostics.Process]::GetProcessById($id) } catch {}
            $record = [pscustomobject]@{
                pid = $id
                name = [string]$child.Name
                creationDate = ([DateTime]$child.CreationDate).ToString('o')
                cefType = Cef-Type ([string]$child.CommandLine)
                firstSeenUtc = [DateTime]::UtcNow.ToString('o')
                handle = $childHandle
            }
            $children[$id] = $record
            $timeline.Add([pscustomobject]@{event='child_seen';pid=$id;name=$record.name;cefType=$record.cefType;utc=$record.firstSeenUtc})
        }
    }

    $exitCode = $handle.ExitCode
    $childResults = @()
    foreach ($entry in $children.Values) {
        $childExit = $null
        $childExitHex = $null
        $stillRunning = $false
        if ($entry.handle) {
            try {
                if ($entry.handle.WaitForExit(0)) {
                    $childExit = [int]$entry.handle.ExitCode
                    $childExitHex = Exit-Hex $childExit
                } else {
                    $stillRunning = $true
                }
            } catch {}
            try { $entry.handle.Dispose() } catch {}
        }
        $childResults += [pscustomobject]@{
            pid=$entry.pid;name=$entry.name;creationDate=$entry.creationDate;cefType=$entry.cefType;
            firstSeenUtc=$entry.firstSeenUtc;exitCode=$childExit;exitCodeHex=$childExitHex;stillRunning=$stillRunning
        }
    }

    $nativeEvents = @()
    try {
        $nativeEvents = @(Get-WinEvent -FilterHashtable @{LogName='Application';StartTime=$startedUtc;Id=1000,1001} -ErrorAction SilentlyContinue |
            Where-Object { $_.Message -match '(?i)(javaw?\.exe|jcef_helper\.exe|libcef\.dll|OpenAL\.dll|soft_oal\.dll)' } |
            Select-Object -First 20 |
            ForEach-Object { [pscustomobject]@{id=$_.Id;provider=$_.ProviderName;timeCreated=$_.TimeCreated.ToUniversalTime().ToString('o');message=$_.Message} })
    } catch {}

    Save ([pscustomobject]@{
        schema=1
        diagnosticOnly=$true
        observerEffect='100ms Win32_Process child polling; do not use this run for clean startup timing'
        javaPid=$JavaPid
        javaCreationDate=$JavaCreationDate
        startedUtc=$startedUtc.ToString('o')
        exitedUtc=[DateTime]::UtcNow.ToString('o')
        exitCode=$exitCode
        exitCodeHex=(Exit-Hex $exitCode)
        children=@($childResults)
        timeline=@($timeline)
        applicationEvents=@($nativeEvents)
    })
} finally {
    try { $handle.Dispose() } catch {}
}
