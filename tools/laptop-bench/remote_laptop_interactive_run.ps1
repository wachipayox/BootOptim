[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$StateFile)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)
function Save([object]$o){$t="$StateFile.tmp-$PID";[IO.File]::WriteAllText($t,($o|ConvertTo-Json -Depth 8),$Utf8);Move-Item -LiteralPath $t -Destination $StateFile -Force}
function Load{Get-Content -LiteralPath $StateFile -Raw|ConvertFrom-Json}
function Fail([object]$s,[string]$m){$s.valid=$false;$s.reason=$m;$s.phase='invalid';Save $s;throw "BOOTOPTIM_REMOTE_INVALID: $m"}
function Target-Java([string]$game,[string]$root){@(Get-CimInstance Win32_Process|Where-Object{$_.Name -in @('java.exe','javaw.exe')}|Where-Object{$c=[string]$_.CommandLine;$c -and (($c.IndexOf($game,[StringComparison]::OrdinalIgnoreCase)-ge 0)-or($c.IndexOf($root,[StringComparison]::OrdinalIgnoreCase)-ge 0))})}
function Prism-Procs([string]$exe){$n=@('prismlauncher.exe','PrismLauncher.exe',[IO.Path]::GetFileName($exe))|Select-Object -Unique;@(Get-CimInstance Win32_Process|Where-Object{$n -contains $_.Name})}
function Stop-Pid([int]$id,[bool]$gui=$false){if($id-le0){return};$p=Get-Process -Id $id -ErrorAction SilentlyContinue;if(-not$p){return};if($gui){try{[void]$p.CloseMainWindow();if($p.WaitForExit(10000)){return}}catch{}};Stop-Process -Id $id -Force -ErrorAction SilentlyContinue}
function Quote([string]$v){if($v -notmatch '[\s"]'){return $v};if($v.Contains('"')){throw 'quote in Prism argument'};'"'+$v.TrimEnd('\')+'"'}

$s=Load
$self=Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
if([int]$self.SessionId-ne[int]$s.expectedSessionId){Fail $s "task session $($self.SessionId) != expected active session $($s.expectedSessionId)"}
if(@(Prism-Procs $s.prismExe).Count){Fail $s 'Prism already running before interactive launch'}
if(@(Target-Java $s.gameRoot $s.instanceRoot).Count){Fail $s 'target java/javaw already running before interactive launch'}
$s.phase='launching';$s.launchStartedUtc=[DateTime]::UtcNow.ToString('o');Save $s

$args=@();if($s.prismRoot){$args+='-d';$args+=Quote([string]$s.prismRoot)};$args+='-l';$args+=Quote([string]$s.instanceId)
$prism=Start-Process -FilePath $s.prismExe -ArgumentList $args -PassThru
$s.prismPid=$prism.Id;Save $s

$java=$null;$deadline=[DateTime]::UtcNow.AddSeconds(90)
do{Start-Sleep -Milliseconds 250;$c=@(Target-Java $s.gameRoot $s.instanceRoot|Where-Object{[int]$_.SessionId-eq[int]$s.expectedSessionId});if($c.Count-gt1){Fail $s "multiple target Java processes: $($c.Count)"};if($c.Count-eq1){$java=$c[0];break}}while([DateTime]::UtcNow-lt$deadline)
if(-not$java){Stop-Pid ([int]$s.prismPid) $true;Fail $s 'no target java/javaw appeared within 90 s'}

$s.javaPid=[int]$java.ProcessId;$s.javaCreationDate=([DateTime]$java.CreationDate).ToString('o');$s.effectiveCommandLine=[string]$java.CommandLine;$s.effectiveJavaExe=[string]$java.ExecutablePath;$s.phase='validating';Save $s
try{
    $cmd=[string]$s.effectiveCommandLine;if([string]::IsNullOrWhiteSpace($cmd)){throw 'effective Win32_Process.CommandLine unavailable'}
    if([int]$java.SessionId-ne[int]$s.expectedSessionId){throw 'Java is outside the expected interactive session'}
    if($s.expectedJavaExe){$actual=[IO.Path]::GetFullPath([string]$java.ExecutablePath);if(-not$actual.Equals([string]$s.expectedJavaExe,[StringComparison]::OrdinalIgnoreCase)){throw "Java executable mismatch: $actual"}}
    foreach($r in @($s.requiredJvmArgs)){if($cmd.IndexOf([string]$r,[StringComparison]::Ordinal)-lt0){throw "missing required JVM arg: $r"};if(([string]$r)-match'^(-D[^=]+)='){$key=$Matches[1]+'=';if([regex]::Matches($cmd,[regex]::Escape($key)).Count-ne1){throw "property key not exactly once: $key"}}}
    foreach($f in @($s.forbiddenJvmArgs)){if($cmd.IndexOf([string]$f,[StringComparison]::Ordinal)-ge0){throw "forbidden/stale JVM arg present: $f"}}
    $keys=[regex]::Matches($cmd,'-Dboot_optim\.[A-Za-z0-9_.-]+=')|ForEach-Object{$_.Value};foreach($g in @($keys|Group-Object)){if($g.Count-gt1){throw "duplicate BootOptim JVM property: $($g.Name)"}}
}catch{
    $m=$_.Exception.Message;Stop-Pid ([int]$s.javaPid);Stop-Pid ([int]$s.prismPid) $true;Fail $s $m
}

$s.valid=$true;$s.reason=$null;$s.phase='measuring';Save $s
$p=[Diagnostics.Process]::GetProcessById([int]$s.javaPid)
$exited=$p.WaitForExit(([int]$s.timeoutSeconds)*1000)
if(-not$exited){$s.valid=$false;$s.reason='java_timeout';$s.phase='invalid';Save $s;Stop-Pid ([int]$s.javaPid)}else{$s.javaExitedUtc=[DateTime]::UtcNow.ToString('o')}
Stop-Pid ([int]$s.prismPid) $true
$s.phase=$(if($s.valid){'finished'}else{'invalid'});$s.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $s
