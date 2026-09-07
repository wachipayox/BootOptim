[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)
$root=Join-Path $env:RUNNER_TEMP ('bootoptim-exit-causality-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $root|Out-Null
$probe=Join-Path $PSScriptRoot 'remote_laptop_exit_causality_probe.ps1'
$src=Join-Path $root 'ExitTreeFixture.java'
$txt=@'
public final class ExitTreeFixture {
  public static void main(String[] args) throws Exception {
    String java = System.getProperty("java.home") + "\\bin\\java.exe";
    if (args.length == 0 || args[0].equals("parent")) {
      new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "ExitTreeFixture", "child").start();
      Thread.sleep(30000L);
    } else if (args[0].equals("child")) {
      Thread.sleep(3000L);
      Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "ExitTreeFixture", "grandchild").start();
      p.waitFor();
      Thread.sleep(30000L);
    } else {
      Thread.sleep(30000L);
    }
  }
}
'@
[IO.File]::WriteAllText($src,$txt,$Utf8)
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') $src
if($LASTEXITCODE-ne0){throw 'fixture compile failed'}
function Cim([int]$id){Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue}
$java=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-cp',$root,'ExitTreeFixture','parent') -PassThru
Start-Sleep -Milliseconds 300
$c=Cim $java.Id;if(-not$c){throw 'fixture Java missing'};$creation=([DateTime]$c.CreationDate).ToString('o')
$out=Join-Path $root 'result.json'
$job=Start-Job -ScriptBlock{param($p,$id,$cr,$o)& $p -JavaPid $id -JavaCreationDate $cr -OutputFile $o -PollMilliseconds 100 -PostExitEventGraceMilliseconds 0} -ArgumentList $probe,$java.Id,$creation,$out
try{
  $deadline=[DateTime]::UtcNow.AddSeconds(15);$s=$null;$grand=$null
  do{
    Start-Sleep -Milliseconds 200
    if(Test-Path $out){try{$s=Get-Content $out -Raw|ConvertFrom-Json}catch{}}
    if($s){
      $directJava=@($s.processes|Where-Object{$_.parentPid-eq$java.Id -and $_.name-eq'java.exe'})|Select-Object -First 1
      if($directJava){$grand=@($s.processes|Where-Object{$_.parentPid-eq$directJava.pid -and $_.name-eq'java.exe'})|Select-Object -First 1}
      if($grand){break}
    }
  }while([DateTime]::UtcNow-lt$deadline)
  if(-not$s){throw 'observer produced no checkpoint'}
  if(-not$grand){$diag=$s|ConvertTo-Json -Depth 8 -Compress;throw "recursive Java grandchild was not observed; status=$($s.status) state=$diag"}

  Stop-Process -Id ([int]$grand.pid) -Force -ErrorAction Stop
  $deadline=[DateTime]::UtcNow.AddSeconds(10);$s=$null;$grandExit=$null
  do{
    Start-Sleep -Milliseconds 200
    $s=Get-Content $out -Raw|ConvertFrom-Json
    $grandExit=@($s.processes|Where-Object{$_.pid-eq$grand.pid -and $_.exitedUtc})|Select-Object -First 1
    if($grandExit){break}
  }while([DateTime]::UtcNow-lt$deadline)
  if(-not$grandExit){$diag=$s|ConvertTo-Json -Depth 8 -Compress;throw "grandchild exit was not persisted while root stayed alive; state=$diag"}
  if([int]$grandExit.exitCode-eq0){throw "force-killed grandchild unexpectedly recorded exit 0"}
  if(@($s.timeline|Where-Object{$_.event-eq'process_exit' -and $_.pid-eq$grand.pid -and $_.exitCode-ne0}).Count-eq0){throw 'timeline lost nonzero grandchild exit'}

  Stop-Process -Id $java.Id -Force
  if(-not(Wait-Job $job -Timeout 20)){throw 'observer did not finish'}
  Receive-Job $job -ErrorAction SilentlyContinue|Out-Null
  $s=Get-Content $out -Raw|ConvertFrom-Json
  if($s.status-ne'complete'){throw "observer status $($s.status)"}
  if($s.exitCode-eq0){throw 'forced parent exit unexpectedly recorded zero'}
  if($s.exitClassification-ne'java_nonzero_exit'){throw "classification $($s.exitClassification)"}
  Write-Host 'Exit causality observer tests passed.'
}finally{
  try{Stop-Process -Id $java.Id -Force -ErrorAction SilentlyContinue}catch{}
  Remove-Job $job -Force -ErrorAction SilentlyContinue
  Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}
