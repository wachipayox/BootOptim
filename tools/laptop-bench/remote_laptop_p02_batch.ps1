[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,48}$')][string]$BatchId,
    [ValidateRange(2,20)][int]$Runs=10,
    [ValidateRange(10,600)][int]$InterRunSeconds=60,
    [string]$BatchRoot=(Join-Path $env:LOCALAPPDATA 'BootOptimBench\batches'),
    [string]$TransactionScript=(Join-Path $PSScriptRoot 'remote_laptop_transaction.ps1'),
    [Parameter(Mandatory=$true)][string]$InstanceRoot,
    [Parameter(Mandatory=$true)][string]$PrismExe,
    [Parameter(Mandatory=$true)][string]$PrismRoot,
    [Parameter(Mandatory=$true)][string]$InstanceId,
    [Parameter(Mandatory=$true)][string]$InteractiveUser,
    [Parameter(Mandatory=$true)][string]$ArtifactJar,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string]$ExpectedJarSha256,
    [Parameter(Mandatory=$true)][string]$ExpectedJavaExe,
    [Parameter(Mandatory=$true)][string]$JvmArgs,
    [Parameter(Mandatory=$true)][string[]]$RequiredJvmArg,
    [int]$TimeoutSeconds=900
)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)

function Save([object]$value,[string]$path){
    $tmp="$path.tmp-$PID"
    [IO.File]::WriteAllText($tmp,($value|ConvertTo-Json -Depth 12),$Utf8)
    Move-Item -LiteralPath $tmp -Destination $path -Force
}
function Load([string]$path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
function Invoke-Tx([hashtable]$args){& $TransactionScript @args}

if(-not(Test-Path -LiteralPath $TransactionScript -PathType Leaf)){throw "Missing transaction script: $TransactionScript"}
$root=Join-Path ([IO.Path]::GetFullPath($BatchRoot)) $BatchId
if(Test-Path -LiteralPath $root){throw "Batch already exists: $root"}
$runsRoot=Join-Path $root 'runs'
New-Item -ItemType Directory -Force -Path $runsRoot|Out-Null
$statePath=Join-Path $root 'batch-state.json'
$batch=[ordered]@{schema=1;batchId=$BatchId;phase='running';runs=$Runs;interRunSeconds=$InterRunSeconds;createdUtc=[DateTime]::UtcNow.ToString('o');finishedUtc=$null;results=@()}
Save $batch $statePath

for($index=1;$index-le$Runs;$index++){
    $runId=('{0}-{1:d2}' -f $BatchId,$index)
    $common=@{RunId=$runId;InstanceRoot=$InstanceRoot;PrismExe=$PrismExe;PrismRoot=$PrismRoot;InstanceId=$InstanceId;InteractiveUser=$InteractiveUser;ArtifactJar=$ArtifactJar;ExpectedJarSha256=$ExpectedJarSha256;ExpectedJavaExe=$ExpectedJavaExe;JvmArgs=$JvmArgs;RequiredJvmArg=$RequiredJvmArg;TimeoutSeconds=$TimeoutSeconds;StateRoot=$runsRoot;P02HostProbe=$true;P02ColdState='warm_same_boot'}
    $entry=[ordered]@{index=$index;runId=$runId;startedUtc=[DateTime]::UtcNow.ToString('o');endedUtc=$null;phase=$null;valid=$false;reason=$null;postflight=$null}
    $batch.results+= [pscustomobject]$entry; Save $batch $statePath
    try{
        Invoke-Tx ($common + @{Action='Preflight'})|Out-Null
        Invoke-Tx ($common + @{Action='Stage'})|Out-Null
        Invoke-Tx ($common + @{Action='Run'})|Out-Null
        $runState=Join-Path (Join-Path $runsRoot $runId) 'state.json'
        $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds+120)
        do{
            Start-Sleep -Seconds 5
            $state=Load $runState
        }while($state.phase -notin @('finished','invalid') -and [DateTime]::UtcNow-lt$deadline)
        if($state.phase -notin @('finished','invalid')){throw 'batch wait exceeded transaction timeout'}
        $entry.phase=$state.phase;$entry.valid=[bool]$state.valid;$entry.reason=$state.reason
        if($state.phase-eq'finished'){
            Invoke-Tx @{Action='Postflight';RunId=$runId;StateRoot=$runsRoot}|Out-Null
            $entry.postflight='restored'
        }else{
            Invoke-Tx @{Action='Recover';RunId=$runId;StateRoot=$runsRoot;ForceStopOwned=$true}|Out-Null
            $entry.postflight='recovered'
        }
    }catch{
        $entry.phase='batch_error';$entry.reason=$_.Exception.Message
        try{Invoke-Tx @{Action='Recover';RunId=$runId;StateRoot=$runsRoot;ForceStopOwned=$true}|Out-Null;$entry.postflight='recovered'}catch{$entry.postflight='recovery_failed'}
    }
    $entry.endedUtc=[DateTime]::UtcNow.ToString('o'); Save $batch $statePath
    if($index-lt$Runs){Start-Sleep -Seconds $InterRunSeconds}
}
$batch.phase='finished';$batch.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $batch $statePath
try{msg.exe $env:USERNAME "BootOptim: lote $BatchId terminado. Revisa batch-state.json."|Out-Null}catch{}
