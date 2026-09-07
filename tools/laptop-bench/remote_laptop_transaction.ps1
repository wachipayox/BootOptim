[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight','Stage','Run','Status','Postflight','Recover')]
    [string]$Action,
    [string]$RunId,
    [string]$InstanceRoot,
    [string]$PrismExe,
    [string]$PrismRoot,
    [string]$InstanceId,
    [string]$InteractiveUser,
    [string]$ArtifactJar,
    [string]$ExpectedJarSha256,
    [string]$JvmArgs,
    [string[]]$RequiredJvmArg = @(),
    [string[]]$ForbiddenJvmArg = @(),
    [string]$ExpectedJavaExe,
    [int]$TimeoutSeconds = 900,
    [string]$StateRoot = (Join-Path $env:LOCALAPPDATA 'BootOptimBench'),
    [switch]$ForceStopOwned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$WrapperMarker = 'dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class'
$InnerModMarker = 'dev/wachipayox/bootoptim/BootOptim.class'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Fail([string]$m) { throw "BOOTOPTIM_REMOTE_INVALID: $m" }
function Full([string]$p) { if ([string]::IsNullOrWhiteSpace($p)) { return $null }; [IO.Path]::GetFullPath($p) }
function Sha([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToUpperInvariant() }
function Save([object]$o,[string]$p) { $d=Split-Path -Parent $p; New-Item -ItemType Directory -Force -Path $d|Out-Null; $t="$p.tmp-$PID"; [IO.File]::WriteAllText($t,($o|ConvertTo-Json -Depth 10),$Utf8); Move-Item -LiteralPath $t -Destination $p -Force }
function Load([string]$p) { if(-not(Test-Path -LiteralPath $p -PathType Leaf)){Fail "missing state $p"}; Get-Content -LiteralPath $p -Raw|ConvertFrom-Json }

function BootOptim-Kind([string]$p) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z=[IO.Compression.ZipFile]::OpenRead($p)
        try {
            $wrapper=$false; $inner=$false
            foreach($e in $z.Entries) {
                if($e.FullName -eq $WrapperMarker){$wrapper=$true}
                if($e.FullName -eq $InnerModMarker){$inner=$true}
            }
            if($wrapper -and $inner){return 'ambiguous'}
            if($wrapper){return 'wrapper'}
            if($inner){return 'inner'}
            return $null
        } finally {$z.Dispose()}
    } catch { return $null }
}
function BootOptim-Artifacts([string]$mods) {
    if(-not(Test-Path -LiteralPath $mods -PathType Container)){return @()}
    @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar'|ForEach-Object{
        $kind=BootOptim-Kind $_.FullName
        if($kind){[pscustomobject]@{path=$_.FullName;name=$_.Name;kind=$kind;sha256=Sha $_.FullName}}
    })
}
function One-Wrapper([string]$mods,[string]$expectedSha=$null) {
    $a=@(BootOptim-Artifacts $mods)
    if($a.Count-ne1){Fail "expected exactly one BootOptim JAR total (wrapper or inner), found $($a.Count)"}
    if($a[0].kind-ne'wrapper'){Fail "the only BootOptim JAR is '$($a[0].kind)', not the packaged early-service wrapper"}
    if($expectedSha -and $a[0].sha256-ne$expectedSha){Fail "BootOptim wrapper SHA-256 mismatch: $($a[0].sha256)"}
    $a[0]
}
function Prism-Procs([string]$exe) {
    $names=@('prismlauncher.exe','PrismLauncher.exe');if($exe){$names += [IO.Path]::GetFileName($exe)};$names=@($names|Select-Object -Unique)
    @(Get-CimInstance Win32_Process|Where-Object{$names -contains $_.Name})
}
function Target-Java([string]$game,[string]$root) {
    $all=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    @($all|Where-Object{$c=[string]$_.CommandLine;$c -and (($c.IndexOf($game,[StringComparison]::OrdinalIgnoreCase)-ge0)-or($c.IndexOf($root,[StringComparison]::OrdinalIgnoreCase)-ge0))})
}

