[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$TargetPid,
    [Parameter(Mandatory=$true)][string]$CreationDate,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]{1,64}$')][string]$RunId,
    [Parameter(Mandatory=$true)][ValidateSet('physical_laptop','hosted_windows_harness')][string]$Origin,
    [Parameter(Mandatory=$true)][ValidateSet('main_menu','main_menu_presented')][string]$Endpoint,
    [Parameter(Mandatory=$true)][ValidateSet('fresh_boot_no_pack_touch','warm_same_boot','unknown')][string]$ColdState,
    [ValidateRange(2000,30000)][int]$SampleIntervalMs=5000,
    [ValidateRange(30,3600)][int]$TimeoutSeconds=1200
)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)

function Field([object]$o,[string]$name) {
    if($null-eq$o){return $null}
    $p=$o.PSObject.Properties[$name]
    if($null-eq$p){return $null}
    return $p.Value
}
function Same-Creation([object]$process) {
    if(-not$process){return $false}
    try{return ([DateTime]$process.CreationDate).ToString('o')-eq$CreationDate}catch{return $false}
}
function Write-Row([object]$row) {
    [IO.File]::AppendAllText($OutputPath,(($row|ConvertTo-Json -Compress -Depth 8)+[Environment]::NewLine),$Utf8)
}

$parent=Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
if($parent){New-Item -ItemType Directory -Force -Path $parent|Out-Null}
if(Test-Path -LiteralPath $OutputPath){Remove-Item -LiteralPath $OutputPath -Force}

$target=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetPid" -ErrorAction SilentlyContinue
if(-not(Same-Creation $target)){throw 'Target PID/CreationDate identity mismatch before sampling'}
$os=Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue|Select-Object -First 1
$observer=Get-Process -Id $PID -ErrorAction Stop
$observerCpuStartMs=$observer.TotalProcessorTime.TotalMilliseconds
$started=[DateTime]::UtcNow
$deadline=$started.AddSeconds($TimeoutSeconds)

Write-Row ([ordered]@{
    kind='header';schema=1;measurementClass='diagnostic_not_benchmark';runId=$RunId
    origin=$Origin;endpoint=$Endpoint;coldState=$ColdState;pid=$TargetPid;creationDate=$CreationDate
    sampleIntervalMs=$SampleIntervalMs;startedUtc=$started.ToString('o')
    windowsBootUtc=if($os -and (Field $os 'LastBootUpTime')){([DateTime](Field $os 'LastBootUpTime')).ToUniversalTime().ToString('o')}else{$null}
})

$count=0
$stopReason='timeout'
$maxSampleCostMs=0.0
while([DateTime]::UtcNow-lt$deadline){
    $sampleStarted=[DateTime]::UtcNow
    $sw=[Diagnostics.Stopwatch]::StartNew()
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetPid" -ErrorAction SilentlyContinue
    if(-not(Same-Creation $process)){
        $stopReason=if($count-eq0){'pid_not_found_or_identity_mismatch'}else{'process_exited'}
        break
    }

    # No Minecraft/Prism/log/resource reads occur here. All observations are bounded performance-counter snapshots.
    $proc=Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -Filter "IDProcess=$TargetPid" -ErrorAction SilentlyContinue|Select-Object -First 1
    $mem=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory -ErrorAction SilentlyContinue|Select-Object -First 1
    $disk=Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'" -ErrorAction SilentlyContinue|Select-Object -First 1
    $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'" -ErrorAction SilentlyContinue|Select-Object -First 1
    $sys=Get-CimInstance Win32_PerfFormattedData_PerfOS_System -ErrorAction SilentlyContinue|Select-Object -First 1
    $sw.Stop()
    $cost=[double]$sw.Elapsed.TotalMilliseconds
    if($cost-gt$maxSampleCostMs){$maxSampleCostMs=$cost}

    Write-Row ([ordered]@{
        kind='sample';utc=$sampleStarted.ToString('o');pid=$TargetPid;sampleCostMs=[Math]::Round($cost,3)
        processPercentCpu=Field $proc 'PercentProcessorTime'
        processIoReadBytesPerSec=Field $proc 'IOReadBytesPersec'
        processIoWriteBytesPerSec=Field $proc 'IOWriteBytesPersec'
        processIoOtherBytesPerSec=Field $proc 'IOOtherBytesPersec'
        processPageFaultsPerSec=Field $proc 'PageFaultsPersec'
        processWorkingSetPrivateBytes=Field $proc 'WorkingSetPrivate'
        processPrivateBytes=Field $proc 'PrivateBytes'
        processThreadCount=Field $proc 'ThreadCount'
        memoryAvailableMBytes=Field $mem 'AvailableMBytes'
        memoryCacheBytes=Field $mem 'CacheBytes'
        memoryStandbyCacheCoreBytes=Field $mem 'StandbyCacheCoreBytes'
        memoryStandbyCacheNormalPriorityBytes=Field $mem 'StandbyCacheNormalPriorityBytes'
        memoryStandbyCacheReserveBytes=Field $mem 'StandbyCacheReserveBytes'
        memoryModifiedPageListBytes=Field $mem 'ModifiedPageListBytes'
        memoryPagesInputPerSec=Field $mem 'PagesInputPersec'
        memoryPageReadsPerSec=Field $mem 'PageReadsPersec'
        memoryTransitionFaultsPerSec=Field $mem 'TransitionFaultsPersec'
        diskReadBytesPerSec=Field $disk 'DiskReadBytesPersec'
        diskWriteBytesPerSec=Field $disk 'DiskWriteBytesPersec'
        diskReadsPerSec=Field $disk 'DiskReadsPersec'
        diskWritesPerSec=Field $disk 'DiskWritesPersec'
        diskCurrentQueueLength=Field $disk 'CurrentDiskQueueLength'
        diskAvgQueueLength=Field $disk 'AvgDiskQueueLength'
        diskAvgSecondsPerRead=Field $disk 'AvgDisksecPerRead'
        diskPercentTime=Field $disk 'PercentDiskTime'
        cpuPercent=Field $cpu 'PercentProcessorTime'
        processorQueueLength=Field $sys 'ProcessorQueueLength'
    })
    $count++
    $remaining=$SampleIntervalMs-[int]$sw.ElapsedMilliseconds
    if($remaining-gt0){Start-Sleep -Milliseconds $remaining}
}

$observer=Get-Process -Id $PID -ErrorAction SilentlyContinue
$observerCpuMs=if($observer){[Math]::Max(0.0,$observer.TotalProcessorTime.TotalMilliseconds-$observerCpuStartMs)}else{$null}
$finished=[DateTime]::UtcNow
Write-Row ([ordered]@{
    kind='footer';status='completed';samples=$count;stopReason=$stopReason
    finishedUtc=$finished.ToString('o');observerCpuMs=$observerCpuMs;maxSampleCostMs=[Math]::Round($maxSampleCostMs,3)
})

[ordered]@{status='completed';samples=$count;stopReason=$stopReason;observerCpuMs=$observerCpuMs;maxSampleCostMs=$maxSampleCostMs}|ConvertTo-Json -Compress
