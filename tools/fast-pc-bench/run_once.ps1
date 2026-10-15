# One startup on a physical PC through the Wachiland Launcher (see README.md).
# Writes jvm_flags (+ run marker) and jvm_binary into info_v1.json, starts the launcher with a CPU affinity mask (Java inherits it),
# wait for Java (exitOnTitle) to exit, copy the startup report + latest.log.
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Flags = '',
    [string]$Mask = 'FFFF',
    [string]$Instance = 'Wachiland Elite BETA',
    [Parameter(Mandatory = $true)][string]$Exe,
    [string]$Out = 'C:\BootOptimBench\local\runs',
    [switch]$CheckFlags,
    [string]$JavaPath = '',
    [int]$TimeoutMin = 20
)
$ErrorActionPreference = 'Stop'
$data = Join-Path $env:APPDATA 'WachilandLauncher'
$inst = Join-Path $data "instances\$Instance"
$info = Join-Path $inst 'info_v1.json'
$game = Join-Path $inst '.minecraft'
if (Get-Process -Name 'WachilandLauncher*' -EA SilentlyContinue) { throw 'launcher running' }
if (Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match 'bootstraplauncher|LaunchWrapper' }) { throw 'game java running' }
$id = $Tag + '-' + (Get-Date -Format 'MMdd-HHmmss')
$runDir = Join-Path $Out $id; New-Item -ItemType Directory -Force $runDir | Out-Null
$marker = "-Dboot_optim.benchRunId=$id"
$all = ("-Dboot_optim.benchmark.exitOnTitle=true $Flags $marker").Trim() -replace '\s+', ' '
$j = Get-Content -LiteralPath $info -Raw | ConvertFrom-Json
$f = [pscustomobject]@{ enabled = $true; flags = $all }
if ($j.PSObject.Properties['jvm_flags']) { $j.jvm_flags = $f } else { $j | Add-Member jvm_flags $f }
if ($JavaPath) { $b = [pscustomobject]@{ enabled = $true; path = $JavaPath } } else { $b = [pscustomobject]@{ enabled = $false; path = '' } }
if ($j.PSObject.Properties['jvm_binary']) { $j.jvm_binary = $b } else { $j | Add-Member jvm_binary $b }
[IO.File]::WriteAllText($info, ($j | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding $false))
Remove-Item (Join-Path $game 'logs\bootoptim-startup.log') -EA SilentlyContinue
$t0 = Get-Date
Start-Process cmd.exe -ArgumentList "/c start `"`" /affinity $Mask `"$Exe`" --run-instance-normal `"$Instance`" --no-update" -WindowStyle Hidden
$java = $null; $deadline = (Get-Date).AddMinutes(5)
while (-not $java -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $java = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($id) } | Select-Object -First 1
}
if (-not $java) { throw "no Java with marker $id (see $data\launcher.log)" }
$p = Get-Process -Id $java.ProcessId
$aff = '{0:X}' -f [int64]$p.ProcessorAffinity
if ($CheckFlags) {
    Start-Sleep 3
    $jcmd = Join-Path (Split-Path $java.ExecutablePath) 'jcmd.exe'
    if (-not (Test-Path $jcmd)) { $jcmd = 'C:\Program Files\Java\jdk-21\bin\jcmd.exe' }
    if (Test-Path $jcmd) { & $jcmd $java.ProcessId VM.flags 2>&1 | Out-File "$runDir\vmflags.txt" }
}
$ok = $p.WaitForExit($TimeoutMin * 60000)
if (-not $ok) { Stop-Process -Id $p.Id -Force; 'TIMEOUT' | Out-File "$runDir\status.txt" }
Start-Sleep 3
Get-Process -Name 'WachilandLauncher*' -EA SilentlyContinue | Stop-Process -Force
Copy-Item (Join-Path $game 'logs\bootoptim-startup.log') $runDir -EA SilentlyContinue
Copy-Item (Join-Path $game 'logs\latest.log') $runDir -EA SilentlyContinue
$rep = Join-Path $runDir 'bootoptim-startup.log'
$mm = if (Test-Path $rep) { (Select-String $rep -Pattern 'PHASE name=main_menu uptime_ms=(\d+)' | Select-Object -First 1).Matches.Groups[1].Value } else { '' }
$me = if (Test-Path $rep) { (Select-String $rep -Pattern 'PHASE name=mod_entrypoint uptime_ms=(\d+)' | Select-Object -First 1).Matches.Groups[1].Value } else { '' }
$line = "$id tag=$Tag mask=$Mask javaAffinity=$aff flags='$Flags' mod_entrypoint_ms=$me main_menu_ms=$mm exit=$($p.ExitCode) java=$($java.ExecutablePath)"
Add-Content (Join-Path $Out 'results.txt') $line
$line
