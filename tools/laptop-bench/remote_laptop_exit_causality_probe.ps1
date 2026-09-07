[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][int]$JavaPid,
    [Parameter(Mandatory=$true)][string]$JavaCreationDate,
    [Parameter(Mandatory=$true)][string]$OutputFile,
    [int]$PrismPid = 0,
    [string]$PrismCreationDate,
    [string[]]$LogPath = @(),
    [string]$ControllerEventFile,
    [ValidateRange(50,1000)][int]$PollMilliseconds = 100,
    [ValidateRange(0,10000)][int]$PostExitEventGraceMilliseconds = 3000
)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)
$ErrorFile="$OutputFile.observer-error.json"

function Write-JsonAtomic([string]$path,[object]$value){
    $dir=Split-Path -Parent $path;if($dir){New-Item -ItemType Directory -Force -Path $dir|Out-Null}
    $tmp="$path.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
    try{[IO.File]::WriteAllText($tmp,($value|ConvertTo-Json -Depth 12),$Utf8);Move-Item -LiteralPath $tmp -Destination $path -Force}
    finally{if(Test-Path -LiteralPath $tmp){Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue}}
}
function Exit-Hex([int]$code){$bits=[BitConverter]::ToUInt32([BitConverter]::GetBytes($code),0);'0x{0:X8}' -f $bits}
function Same-Creation([object]$p,[string]$iso){if(-not$p -or -not$iso){return $false};try{return ([DateTime]$p.CreationDate).ToString('o')-eq$iso}catch{return $false}}
function Safe-Creation([object]$p){try{if($null-ne$p.CreationDate){return ([DateTime]$p.CreationDate).ToString('o')}}catch{};return $null}
function Cef-Type([string]$cmd){if([string]::IsNullOrWhiteSpace($cmd)){return $null};$m=[regex]::Match($cmd,'(?:^|\s)--type=([^\s\"]+)');if($m.Success){return $m.Groups[1].Value};return $null}
function Process-Kind([string]$name,[string]$cmd){if($name -ieq 'jcef_helper.exe'){return 'jcef_helper'};if($name -match '^(java|javaw)\.exe$'){return 'java'};$t=Cef-Type $cmd;if($t){return 'cef_'+$t};return 'other'}
function Ensure-WindowsApi{
    if('BootOptimWindowProbe' -as [type]){return}
    Add-Type -TypeDefinition @'
using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class BootOptimWindowProbe {
 [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr lp);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
 delegate bool EnumProc(IntPtr h, IntPtr lp);
 public static string[] VisibleForPid(int wanted){var r=new List<string>();EnumWindows((h,l)=>{uint p;GetWindowThreadProcessId(h,out p);if(p==wanted && IsWindowVisible(h)){var s=new StringBuilder(512);GetWindowText(h,s,s.Capacity);r.Add(s.ToString());}return true;},IntPtr.Zero);return r.ToArray();}
}
'@
}
function Window-State([int]$processId){try{@([BootOptimWindowProbe]::VisibleForPid($processId))}catch{@()}}
function Read-ControllerEvents{if(-not$ControllerEventFile -or -not(Test-Path -LiteralPath $ControllerEventFile -PathType Leaf)){return @()};$out=@();foreach($line in @(Get-Content -LiteralPath $ControllerEventFile -ErrorAction SilentlyContinue)){if([string]::IsNullOrWhiteSpace($line)){continue};try{$out+=($line|ConvertFrom-Json)}catch{$out+=[pscustomobject]@{event='controller_event_parse_error';raw=$line}}};@($out)}
function Read-LifecycleMarkers{
    $found=[ordered]@{minecraftStop=$false;jvmShutdownHook=$false;joinedGame=$false;levelLoadingScreen=$false;mcefInitialized=$false;mcefLast=$null;lines=@()}
    foreach($path in $LogPath){if(-not(Test-Path -LiteralPath $path -PathType Leaf)){continue};foreach($line in @(Get-Content -LiteralPath $path -ErrorAction SilentlyContinue)){
        $keep=$false
        if($line -match 'BOOTOPTIM_NATIVE_EXIT_PROBE stage=minecraft_stop'){$found.minecraftStop=$true;$keep=$true}
        if($line -match 'BOOTOPTIM_NATIVE_EXIT_PROBE stage=jvm_shutdown_hook'){$found.jvmShutdownHook=$true;$keep=$true}
        if($line -match 'BootOptimBench joined the game'){$found.joinedGame=$true;$keep=$true}
        if($line -match '(?i)level_loading_screen|Loading terrain'){$found.levelLoadingScreen=$true;$keep=$true}
        if($line -match 'BOOTOPTIM_MCEF_FIRST_CONSUMER|BOOTOPTIM_MCEF_NATIVE_PROBE|Chromium Embedded Framework initialized'){$found.mcefLast=$line;$keep=$true;if($line -match 'status=initialized|Chromium Embedded Framework initialized'){$found.mcefInitialized=$true}}
        if($keep -and $found.lines.Count-lt100){$found.lines+=([string]$line)}
    }}
    [pscustomobject]$found
}
function Error-Snapshot([System.Management.Automation.ErrorRecord]$e,[string]$stage){[ordered]@{schema=1;diagnosticOnly=$true;observerPid=$PID;javaPid=$JavaPid;stage=$stage;utc=[DateTime]::UtcNow.ToString('o');exceptionType=if($e.Exception){$e.Exception.GetType().FullName}else{$null};message=if($e.Exception){$e.Exception.Message}else{[string]$e};category=[string]$e.CategoryInfo;scriptStackTrace=[string]$e.ScriptStackTrace}}

