[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$TargetPid,
    [Parameter(Mandatory=$true)][string]$CreationDate,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]{1,64}$')][string]$RunId,
    [Parameter(Mandatory=$true)][ValidateSet('physical_laptop','hosted_windows_harness')][string]$Origin,
    [Parameter(Mandatory=$true)][ValidateSet('main_menu','main_menu_presented')][string]$Endpoint,
    [Parameter(Mandatory=$true)][ValidateSet('fresh_boot_no_pack_touch','warm_same_boot','unknown')][string]$ColdState,
    [ValidateRange(10000,60000)][int]$SampleIntervalMs=30000,
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
function Target-Process {
    # WMI performance classes can block for many seconds on this HDD laptop.  This
    # diagnostic must not become the source of the variation it is trying to find.
    try{$p=Get-Process -Id $TargetPid -ErrorAction Stop}catch{return $null}
    if($p.ProcessName-notin@('java','javaw')){return $null}
    try{
        $expected=([DateTime]$CreationDate).ToUniversalTime()
        if([Math]::Abs(($p.StartTime.ToUniversalTime()-$expected).TotalSeconds)-gt2){return $null}
    }catch{return $null}
    return $p
}
function Write-Row([object]$row) {
    [IO.File]::AppendAllText($OutputPath,(($row|ConvertTo-Json -Compress -Depth 8)+[Environment]::NewLine),$Utf8)
}

$parent=Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
if($parent){New-Item -ItemType Directory -Force -Path $parent|Out-Null}
if(Test-Path -LiteralPath $OutputPath){Remove-Item -LiteralPath $OutputPath -Force}

$target=Target-Process
if(-not$target){throw 'Target PID/CreationDate identity mismatch before sampling'}
$observer=Get-Process -Id $PID -ErrorAction Stop
$observerCpuStartMs=$observer.TotalProcessorTime.TotalMilliseconds
$started=[DateTime]::UtcNow
$deadline=$started.AddSeconds($TimeoutSeconds)

Write-Row ([ordered]@{
    kind='header';schema=1;measurementClass='diagnostic_not_benchmark';runId=$RunId
    origin=$Origin;endpoint=$Endpoint;coldState=$ColdState;pid=$TargetPid;creationDate=$CreationDate
    sampleIntervalMs=$SampleIntervalMs;startedUtc=$started.ToString('o')
    observerMode='low_intrusion_process_api';windowsBootUtc=$null
})

$count=0
$stopReason='timeout'
$maxSampleCostMs=0.0
while([DateTime]::UtcNow-lt$deadline){
    $sampleStarted=[DateTime]::UtcNow
    $sw=[Diagnostics.Stopwatch]::StartNew()
    $process=Target-Process
    if(-not$process){
        $stopReason=if($count-eq0){'pid_not_found_or_identity_mismatch'}else{'process_exited'}
        break
    }

    # No Minecraft/Prism/log/resource reads and no WMI/performance-counter queries
    # occur here. These process API fields are cumulative snapshots only.
    $sw.Stop()
    $cost=[double]$sw.Elapsed.TotalMilliseconds
    if($cost-gt$maxSampleCostMs){$maxSampleCostMs=$cost}

    Write-Row ([ordered]@{
        kind='sample';utc=$sampleStarted.ToString('o');pid=$TargetPid;sampleCostMs=[Math]::Round($cost,3)
        processCpuMs=[Math]::Round($process.TotalProcessorTime.TotalMilliseconds,3)
        processWorkingSetBytes=$process.WorkingSet64
        processPrivateBytes=$process.PrivateMemorySize64
        processThreadCount=$process.Threads.Count
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
