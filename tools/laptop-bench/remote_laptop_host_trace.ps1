[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [int]$TargetPid,
    [Parameter(Mandatory = $true)]
    [string]$CreationDate,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [ValidateRange(500, 10000)]
    [int]$SampleIntervalMs = 2000,
    [ValidateRange(30, 3600)]
    [int]$TimeoutSeconds = 1200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Same-Creation([object]$process) {
    if (-not $process) { return $false }
    try {
        return ([DateTime]$process.CreationDate).ToString('o') -eq $CreationDate
    } catch {
        return $false
    }
}

function Add-Sample([object]$sample) {
    [IO.File]::AppendAllText($OutputPath, (($sample | ConvertTo-Json -Compress -Depth 8) + [Environment]::NewLine), $utf8)
}

$parent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
if (Test-Path -LiteralPath $OutputPath) { Remove-Item -LiteralPath $OutputPath -Force }

$started = [DateTime]::UtcNow
$deadline = $started.AddSeconds($TimeoutSeconds)
$count = 0
$stopReason = 'timeout'

while ([DateTime]::UtcNow -lt $deadline) {
    $now = [DateTime]::UtcNow
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$TargetPid" -ErrorAction SilentlyContinue
    if (-not (Same-Creation $process)) {
        $stopReason = if ($count -eq 0) { 'pid_not_found_or_identity_mismatch' } else { 'process_exited' }
        break
    }

    # These are read-only Windows performance snapshots. They deliberately do
    # not read Minecraft logs or query Prism, so they can run alongside a
    # transaction without changing its lifecycle or measurement endpoint.
    # Use WMI filters instead of enumerating every process/disk/core.  The
    # difference matters on the target's 2C/4T HDD: a diagnostic must not turn
    # each one-second sample into a multi-second all-process scan.
    $procPerf = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -Filter "IDProcess=$TargetPid" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $memory = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $disk = Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $cpu = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'" -ErrorAction SilentlyContinue |
        Select-Object -First 1

    $sample = [ordered]@{
        utc = $now.ToString('o')
        pid = $TargetPid
        creationDate = $CreationDate
        processPercentCpu = if ($procPerf) { [double]$procPerf.PercentProcessorTime } else { $null }
        processIoBytesPerSec = if ($procPerf) { [double]$procPerf.IODataBytesPersec } else { $null }
        processPageFaultsPerSec = if ($procPerf) { [double]$procPerf.PageFaultsPersec } else { $null }
        processWorkingSetPrivateBytes = if ($procPerf) { [int64]$procPerf.WorkingSetPrivate } else { $null }
        memoryPagesInputPerSec = if ($memory) { [double]$memory.PagesInputPersec } else { $null }
        memoryPageReadsPerSec = if ($memory) { [double]$memory.PageReadsPersec } else { $null }
        memoryPagesPerSec = if ($memory) { [double]$memory.PagesPersec } else { $null }
        diskReadBytesPerSec = if ($disk) { [double]$disk.DiskReadBytesPersec } else { $null }
        diskReadsPerSec = if ($disk) { [double]$disk.DiskReadsPersec } else { $null }
        cpuPercent = if ($cpu) { [double]$cpu.PercentProcessorTime } else { $null }
    }
    Add-Sample $sample
    $count++
    Start-Sleep -Milliseconds $SampleIntervalMs
}

[ordered]@{
    status = 'completed'
    pid = $TargetPid
    creationDate = $CreationDate
    output = [IO.Path]::GetFullPath($OutputPath)
    samples = $count
    sampleIntervalMs = $SampleIntervalMs
    stopReason = $stopReason
    startedUtc = $started.ToString('o')
    finishedUtc = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json -Compress