$state=[ordered]@{schema=4;diagnosticOnly=$true;observerEffect=("{0}ms recursive Win32_Process/window polling plus checkpoints; not valid for clean timing"-f$PollMilliseconds);status='starting';stage='starting';observerPid=$PID;javaPid=$JavaPid;javaCreationDate=$JavaCreationDate;startedUtc=[DateTime]::UtcNow.ToString('o');attachedUtc=$null;exitedUtc=$null;exitCode=$null;exitCodeHex=$null;exitClassification='insufficient_evidence';prism=$null;javaWindows=@();processes=@();timeline=@();controllerEvents=@();lifecycle=$null;applicationEvents=@();warnings=@();error=$null}
$records=@{};$handles=@{};$timeline=New-Object System.Collections.Generic.List[object];$fatal=$null;$javaHandle=$null;$prismHandle=$null
function Timeline([object]$e){$timeline.Add($e);$state.timeline=$timeline.ToArray()}
function Snapshot-Records{$state.processes=@($records.Values|ForEach-Object{[pscustomobject]@{pid=$_.pid;parentPid=$_.parentPid;creationDate=$_.creationDate;name=$_.name;kind=$_.kind;cefType=$_.cefType;firstSeenUtc=$_.firstSeenUtc;exitedUtc=$_.exitedUtc;exitCode=$_.exitCode;exitCodeHex=$_.exitCodeHex;stillRunning=$_.stillRunning}})}
function Observe-Descendants{
    $parents=New-Object System.Collections.Queue;$parents.Enqueue([int]$JavaPid);foreach($r in $records.Values){if($r.stillRunning){$parents.Enqueue([int]$r.pid)}}
    $visited=@{};$seenChanged=$false
    while($parents.Count-gt0){$ppid=[int]$parents.Dequeue();if($visited.ContainsKey($ppid)){continue};$visited[$ppid]=$true
        foreach($c in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ppid" -ErrorAction SilentlyContinue)){
            $id=[int]$c.ProcessId;$created=Safe-Creation $c;$key="$id|$created";$cmd=[string]$c.CommandLine
            if(-not$records.ContainsKey($key)){
                $h=$null;try{$h=[Diagnostics.Process]::GetProcessById($id)}catch{};if($h){$handles[$key]=$h}
                $rec=[pscustomobject]@{pid=$id;parentPid=[int]$c.ParentProcessId;creationDate=$created;name=[string]$c.Name;kind=Process-Kind ([string]$c.Name) $cmd;cefType=Cef-Type $cmd;firstSeenUtc=[DateTime]::UtcNow.ToString('o');exitedUtc=$null;exitCode=$null;exitCodeHex=$null;stillRunning=$true}
                $records[$key]=$rec;$seenChanged=$true;Timeline([pscustomobject]@{event='process_seen';utc=$rec.firstSeenUtc;pid=$id;parentPid=$rec.parentPid;name=$rec.name;kind=$rec.kind;cefType=$rec.cefType})
            }
            $parents.Enqueue($id)
        }
    }
    if($seenChanged){Snapshot-Records;Write-JsonAtomic $OutputFile $state}
    foreach($key in @($records.Keys)){$r=$records[$key];if(-not$r.stillRunning){continue};$h=$null;if($handles.ContainsKey($key)){$h=$handles[$key]};if(-not$h){continue};try{if($h.WaitForExit(0)){$r.stillRunning=$false;$r.exitedUtc=[DateTime]::UtcNow.ToString('o');try{$r.exitCode=[int]$h.ExitCode;$r.exitCodeHex=Exit-Hex $r.exitCode}catch{};Timeline([pscustomobject]@{event='process_exit';utc=$r.exitedUtc;pid=$r.pid;name=$r.name;kind=$r.kind;exitCode=$r.exitCode;exitCodeHex=$r.exitCodeHex});Snapshot-Records;Write-JsonAtomic $OutputFile $state}}catch{}}
}

