[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$StateFile)

Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)

function Save([object]$o){$t="$StateFile.tmp-$PID";[IO.File]::WriteAllText($t,($o|ConvertTo-Json -Depth 10),$Utf8);Move-Item -LiteralPath $t -Destination $StateFile -Force}
function Load{Get-Content -LiteralPath $StateFile -Raw|ConvertFrom-Json}
function Fail([object]$s,[string]$m){$s.valid=$false;$s.reason=$m;$s.phase='invalid';Save $s;throw "BOOTOPTIM_REMOTE_INVALID: $m"}
function Text-Sha256([string]$text){$h=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($text)))).Replace('-','')}finally{$h.Dispose()}}

function Ensure-Native {
    if('BootOptimRemoteNative' -as [type]){return}
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class BootOptimRemoteNative {
  enum W { InitialProgram,ApplicationName,WorkingDirectory,OEMId,SessionId,UserName,WinStationName,DomainName,ConnectState }
  [DllImport("Wtsapi32.dll",SetLastError=true)] static extern bool WTSQuerySessionInformation(IntPtr s,int id,W i,out IntPtr p,out int n);
  [DllImport("Wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr p);
  [DllImport("shell32.dll",SetLastError=true)] static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)] string cmd,out int argc);
  [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
  static string S(int id,W i){IntPtr p;int n;if(!WTSQuerySessionInformation(IntPtr.Zero,id,i,out p,out n))return null;try{return Marshal.PtrToStringAnsi(p);}finally{WTSFreeMemory(p);}}
  public static string User(int id){return S(id,W.UserName);} public static string Domain(int id){return S(id,W.DomainName);}
  public static int State(int id){IntPtr p;int n;if(!WTSQuerySessionInformation(IntPtr.Zero,id,W.ConnectState,out p,out n))return -1;try{return Marshal.ReadInt32(p);}finally{WTSFreeMemory(p);}}
  public static string[] Args(string cmd){int n;IntPtr p=CommandLineToArgvW(cmd,out n);if(p==IntPtr.Zero)throw new Win32Exception();try{var a=new string[n];for(int i=0;i<n;i++)a[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(p,i*IntPtr.Size));return a;}finally{LocalFree(p);}}
}
'@
}
function Assert-ExpectedSession([object]$s){
    if([BootOptimRemoteNative]::State([int]$s.expectedSessionId)-ne0){throw "expected desktop session $($s.expectedSessionId) is not WTSActive"}
    $spec=[string]$s.interactiveUser;$dom=$null;$usr=$spec;if($spec.Contains('\')){$x=$spec.Split('\',2);$dom=$x[0];$usr=$x[1]}
    $u=[BootOptimRemoteNative]::User([int]$s.expectedSessionId);$d=[BootOptimRemoteNative]::Domain([int]$s.expectedSessionId)
    if(-not$u -or -not$u.Equals($usr,[StringComparison]::OrdinalIgnoreCase)){throw "active session user '$u' does not match '$usr'"}
    if($dom -and (-not$d -or -not$d.Equals($dom,[StringComparison]::OrdinalIgnoreCase))){throw "active session domain '$d' does not match '$dom'"}
}
function Target-Java([string]$game,[string]$root){
    $all=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    @($all|Where-Object{$c=[string]$_.CommandLine;$c -and (($c.IndexOf($game,[StringComparison]::OrdinalIgnoreCase)-ge0)-or($c.IndexOf($root,[StringComparison]::OrdinalIgnoreCase)-ge0))})
}
function Prism-Procs([string]$exe){$n=@('prismlauncher.exe','PrismLauncher.exe',[IO.Path]::GetFileName($exe))|Select-Object -Unique;@(Get-CimInstance Win32_Process|Where-Object{$n -contains $_.Name})}
function Quote-Arg([string]$v){
    if($null-eq$v -or $v.Length-eq0){return '""'}
    if($v.Contains('"')){throw 'Prism path/instance argument contains an unsupported double quote'}
    if($v -notmatch '\s'){return $v}
    $m=[regex]::Match($v,'\\+$');$trail=$m.Value.Length;$body=if($trail){$v.Substring(0,$v.Length-$trail)}else{$v};$tail=if($trail){(('\' * ($trail*2))-join'')}else{''}
    '"'+$body+$tail+'"'
}
function Exact-Count([object[]]$a,[string]$needle){$n=0;foreach($x in $a){if([string]::Equals([string]$x,$needle,[StringComparison]::Ordinal)){$n++}};$n}
function Stop-PrismOwned([object]$s){
    if([int]$s.prismPid-le0 -or -not$s.prismCreationDate){return}
    $c=Get-CimInstance Win32_Process -Filter "ProcessId=$($s.prismPid)" -ErrorAction SilentlyContinue;if(-not$c){return}
    if(([DateTime]$c.CreationDate).ToString('o')-ne[string]$s.prismCreationDate){return}
    $p=Get-Process -Id ([int]$s.prismPid) -ErrorAction SilentlyContinue;if(-not$p){return}
    try{[void]$p.CloseMainWindow();if($p.WaitForExit(15000)){return}}catch{}
    $p=Get-Process -Id ([int]$s.prismPid) -ErrorAction SilentlyContinue;if($p){Stop-Process -Id ([int]$s.prismPid) -Force -ErrorAction Stop}
}
function Archive-RunEvidence([object]$s) {
    # Copy only completed, bounded diagnostic files after Java has exited.  Never inspect logs while a
    # performance run is alive, and never claim an archive exists unless every copied path is recorded.
    $evidence=Join-Path (Split-Path -Parent $StateFile) 'evidence'
    New-Item -ItemType Directory -Force -Path $evidence|Out-Null
    $logs=Join-Path $s.gameRoot 'logs'
    $copied=@()
    foreach($name in @('latest.log','debug.log','bootoptim-startup.log')){
        $source=Join-Path $logs $name
        if(-not(Test-Path -LiteralPath $source -PathType Leaf)){continue}
        $destination=Join-Path $evidence $name
        Copy-Item -LiteralPath $source -Destination $destination -Force
        $copied += [pscustomobject]@{name=$name;length=(Get-Item -LiteralPath $destination).Length}
    }
    $s.logArchive=@($copied)
}

$s=Load
try{Ensure-Native}catch{Fail $s ("native helper setup failed: "+$_.Exception.Message)}
if([int]$s.schema-ne3){Fail $s "unsupported transaction schema $($s.schema)"}
try{Assert-ExpectedSession $s}catch{Fail $s $_.Exception.Message}
$self=Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
if([int]$self.SessionId-ne[int]$s.expectedSessionId){Fail $s "task session $($self.SessionId) != expected active session $($s.expectedSessionId)"}
if(@(Prism-Procs $s.prismExe).Count){Fail $s 'Prism already running before interactive launch'}
if(@(Target-Java $s.gameRoot $s.instanceRoot).Count){Fail $s 'target java/javaw already running before interactive launch'}

$s.phase='launching';$s.launchStartedUtc=[DateTime]::UtcNow.ToString('o');Save $s
try{
    $parts=@();if($s.prismRoot){$parts+='-d';$parts+=Quote-Arg([string]$s.prismRoot)};$parts+='-l';$parts+=Quote-Arg([string]$s.instanceId);$argLine=$parts-join' '
    $prism=Start-Process -FilePath $s.prismExe -ArgumentList $argLine -PassThru
    $pc=Get-CimInstance Win32_Process -Filter "ProcessId=$($prism.Id)" -ErrorAction Stop
    if([int]$pc.SessionId-ne[int]$s.expectedSessionId){throw "Prism started in session $($pc.SessionId), expected $($s.expectedSessionId)"}
    $s.prismPid=[int]$pc.ProcessId;$s.prismCreationDate=([DateTime]$pc.CreationDate).ToString('o');Save $s
}catch{Fail $s ("Prism launch failed: "+$_.Exception.Message)}

# Prism can spend several minutes materializing the instance before it creates
# the Java child on the slow HDD laptop.  A short detector timeout is unsafe:
# it marks the transaction invalid, may close only Prism, and can then leave a
# late Java process outside the transaction's identity record.  Keep this
# launch grace separate from the measured-process timeout and cap it so a truly
# failed Prism launch is still reported promptly.
$java=$null
$appearanceTimeoutSeconds=[Math]::Min(300,[Math]::Max(90,[int]$s.timeoutSeconds-60))
$deadline=[DateTime]::UtcNow.AddSeconds($appearanceTimeoutSeconds)
do{
    Start-Sleep -Milliseconds 1000
    $c=@(Target-Java $s.gameRoot $s.instanceRoot|Where-Object{[int]$_.SessionId-eq[int]$s.expectedSessionId})
    if($c.Count-gt1){try{Stop-PrismOwned $s}catch{};Fail $s "multiple target Java processes: $($c.Count)"}
    if($c.Count-eq1){$java=$c[0];break}
}while([DateTime]::UtcNow-lt$deadline)
if(-not$java){try{Stop-PrismOwned $s}catch{};Fail $s "no target java/javaw appeared within $appearanceTimeoutSeconds s"}

$javaHandle=$null;$javaIdentity=$null;$expectedJavaCreation=([DateTime]$java.CreationDate).ToString('o')
try{
    $javaHandle=[Diagnostics.Process]::GetProcessById([int]$java.ProcessId)
    $javaIdentity=Get-CimInstance Win32_Process -Filter "ProcessId=$($java.ProcessId)" -ErrorAction Stop
    $actualCreation=([DateTime]$javaIdentity.CreationDate).ToString('o')
    if($javaIdentity.Name -notin @('java.exe','javaw.exe') -or $actualCreation-ne$expectedJavaCreation -or [int]$javaIdentity.SessionId-ne[int]$s.expectedSessionId){throw 'target Java identity changed before process handle validation'}
}catch{
    try{if($javaHandle){$javaHandle.Dispose()}}catch{};try{Stop-PrismOwned $s}catch{};Fail $s 'target Java exited or PID was reused before identity validation'
}
$java=$javaIdentity
$s.javaPid=[int]$java.ProcessId;$s.javaCreationDate=$actualCreation;$s.effectiveJavaExe=[string]$java.ExecutablePath;$s.phase='validating';Save $s
try{
    Assert-ExpectedSession $s
    if([int]$java.SessionId-ne[int]$s.expectedSessionId){throw 'Java is outside the expected interactive session'}
    $cmd=[string]$java.CommandLine;if([string]::IsNullOrWhiteSpace($cmd)){throw 'effective Win32_Process.CommandLine unavailable'}
    $argv=@([BootOptimRemoteNative]::Args($cmd));if($argv.Count-lt2){throw 'effective command line could not be tokenized'}
    if($s.expectedJavaExe){if([string]::IsNullOrWhiteSpace([string]$java.ExecutablePath)){throw 'effective Java executable path unavailable'};$actual=[IO.Path]::GetFullPath([string]$java.ExecutablePath);if(-not$actual.Equals([string]$s.expectedJavaExe,[StringComparison]::OrdinalIgnoreCase)){throw "Java executable mismatch: $actual"}}
    foreach($r in @($s.requiredJvmArgs)){if((Exact-Count $argv ([string]$r))-ne1){throw "required JVM argument is not present exactly once: $r"}}
    foreach($f in @($s.forbiddenJvmArgs)){if((Exact-Count $argv ([string]$f))-gt0){throw "forbidden/stale JVM argument present: $f"}}
    $bootKeys=@();foreach($a in $argv){if(([string]$a)-match'^-D(boot_optim\.[A-Za-z0-9_.-]+)(?:=.*)?$'){$bootKeys+=$Matches[1]}}
    foreach($g in @($bootKeys|Group-Object)){if($g.Count-gt1){throw "duplicate BootOptim JVM property key: $($g.Name)"}}
    foreach($family in @('^-Xmx','^-Xms','^-XX:ActiveProcessorCount=')){if(@($argv|Where-Object{([string]$_)-match$family}).Count-gt1){throw "duplicate JVM singleton option family: $family"}}
    $s.effectiveCommandLineSha256=Text-Sha256 $cmd;$s.observedBootOptimPropertyKeys=@($bootKeys|Sort-Object -Unique);$s.validatedRequiredJvmArgs=@($s.requiredJvmArgs);$s.valid=$true;$s.reason=$null;$s.phase='measuring';Save $s
}catch{
    $m=$_.Exception.Message;try{if($javaHandle -and -not$javaHandle.HasExited){$javaHandle.Kill();$javaHandle.WaitForExit()}}catch{};try{Stop-PrismOwned $s}catch{};Fail $s $m
}

$exited=$javaHandle.WaitForExit(([int]$s.timeoutSeconds)*1000)
if(-not$exited){
    $s.valid=$false;$s.reason='java_timeout';$s.phase='invalid';Save $s
    try{if(-not$javaHandle.HasExited){$javaHandle.Kill();$javaHandle.WaitForExit()}}catch{}
}else{$s.javaExitedUtc=[DateTime]::UtcNow.ToString('o')}
try{Stop-PrismOwned $s}catch{$s.valid=$false;$s.reason='prism_close_failed'}
try{Archive-RunEvidence $s}catch{
    # Archive failure is not a game failure, but it must be explicit: subsequent performance or diagnostic
    # analysis has no permission to silently use whatever a later launch writes into the live logs.
    $s.logArchive=@([pscustomobject]@{error=$_.Exception.GetType().Name})
}
try{$javaHandle.Dispose()}catch{}
$s.phase=$(if($s.valid){'finished'}else{'invalid'});$s.finishedUtc=[DateTime]::UtcNow.ToString('o');Save $s