function Ensure-Wts {
    if('BootOptimWts' -as [type]){return}
    Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class BootOptimWts {
  enum I { InitialProgram,ApplicationName,WorkingDirectory,OEMId,SessionId,UserName,WinStationName,DomainName,ConnectState }
  [DllImport("Wtsapi32.dll",SetLastError=true)] static extern bool WTSQuerySessionInformation(IntPtr s,int id,I i,out IntPtr p,out int n);
  [DllImport("Wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr p);
  static string S(int id,I i){IntPtr p;int n;if(!WTSQuerySessionInformation(IntPtr.Zero,id,i,out p,out n))return null;try{return Marshal.PtrToStringUni(p);}finally{WTSFreeMemory(p);}}
  public static string User(int id){return S(id,I.UserName);} public static string Domain(int id){return S(id,I.DomainName);}
  public static int State(int id){IntPtr p;int n;if(!WTSQuerySessionInformation(IntPtr.Zero,id,I.ConnectState,out p,out n))return -1;try{return Marshal.ReadInt32(p);}finally{WTSFreeMemory(p);}}
}
'@
}
function Active-Session([string]$spec) {
    Ensure-Wts;$dom=$null;$usr=$spec;if($spec.Contains('\')){$x=$spec.Split('\',2);$dom=$x[0];$usr=$x[1]}
    $out=@();foreach($sid in @(Get-Process explorer -ErrorAction SilentlyContinue|Select-Object -ExpandProperty SessionId -Unique)){
        if([BootOptimWts]::State([int]$sid)-ne0){continue};$u=[BootOptimWts]::User([int]$sid);$d=[BootOptimWts]::Domain([int]$sid)
        if($u -and $u.Equals($usr,[StringComparison]::OrdinalIgnoreCase) -and (-not$dom -or ($d -and $d.Equals($dom,[StringComparison]::OrdinalIgnoreCase)))){$out += [pscustomobject]@{sessionId=[int]$sid;user=$u;domain=$d}}
    }
    if($out.Count-ne1){Fail "expected one WTSActive Explorer session for '$spec', found $($out.Count)"};$out[0]
}
function Canonical-SessionUser([object]$session) { if($session.domain){return ([string]$session.domain+'\'+[string]$session.user)}; [string]$session.user }

function Set-CfgKey([string]$text,[string]$key,[string]$value) {
    $nl=if($text.Contains("`r`n")){"`r`n"}else{"`n"};$pat='(?m)^'+[regex]::Escape($key)+'=.*$';$rx=New-Object Text.RegularExpressions.Regex($pat);$m=$rx.Matches($text)
    if($m.Count-gt1){Fail "instance.cfg contains duplicate '$key' keys"}
    $rep=$key+'='+$value
    if($m.Count-eq1){return $rx.Replace($text,[Text.RegularExpressions.MatchEvaluator]{param($match) $rep},1)}
    if($text.Length-gt0 -and -not$text.EndsWith("`n")){$text+=$nl};$text+$rep+$nl
}
function Qs([string]$v) {
    if($v.Contains("`r")-or$v.Contains("`n")-or$v.Contains([char]0)){Fail 'JvmArgs may not contain newline or NUL'}
    $v.Replace('\','\\').Replace('"','\"')
}

function Config {
    foreach($n in @('RunId','InstanceRoot','PrismExe','InstanceId','InteractiveUser','ArtifactJar','ExpectedJarSha256','JvmArgs')){if([string]::IsNullOrWhiteSpace((Get-Variable -Name $n -ValueOnly))){Fail "$n is required"}}
    if($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'){Fail 'RunId must be 1-64 path-safe characters: letters, digits, dot, underscore, hyphen'}
    if($ExpectedJarSha256 -notmatch '^[0-9A-Fa-f]{64}$'){Fail 'ExpectedJarSha256 must be 64 hexadecimal characters'}
    if($TimeoutSeconds-lt600){Fail 'TimeoutSeconds < 600 is unsafe for the observed 350-380 s startup regime'}
    $r=Full $InstanceRoot;$g=Join-Path $r '.minecraft';if(-not(Test-Path -LiteralPath $g -PathType Container)){Fail "missing $g"}
    [pscustomobject]@{runId=$RunId;instanceRoot=$r;gameRoot=$g;modsDir=Join-Path $g 'mods';instanceCfg=Join-Path $r 'instance.cfg';prismExe=Full $PrismExe;prismRoot=if($PrismRoot){Full $PrismRoot}else{$null};instanceId=$InstanceId;interactiveUser=$InteractiveUser;artifactJar=Full $ArtifactJar;candidateSha=$ExpectedJarSha256.ToUpperInvariant();jvmArgs=$JvmArgs;required=@($RequiredJvmArg);forbidden=@($ForbiddenJvmArg);expectedJava=if($ExpectedJavaExe){Full $ExpectedJavaExe}else{$null};timeout=$TimeoutSeconds}
}
function Pre([object]$c) {
    foreach($p in @($c.instanceRoot,$c.gameRoot,$c.modsDir)){if(-not(Test-Path -LiteralPath $p -PathType Container)){Fail "missing directory $p"}}
    foreach($p in @($c.instanceCfg,$c.prismExe,$c.artifactJar)){if(-not(Test-Path -LiteralPath $p -PathType Leaf)){Fail "missing file $p"}}
    $modsPrefix=(Full $c.modsDir).TrimEnd('\')+'\';if((Full $c.artifactJar).StartsWith($modsPrefix,[StringComparison]::OrdinalIgnoreCase)){Fail 'ArtifactJar must be outside the live mods directory'}
    if((BootOptim-Kind $c.artifactJar)-ne'wrapper'){Fail 'candidate is not the packaged BootOptim early-service wrapper'};if((Sha $c.artifactJar)-ne$c.candidateSha){Fail 'candidate SHA-256 mismatch'}
    $j=One-Wrapper $c.modsDir
    if(@(Prism-Procs $c.prismExe).Count){Fail 'Prism must be fully stopped before touching instance.cfg'};if(@(Target-Java $c.gameRoot $c.instanceRoot).Count){Fail 'target java/javaw already exists'}
    $s=Active-Session $c.interactiveUser;[pscustomobject]@{jar=$j;session=$s}
}
function Same-Creation([object]$p,[string]$iso) { if(-not$iso){return $false};(([DateTime]$p.CreationDate).ToString('o') -eq $iso) }
function Stop-Owned([int]$id,[string]$created,[string]$kind,[object]$st,[switch]$force) {
    if($id-le0){return};$p=Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue;if(-not$p){return};if(-not(Same-Creation $p $created)){return}
    if([int]$p.SessionId-ne[int]$st.expectedSessionId){Fail "$kind PID $id creation matched but session identity changed"}
    if($kind-eq'Java' -and $p.Name -notin @('java.exe','javaw.exe')){Fail "recorded Java PID $id no longer identifies Java"}
    if($kind-eq'Prism' -and $p.Name -ne[IO.Path]::GetFileName([string]$st.prismExe) -and $p.Name -notin @('prismlauncher.exe','PrismLauncher.exe')){Fail "recorded Prism PID $id no longer identifies Prism"}
    if(-not$force){Fail "$kind PID $id is still the recorded process; use -ForceStopOwned only for recovery"}
    $gp=Get-Process -Id $id -ErrorAction SilentlyContinue;if(-not$gp){return};if($kind-eq'Prism'){try{[void]$gp.CloseMainWindow();if($gp.WaitForExit(10000)){return}}catch{}};Stop-Process -Id $id -Force
}
function Register-BenchTask([object]$st,[string]$stateFile) {
    $invoke="& '"+$st.runner.Replace("'","''")+"' -StateFile '"+$stateFile.Replace("'","''")+"'";$b64=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($invoke));$ps="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $a=New-ScheduledTaskAction -Execute $ps -Argument "-NoLogo -NoProfile -NonInteractive -EncodedCommand $b64";$pr=New-ScheduledTaskPrincipal -UserId $st.interactiveUser -LogonType Interactive -RunLevel Limited;$set=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds ([int]$st.timeoutSeconds+180))
    Register-ScheduledTask -TaskName $st.taskName -Action $a -Principal $pr -Settings $set -Force|Out-Null
}

if($RunId -and $RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'){Fail 'RunId must be 1-64 path-safe characters: letters, digits, dot, underscore, hyphen'}
$stateFile=if($RunId){Join-Path (Full $StateRoot) (Join-Path $RunId 'state.json')}else{$null}
if($Action -in @('Status','Postflight','Recover') -and -not$stateFile){Fail 'RunId is required'}

switch($Action){
'Preflight'{
    $c=Config;if(Test-Path -LiteralPath $stateFile){Fail 'transaction already exists'};$p=Pre $c;$resolvedUser=Canonical-SessionUser $p.session
    [pscustomobject]@{status='ok';sessionId=$p.session.sessionId;interactiveUser=$resolvedUser;originalJar=$p.jar.path;originalJarSha256=$p.jar.sha256;candidateSha256=$c.candidateSha;instanceCfgSha256=Sha $c.instanceCfg}|ConvertTo-Json;break
}
'Stage'{
    $c=Config
    if(Test-Path -LiteralPath $stateFile){$old=Load $stateFile;if($old.phase-eq'staged'){$live=One-Wrapper $old.modsDir $old.candidateSha256;if((Sha $old.instanceCfg)-ne$old.stagedCfgSha256){Fail 'staged instance.cfg drifted before repeat Stage'};$old|ConvertTo-Json -Depth 10;break};Fail "existing transaction phase $($old.phase); recover it before staging again"}
    $p=Pre $c;$resolvedUser=Canonical-SessionUser $p.session;$dir=Split-Path -Parent $stateFile;$bak=Join-Path $dir 'backup';New-Item -ItemType Directory -Force -Path $bak|Out-Null
    $cfgBak=Join-Path $bak 'instance.cfg.original';Copy-Item -LiteralPath $c.instanceCfg -Destination $cfgBak
    $jarBak=Join-Path $bak $p.jar.name;Copy-Item -LiteralPath $p.jar.path -Destination $jarBak;if((Sha $jarBak)-ne$p.jar.sha256){Fail 'JAR backup hash mismatch'}
    $runner=Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'remote_laptop_interactive_run.ps1';if(-not(Test-Path -LiteralPath $runner -PathType Leaf)){Fail "missing runner $runner"}
    $taskNonce=[Guid]::NewGuid().ToString('N').Substring(0,12)
    $target=Join-Path $c.modsDir ('bootoptim-bench-'+$c.runId+'-'+$c.candidateSha.Substring(0,12)+'.jar');if($target.Equals($p.jar.path,[StringComparison]::OrdinalIgnoreCase)){$target=Join-Path $c.modsDir ('bootoptim-bench-staged-'+$c.candidateSha.Substring(0,12)+'.jar')}
    if(Test-Path -LiteralPath $target){Fail "staged target path already exists: $target"}
    $tmp=$target+'.partial-'+[Guid]::NewGuid().ToString('N');if(Test-Path -LiteralPath $tmp){Fail "staging temporary path already exists: $tmp"}
    $st=[ordered]@{schema=3;phase='staging';valid=$false;reason=$null;runId=$c.runId;instanceRoot=$c.instanceRoot;gameRoot=$c.gameRoot;modsDir=$c.modsDir;instanceCfg=$c.instanceCfg;prismExe=$c.prismExe;prismRoot=$c.prismRoot;instanceId=$c.instanceId;interactiveUser=$resolvedUser;expectedSessionId=$p.session.sessionId;candidateSha256=$c.candidateSha;requiredJvmArgs=@($c.required);forbiddenJvmArgs=@($c.forbidden);expectedJavaExe=$c.expectedJava;timeoutSeconds=$c.timeout;originalCfgSha256=Sha $c.instanceCfg;cfgBackup=$cfgBak;originalJarPath=$p.jar.path;originalJarSha256=$p.jar.sha256;jarBackup=$jarBak;stagedJar=$target;stagedTemp=$tmp;stagedCfgSha256=$null;runner=$runner;taskName=('BootOptimBench-'+$c.runId+'-'+$taskNonce);prismPid=0;prismCreationDate=$null;javaPid=0;javaCreationDate=$null;effectiveCommandLineSha256=$null;observedBootOptimPropertyKeys=@();validatedRequiredJvmArgs=@();effectiveJavaExe=$null;createdUtc=[DateTime]::UtcNow.ToString('o')};Save $st $stateFile
    Remove-Item -LiteralPath $p.jar.path
    try{Copy-Item -LiteralPath $c.artifactJar -Destination $tmp;if((Sha $tmp)-ne$c.candidateSha){Fail 'candidate copy hash mismatch'};Move-Item -LiteralPath $tmp -Destination $target}catch{throw}finally{if(Test-Path -LiteralPath $tmp){Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue}}
    $live=One-Wrapper $c.modsDir $c.candidateSha
    $txt=[IO.File]::ReadAllText($c.instanceCfg);$txt=Set-CfgKey $txt 'OverrideJavaArgs' 'true';$txt=Set-CfgKey $txt 'JvmArgs' (Qs $c.jvmArgs);[IO.File]::WriteAllText($c.instanceCfg,$txt,$Utf8)
    $st.stagedCfgSha256=Sha $c.instanceCfg;$st.phase='staged';Save $st $stateFile;$st|ConvertTo-Json -Depth 10;break
}
'Run'{
    $st=Load $stateFile
    if($st.phase -notin @('staged','task_registered')){[pscustomobject]@{status='not_dispatched';phase=$st.phase;valid=$st.valid;reason=$st.reason}|ConvertTo-Json;break}
    if(@(Prism-Procs $st.prismExe).Count){Fail 'Prism appeared after staging'};if(@(Target-Java $st.gameRoot $st.instanceRoot).Count){Fail 'target Java appeared after staging'}
    $s=Active-Session $st.interactiveUser;if([int]$s.sessionId-ne[int]$st.expectedSessionId){Fail 'active session changed'};$live=One-Wrapper $st.modsDir $st.candidateSha256;if((Sha $st.instanceCfg)-ne$st.stagedCfgSha256){Fail 'instance.cfg changed after Stage'}
    $task=$null;try{$task=Get-ScheduledTask -TaskName $st.taskName -ErrorAction Stop}catch{}
    if(-not$task){Register-BenchTask $st $stateFile;$st.phase='task_registered';Save $st $stateFile}
    Start-ScheduledTask -TaskName $st.taskName
    [pscustomobject]@{status='dispatched';taskName=$st.taskName;expectedSessionId=$st.expectedSessionId;phase=$st.phase}|ConvertTo-Json;break
}
'Status'{
    $st=Load $stateFile;$ts=$null
    if($st.phase -in @('launching','validating','measuring')){$ts='suppressed_during_run'}else{try{$ts=(Get-ScheduledTask -TaskName $st.taskName -ErrorAction Stop).State.ToString()}catch{}}
    [pscustomobject]@{runId=$st.runId;phase=$st.phase;valid=$st.valid;reason=$st.reason;taskState=$ts;sessionId=$st.expectedSessionId;javaPid=$st.javaPid;javaCreationDate=$st.javaCreationDate;prismPid=$st.prismPid;prismCreationDate=$st.prismCreationDate;effectiveJavaExe=$st.effectiveJavaExe;effectiveCommandLineSha256=$st.effectiveCommandLineSha256;observedBootOptimPropertyKeys=$st.observedBootOptimPropertyKeys;validatedRequiredJvmArgs=$st.validatedRequiredJvmArgs}|ConvertTo-Json -Depth 6;break
}
{$_ -in @('Postflight','Recover')}{
    $st=Load $stateFile;if([int]$st.schema-ne3){Fail "unsupported transaction schema $($st.schema)"};if($Action-eq'Postflight' -and $st.phase-notin@('finished','invalid')){Fail "Postflight refuses phase $($st.phase)"}
    Stop-Owned ([int]$st.javaPid) ([string]$st.javaCreationDate) 'Java' $st $ForceStopOwned;Stop-Owned ([int]$st.prismPid) ([string]$st.prismCreationDate) 'Prism' $st $ForceStopOwned
    if(@(Prism-Procs $st.prismExe).Count){Fail 'Prism still running; restoration waits until Prism is fully closed'};if(@(Target-Java $st.gameRoot $st.instanceRoot).Count){Fail 'target Java still running; restoration refuses live files'}
    try{Unregister-ScheduledTask -TaskName $st.taskName -Confirm:$false -ErrorAction SilentlyContinue}catch{}
    if(-not(Test-Path -LiteralPath $st.jarBackup -PathType Leaf) -or (Sha $st.jarBackup)-ne$st.originalJarSha256){Fail 'original JAR backup is missing or changed'}
    $stagedTemp=$null;if($st.PSObject.Properties['stagedTemp']){$stagedTemp=[string]$st.stagedTemp}
    if($stagedTemp){$modsPrefix=(Full $st.modsDir).TrimEnd('\')+'\';$tempFull=Full $stagedTemp;if(-not$tempFull.StartsWith($modsPrefix,[StringComparison]::OrdinalIgnoreCase)){Fail 'recorded staging temporary path is outside mods'};if(Test-Path -LiteralPath $tempFull){if(-not(Test-Path -LiteralPath $tempFull -PathType Leaf)){Fail 'recorded staging temporary path is not a file'};Remove-Item -LiteralPath $tempFull -Force}}
    $arts=@(BootOptim-Artifacts $st.modsDir);foreach($a in $arts){$isStaged=$st.stagedJar -and $a.path.Equals([string]$st.stagedJar,[StringComparison]::OrdinalIgnoreCase) -and $a.sha256-eq$st.candidateSha256;$isOriginal=$a.path.Equals([string]$st.originalJarPath,[StringComparison]::OrdinalIgnoreCase) -and $a.sha256-eq$st.originalJarSha256;if(-not$isStaged -and -not$isOriginal){Fail "unexpected BootOptim artifact blocks safe recovery: $($a.path)"}}
    if($st.stagedJar -and (Test-Path -LiteralPath $st.stagedJar -PathType Leaf)){if((Sha $st.stagedJar)-ne$st.candidateSha256){Fail 'staged JAR changed; refusing to delete it'};Remove-Item -LiteralPath $st.stagedJar -Force}
    if(Test-Path -LiteralPath $st.originalJarPath -PathType Leaf){if((Sha $st.originalJarPath)-ne$st.originalJarSha256){Fail 'original JAR path contains unexpected bytes'}}else{Copy-Item -LiteralPath $st.jarBackup -Destination $st.originalJarPath -Force}
    if(-not(Test-Path -LiteralPath $st.cfgBackup -PathType Leaf) -or (Sha $st.cfgBackup)-ne$st.originalCfgSha256){Fail 'instance.cfg backup is missing or changed'};Copy-Item -LiteralPath $st.cfgBackup -Destination $st.instanceCfg -Force
    $r=One-Wrapper $st.modsDir $st.originalJarSha256;if((Sha $st.instanceCfg)-ne$st.originalCfgSha256){Fail 'instance.cfg restore failed'}
    $st.phase='restored';$st.restoredUtc=[DateTime]::UtcNow.ToString('o');Save $st $stateFile
    [pscustomobject]@{status='restored';valid=$st.valid;reason=$st.reason;instanceCfgSha256=Sha $st.instanceCfg;bootOptimSha256=$r.sha256}|ConvertTo-Json;break
}
}