try{
    Ensure-WindowsApi
    Write-JsonAtomic $OutputFile $state
    $state.stage='identity_query';$j=Get-CimInstance Win32_Process -Filter "ProcessId=$JavaPid" -ErrorAction Stop
    if(-not$j -or $j.Name -notin @('java.exe','javaw.exe') -or -not(Same-Creation $j $JavaCreationDate)){throw 'Java PID/creation identity mismatch'}
    $javaHandle=[Diagnostics.Process]::GetProcessById($JavaPid)
    if($PrismPid-gt0){$p=Get-CimInstance Win32_Process -Filter "ProcessId=$PrismPid" -ErrorAction SilentlyContinue;if($p -and (Same-Creation $p $PrismCreationDate)){$prismHandle=[Diagnostics.Process]::GetProcessById($PrismPid);$state.prism=[ordered]@{pid=$PrismPid;creationDate=$PrismCreationDate;attached=$true;exitedUtc=$null;exitCode=$null;exitCodeHex=$null;windows=@(Window-State $PrismPid)}}else{$state.prism=[ordered]@{pid=$PrismPid;creationDate=$PrismCreationDate;attached=$false}}}
    $state.status='attached';$state.stage='observing';$state.attachedUtc=[DateTime]::UtcNow.ToString('o');$state.javaWindows=@(Window-State $JavaPid);Timeline([pscustomobject]@{event='java_attached';utc=$state.attachedUtc;pid=$JavaPid;windows=$state.javaWindows});Write-JsonAtomic $OutputFile $state
    while(-not$javaHandle.WaitForExit($PollMilliseconds)){
        Observe-Descendants
        $wins=@(Window-State $JavaPid);if(($wins -join "`n")-ne($state.javaWindows -join "`n")){$state.javaWindows=$wins;Timeline([pscustomobject]@{event='java_windows';utc=[DateTime]::UtcNow.ToString('o');windows=$wins});Write-JsonAtomic $OutputFile $state}
        if($prismHandle -and -not$state.prism.exitedUtc){try{if($prismHandle.WaitForExit(0)){$state.prism.exitedUtc=[DateTime]::UtcNow.ToString('o');try{$state.prism.exitCode=[int]$prismHandle.ExitCode;$state.prism.exitCodeHex=Exit-Hex $state.prism.exitCode}catch{};Timeline([pscustomobject]@{event='prism_exit';utc=$state.prism.exitedUtc;exitCode=$state.prism.exitCode;exitCodeHex=$state.prism.exitCodeHex});Write-JsonAtomic $OutputFile $state}else{$state.prism.windows=@(Window-State $PrismPid)}}catch{}}
    }
    Observe-Descendants
    $state.exitedUtc=[DateTime]::UtcNow.ToString('o');try{$state.exitCode=[int]$javaHandle.ExitCode;$state.exitCodeHex=Exit-Hex $state.exitCode}catch{$state.warnings+=('parent_exit_code: '+$_.Exception.Message)}
    $state.status='parent_exited';$state.stage='post_exit';$state.javaWindows=@(Window-State $JavaPid);Snapshot-Records;$state.controllerEvents=Read-ControllerEvents;$state.lifecycle=Read-LifecycleMarkers;Write-JsonAtomic $OutputFile $state
    if($PostExitEventGraceMilliseconds-gt0){Start-Sleep -Milliseconds $PostExitEventGraceMilliseconds}
    try{$state.applicationEvents=@(Get-WinEvent -FilterHashtable @{LogName='Application';StartTime=([DateTime]$state.startedUtc);Id=1000,1001} -ErrorAction Stop|Where-Object{$_.Message-match'(?i)(javaw?\.exe|jcef_helper\.exe|libcef\.dll|OpenAL\.dll|soft_oal\.dll)'}|Select-Object -First 30|ForEach-Object{[pscustomobject]@{id=$_.Id;provider=$_.ProviderName;timeCreated=$_.TimeCreated.ToUniversalTime().ToString('o');message=$_.Message}})}catch{$state.warnings+=('application_events: '+$_.Exception.Message)}
    $pidHex=('0x{0:x}' -f $JavaPid);$parentWer=@($state.applicationEvents|Where-Object{$_.message-match'(?i)javaw?\.exe' -and ($_.message-match([regex]::Escape([string]$JavaPid)) -or $_.message-match([regex]::Escape($pidHex)))})
    $helperBad=@($state.processes|Where-Object{$_.kind-like'jcef_helper*' -or $_.kind-like'cef_*'}|Where-Object{$null-ne$_.exitCode -and [int]$_.exitCode-ne0})
    if($parentWer.Count-gt0){$state.exitClassification='java_crash_wer'}
    elseif($state.exitCode-ne0 -and $null-ne$state.exitCode){$state.exitClassification='java_nonzero_exit'}
    elseif($state.lifecycle -and $state.lifecycle.jvmShutdownHook -and $state.lifecycle.minecraftStop){$state.exitClassification='minecraft_requested_clean_shutdown'}
    elseif($state.lifecycle -and $state.lifecycle.jvmShutdownHook){$state.exitClassification='jvm_clean_shutdown_origin_unknown'}
    elseif($state.exitCode-eq0 -and $helperBad.Count-gt0){$state.exitClassification='parent_zero_with_helper_failure_observed'}
    elseif($state.exitCode-eq0){$state.exitClassification='parent_zero_without_shutdown_provenance'}
    else{$state.exitClassification='insufficient_evidence'}
    $state.status='complete';$state.stage='complete';Write-JsonAtomic $OutputFile $state
}catch{
    $fatal=$_;$failedStage=[string]$state.stage;$snapshot=Error-Snapshot $fatal $failedStage
    $state.status='observer_error';$state.error=[pscustomobject]@{stage=$snapshot.stage;utc=$snapshot.utc;exceptionType=$snapshot.exceptionType;message=$snapshot.message;category=$snapshot.category;scriptStackTrace=$snapshot.scriptStackTrace}
    try{Snapshot-Records;$state.timeline=$timeline.ToArray();Write-JsonAtomic $OutputFile $state}catch{}
    try{Write-JsonAtomic $ErrorFile $snapshot}catch{}
}finally{foreach($h in @($handles.Values)){try{$h.Dispose()}catch{}};try{if($javaHandle){$javaHandle.Dispose()}}catch{};try{if($prismHandle){$prismHandle.Dispose()}}catch{}}
if($fatal){throw $fatal}
