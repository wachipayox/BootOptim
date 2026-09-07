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
    } else {
      Thread.sleep(3000L);
      Process p = new ProcessBuilder("cmd.exe", "/c", "ping 127.0.0.1 -n 6 >nul & exit /b 9").start();
      p.waitFor();
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
  $deadline=[DateTime]::UtcNow.AddSeconds(15);$s=$null
  do{Start-Sleep -Milliseconds 200;if(Test-Path $out){try{$s=Get-Content $out -Raw|ConvertFrom-Json}catch{}};if($s -and @($s.processes|Where-Object{$_.parentPid-ne$java.Id}).Count-gt0){break}}while([DateTime]::UtcNow-lt$deadline)
  if(-not$s){throw 'observer produced no checkpoint'}
  $grand=@($s.processes|Where-Object{$_.parentPid-ne$java.Id})
  if($grand.Count-eq0){$diag=$s|ConvertTo-Json -Depth 8 -Compress;throw "recursive descendant was not observed; status=$($s.status) state=$diag"}
  $deadline=[DateTime]::UtcNow.AddSeconds(12);$s=$null
  do{Start-Sleep -Milliseconds 200;$s=Get-Content $out -Raw|ConvertFrom-Json;$bad=@($s.processes|Where-Object{$_.exitCode-eq9});if($bad.Count-gt0){break}}while([DateTime]::UtcNow-lt$deadline)
  if(@($s.processes|Where-Object{$_.exitCode-eq9}).Count-eq0){$diag=$s|ConvertTo-Json -Depth 8 -Compress;throw "descendant exit code 9 was not persisted while parent stayed alive; state=$diag"}
  Stop-Process -Id $java.Id -Force
  if(-not(Wait-Job $job -Timeout 20)){throw 'observer did not finish'}
  Receive-Job $job -ErrorAction SilentlyContinue|Out-Null
  $s=Get-Content $out -Raw|ConvertFrom-Json
  if($s.status-ne'complete'){throw "observer status $($s.status)"}
  if($s.exitCode-eq0){throw 'forced parent exit unexpectedly recorded zero'}
  if($s.exitClassification-ne'java_nonzero_exit'){throw "classification $($s.exitClassification)"}
  if(@($s.timeline|Where-Object{$_.event-eq'process_exit' -and $_.exitCode-eq9}).Count-eq0){throw 'timeline lost descendant exit'}
  Write-Host 'Exit causality observer tests passed.'
}finally{
  try{Stop-Process -Id $java.Id -Force -ErrorAction SilentlyContinue}catch{}
  Remove-Job $job -Force -ErrorAction SilentlyContinue
  Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}
