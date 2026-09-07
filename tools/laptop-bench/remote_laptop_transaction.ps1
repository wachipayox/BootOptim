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
$Marker = 'dev/wachipayox/bootoptim/bootstrap/DiscoveryStartLocator.class'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Fail([string]$m) { throw "BOOTOPTIM_REMOTE_INVALID: $m" }
function Full([string]$p) { if ([string]::IsNullOrWhiteSpace($p)) { return $null }; [IO.Path]::GetFullPath($p) }
function Sha([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToUpperInvariant() }
function Save([object]$o,[string]$p) { $d=Split-Path -Parent $p; New-Item -ItemType Directory -Force -Path $d|Out-Null; $t="$p.tmp-$PID"; [IO.File]::WriteAllText($t,($o|ConvertTo-Json -Depth 8),$Utf8); Move-Item -LiteralPath $t -Destination $p -Force }
function Load([string]$p) { if(-not(Test-Path -LiteralPath $p -PathType Leaf)){Fail "missing state $p"}; Get-Content -LiteralPath $p -Raw|ConvertFrom-Json }

function Is-BootOptim([string]$p) {
    try { Add-Type -AssemblyName System.IO.Compression.FileSystem; $z=[IO.Compression.ZipFile]::OpenRead($p); try { foreach($e in $z.Entries){if($e.FullName -eq $Marker){return $true}}; return $false } finally {$z.Dispose()} } catch { return $false }
}
function BootOptim-Jars([string]$mods) {
    if(-not(Test-Path -LiteralPath $mods -PathType Container)){return @()}
    @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar'|Where-Object{Is-BootOptim $_.FullName}|ForEach-Object{[pscustomobject]@{path=$_.FullName;name=$_.Name;sha256=Sha $_.FullName}})
}
function Prism-Procs([string]$exe) {
    $names=@('prismlauncher.exe','PrismLauncher.exe'); if($exe){$names += [IO.Path]::GetFileName($exe)}; $names=@($names|Select-Object -Unique)
    @(Get-CimInstance Win32_Process|Where-Object{$names -contains $_.Name})
}
function Target-Java([string]$game,[string]$root) {
    @(Get-CimInstance Win32_Process|Where-Object{$_.Name -in @('java.exe','javaw.exe')}|Where-Object{ $c=[string]$_.CommandLine; $c -and (($c.IndexOf($game,[StringComparison]::OrdinalIgnoreCase)-ge 0)-or($c.IndexOf($root,[StringComparison]::OrdinalIgnoreCase)-ge 0)) })
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
    Ensure-Wts; $dom=$null; $usr=$spec; if($spec.Contains('\')){$a=$spec.Split('\',2);$dom=$a[0];$usr=$a[1]}
    $out=@(); foreach($sid in @(Get-Process explorer -ErrorAction SilentlyContinue|Select-Object -ExpandProperty SessionId -Unique)){
        if([BootOptimWts]::State([int]$sid)-ne 0){continue}; $u=[BootOptimWts]::User([int]$sid);$d=[BootOptimWts]::Domain([int]$sid)
        if($u -and $u.Equals($usr,[StringComparison]::OrdinalIgnoreCase) -and (-not $dom -or ($d -and $d.Equals($dom,[StringComparison]::OrdinalIgnoreCase)))){$out += [pscustomobject]@{sessionId=[int]$sid;user=$u;domain=$d}}
    }
    if($out.Count-ne 1){Fail "expected one WTSActive Explorer session for '$spec', found $($out.Count)"}; $out[0]
}

function Set-CfgKey([string]$text,[string]$key,[string]$value) {
    $nl=if($text.Contains("`r`n")){"`r`n"}else{"`n"}; $pat='(?m)^'+[regex]::Escape($key)+'=.*$'; $rep=$key+'='+$value
    if([regex]::IsMatch($text,$pat)){return [regex]::Replace($text,$pat,$rep)}; if($text.Length-gt 0 -and -not $text.EndsWith("`n")){$text+=$nl}; $text+$rep+$nl
}
function Qs([string]$v) {
    if($v.Contains("`r")-or$v.Contains("`n")-or$v.Contains([char]0)-or$v.Contains('"')){Fail 'JvmArgs may not contain newline, NUL, or quotes; keep path-bearing quoting out of this benchmark contract'}
    $v.Replace('\','\\')
}

function Config {
    foreach($n in @('RunId','InstanceRoot','PrismExe','InstanceId','InteractiveUser','ArtifactJar','ExpectedJarSha256','JvmArgs')){if([string]::IsNullOrWhiteSpace((Get-Variable -Name $n -ValueOnly))){Fail "$n is required"}}
    if($TimeoutSeconds-lt 600){Fail 'TimeoutSeconds < 600 is unsafe for the observed 350-380 s startup regime'}
    $r=Full $InstanceRoot; $g=Join-Path $r '.minecraft'; if(-not(Test-Path -LiteralPath $g -PathType Container)){Fail "missing $g"}
    [pscustomobject]@{runId=$RunId;instanceRoot=$r;gameRoot=$g;modsDir=Join-Path $g 'mods';instanceCfg=Join-Path $r 'instance.cfg';prismExe=Full $PrismExe;prismRoot=if($PrismRoot){(Full $PrismRoot).TrimEnd('\')}else{$null};instanceId=$InstanceId;interactiveUser=$InteractiveUser;artifactJar=Full $ArtifactJar;candidateSha=$ExpectedJarSha256.ToUpperInvariant();jvmArgs=$JvmArgs;required=@($RequiredJvmArg);forbidden=@($ForbiddenJvmArg);expectedJava=if($ExpectedJavaExe){Full $ExpectedJavaExe}else{$null};timeout=$TimeoutSeconds}
}
function Pre([object]$c) {
    foreach($p in @($c.instanceRoot,$c.gameRoot,$c.modsDir)){if(-not(Test-Path -LiteralPath $p -PathType Container)){Fail "missing directory $p"}}
    foreach($p in @($c.instanceCfg,$c.prismExe,$c.artifactJar)){if(-not(Test-Path -LiteralPath $p -PathType Leaf)){Fail "missing file $p"}}
    if((Full $c.artifactJar).StartsWith((Full $c.modsDir),[StringComparison]::OrdinalIgnoreCase)){Fail 'ArtifactJar must be outside the live mods directory'}
    if(-not(Is-BootOptim $c.artifactJar)){Fail 'candidate is not the packaged BootOptim wrapper'}; if((Sha $c.artifactJar)-ne$c.candidateSha){Fail 'candidate SHA-256 mismatch'}
    $j=@(BootOptim-Jars $c.modsDir); if($j.Count-ne 1){Fail "expected exactly one live BootOptim wrapper, found $($j.Count)"}
    if(@(Prism-Procs $c.prismExe).Count){Fail 'Prism must be fully stopped before touching instance.cfg'}; if(@(Target-Java $c.gameRoot $c.instanceRoot).Count){Fail 'target java/javaw already exists'}
    $s=Active-Session $c.interactiveUser; [pscustomobject]@{jar=$j[0];session=$s}
}
function Stop-Owned([int]$id,[string]$kind,[switch]$force) {
    if($id-le 0){return}; $p=Get-Process -Id $id -ErrorAction SilentlyContinue; if(-not$p){return}; if(-not$force){Fail "$kind PID $id still alive; use -ForceStopOwned only for the recorded transaction PID"}; if($kind-eq'Prism'){try{[void]$p.CloseMainWindow();if($p.WaitForExit(10000)){return}}catch{}}; Stop-Process -Id $id -Force
}

$stateFile=if($RunId){Join-Path (Full $StateRoot) (Join-Path $RunId 'state.json')}else{$null}
if($Action -in @('Status','Postflight','Recover') -and -not$stateFile){Fail 'RunId is required'}

switch($Action){
'Preflight'{ $c=Config; if(Test-Path -LiteralPath $stateFile){Fail 'transaction already exists'}; $p=Pre $c; [pscustomobject]@{status='ok';sessionId=$p.session.sessionId;originalJar=$p.jar.path;originalJarSha256=$p.jar.sha256;candidateSha256=$c.candidateSha;instanceCfgSha256=Sha $c.instanceCfg}|ConvertTo-Json; break }
'Stage'{
    $c=Config; if(Test-Path -LiteralPath $stateFile){$old=Load $stateFile;if($old.phase-eq'staged'){$old|ConvertTo-Json -Depth 8;break};Fail "existing transaction phase $($old.phase)"}; $p=Pre $c
    $dir=Split-Path -Parent $stateFile;$bak=Join-Path $dir 'backup';New-Item -ItemType Directory -Force -Path $bak|Out-Null
    $cfgBak=Join-Path $bak 'instance.cfg.original';Copy-Item -LiteralPath $c.instanceCfg -Destination $cfgBak
    $jarBak=Join-Path $bak $p.jar.name;Copy-Item -LiteralPath $p.jar.path -Destination $jarBak;if((Sha $jarBak)-ne$p.jar.sha256){Fail 'JAR backup hash mismatch'}
    $runner=Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'remote_laptop_interactive_run.ps1';if(-not(Test-Path -LiteralPath $runner -PathType Leaf)){Fail "missing runner $runner"}
    $st=[ordered]@{schema=1;phase='staging';valid=$false;reason=$null;runId=$c.runId;instanceRoot=$c.instanceRoot;gameRoot=$c.gameRoot;modsDir=$c.modsDir;instanceCfg=$c.instanceCfg;prismExe=$c.prismExe;prismRoot=$c.prismRoot;instanceId=$c.instanceId;interactiveUser=$c.interactiveUser;expectedSessionId=$p.session.sessionId;candidateSha256=$c.candidateSha;requiredJvmArgs=@($c.required);forbiddenJvmArgs=@($c.forbidden);expectedJavaExe=$c.expectedJava;timeoutSeconds=$c.timeout;originalCfgSha256=Sha $c.instanceCfg;cfgBackup=$cfgBak;originalJarPath=$p.jar.path;originalJarSha256=$p.jar.sha256;jarBackup=$jarBak;stagedJar=$null;runner=$runner;taskName=('BootOptimBench-'+($c.runId-replace'[^A-Za-z0-9_.-]','_'));prismPid=0;javaPid=0;effectiveCommandLine=$null;effectiveJavaExe=$null;createdUtc=[DateTime]::UtcNow.ToString('o')};Save $st $stateFile
    Remove-Item -LiteralPath $p.jar.path; $target=Join-Path $c.modsDir ('bootoptim-bench-'+$c.candidateSha.Substring(0,12)+'.jar');$tmp="$target.partial-$PID";Copy-Item -LiteralPath $c.artifactJar -Destination $tmp;if((Sha $tmp)-ne$c.candidateSha){Fail 'candidate copy hash mismatch'};Move-Item -LiteralPath $tmp -Destination $target -Force
    $live=@(BootOptim-Jars $c.modsDir);if($live.Count-ne1 -or $live[0].sha256-ne$c.candidateSha){Fail 'exactly-one candidate JAR invariant failed after staging'}
    $txt=[IO.File]::ReadAllText($c.instanceCfg);$txt=Set-CfgKey $txt 'OverrideJavaArgs' 'true';$txt=Set-CfgKey $txt 'JvmArgs' (Qs $c.jvmArgs);[IO.File]::WriteAllText($c.instanceCfg,$txt,$Utf8)
    $st.stagedJar=$target;$st.stagedCfgSha256=Sha $c.instanceCfg;$st.phase='staged';Save $st $stateFile;$st|ConvertTo-Json -Depth 8;break
}
'Run'{
    $st=Load $stateFile;if($st.phase-ne'staged'){Fail "Run requires staged, got $($st.phase)"};if(@(Prism-Procs $st.prismExe).Count){Fail 'Prism appeared after staging'};if(@(Target-Java $st.gameRoot $st.instanceRoot).Count){Fail 'target Java appeared after staging'}
    $s=Active-Session $st.interactiveUser;if([int]$s.sessionId-ne[int]$st.expectedSessionId){Fail 'active session changed'};$live=@(BootOptim-Jars $st.modsDir);if($live.Count-ne1 -or $live[0].sha256-ne$st.candidateSha256){Fail 'candidate JAR invariant failed immediately before Run'}
    $invoke="& '"+$st.runner.Replace("'","''")+"' -StateFile '"+$stateFile.Replace("'","''")+"'";$b64=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($invoke));$ps="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $a=New-ScheduledTaskAction -Execute $ps -Argument "-NoLogo -NoProfile -NonInteractive -EncodedCommand $b64";$pr=New-ScheduledTaskPrincipal -UserId $st.interactiveUser -LogonType Interactive -RunLevel Limited;$set=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Seconds ([int]$st.timeoutSeconds+180))
    Register-ScheduledTask -TaskName $st.taskName -Action $a -Principal $pr -Settings $set -Force|Out-Null;$st.phase='task_registered';Save $st $stateFile;Start-ScheduledTask -TaskName $st.taskName;[pscustomobject]@{status='started';taskName=$st.taskName;expectedSessionId=$st.expectedSessionId}|ConvertTo-Json;break
}
'Status'{ $st=Load $stateFile;$ts=$null;try{$ts=(Get-ScheduledTask -TaskName $st.taskName).State.ToString()}catch{};[pscustomobject]@{runId=$st.runId;phase=$st.phase;valid=$st.valid;reason=$st.reason;taskState=$ts;sessionId=$st.expectedSessionId;javaPid=$st.javaPid;prismPid=$st.prismPid;effectiveJavaExe=$st.effectiveJavaExe;effectiveCommandLine=$st.effectiveCommandLine}|ConvertTo-Json -Depth 5;break }
{$_ -in @('Postflight','Recover')}{
    $st=Load $stateFile;if($Action-eq'Postflight' -and $st.phase-notin@('finished','invalid')){Fail "Postflight refuses phase $($st.phase)"};Stop-Owned ([int]$st.javaPid) 'Java' $ForceStopOwned;Stop-Owned ([int]$st.prismPid) 'Prism' $ForceStopOwned
    if(@(Prism-Procs $st.prismExe).Count){Fail 'Prism still running; restoration waits until Prism is fully closed'};if(@(Target-Java $st.gameRoot $st.instanceRoot).Count){Fail 'target Java still running; restoration refuses live files'};try{Unregister-ScheduledTask -TaskName $st.taskName -Confirm:$false -ErrorAction SilentlyContinue}catch{}
    foreach($j in @(BootOptim-Jars $st.modsDir)){Remove-Item -LiteralPath $j.path -Force};if((Sha $st.jarBackup)-ne$st.originalJarSha256){Fail 'original JAR backup changed'};Copy-Item -LiteralPath $st.jarBackup -Destination $st.originalJarPath -Force
    if((Sha $st.cfgBackup)-ne$st.originalCfgSha256){Fail 'instance.cfg backup changed'};Copy-Item -LiteralPath $st.cfgBackup -Destination $st.instanceCfg -Force;$r=@(BootOptim-Jars $st.modsDir);if($r.Count-ne1 -or$r[0].sha256-ne$st.originalJarSha256){Fail 'original JAR restore failed'};if((Sha $st.instanceCfg)-ne$st.originalCfgSha256){Fail 'instance.cfg restore failed'}
    $st.phase='restored';$st.restoredUtc=[DateTime]::UtcNow.ToString('o');Save $st $stateFile;[pscustomobject]@{status='restored';valid=$st.valid;reason=$st.reason;instanceCfgSha256=Sha $st.instanceCfg;bootOptimSha256=$r[0].sha256}|ConvertTo-Json;break
}
}
