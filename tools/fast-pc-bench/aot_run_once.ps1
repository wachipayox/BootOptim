# One startup of the Wachiland Launcher with the JDK 25 AOT cache on or off (two-step version, BootOptimPandora #104),
# on a physical PC, optionally restricted to N cores and a lower priority class (README.md, "AOT cache").
# Unlike run_once.ps1 it does not kill the launcher right after the game: it waits for the launcher's `create` step
# (it would die with the launcher's job object) and reads the record/create durations from launcher.log.
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Exe,
    [ValidateSet('on', 'off')][string]$Aot = 'on',
    [int]$Cores = 0,                      # 0 = all cores; N = affinity to the first N logical CPUs + -XX:ActiveProcessorCount=N
    [ValidateSet('normal', 'belownormal', 'low')][string]$Priority = 'normal',
    [switch]$ResetCache,                  # delete <instance>\aot-cache first (forces a record run)
    [string]$Flags = '',
    [string]$Instance = 'Wachiland Elite BETA',
    [string]$Data = (Join-Path $env:APPDATA 'WachilandLauncher'),
    [string]$Out = 'C:\BootOptimBench\local\runs',
    [int]$TimeoutMin = 20
)
$ErrorActionPreference = 'Stop'
function Add-Result([string]$file, [string]$text) {
    for ($i = 0; $i -lt 50; $i++) { try { [IO.File]::AppendAllText($file, $text + "`r`n"); return } catch { Start-Sleep -Milliseconds 200 } }
    throw "cannot write $file"
}
$inst = Join-Path $Data "instances\$Instance"
$info = Join-Path $inst 'info_v1.json'
$game = Join-Path $inst '.minecraft'
$cacheDir = Join-Path $inst 'aot-cache'
$launcherLog = Join-Path $Data 'launcher.log'
$launcherName = [IO.Path]::GetFileNameWithoutExtension($Exe)
if (Get-Process -Name $launcherName -EA SilentlyContinue) { throw 'launcher running' }
if (Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match 'bootstraplauncher|LaunchWrapper|AOTMode=create' }) { throw 'game or create java running' }
if ($ResetCache) { Remove-Item -Recurse -Force $cacheDir -EA SilentlyContinue }

$id = $Tag + '-' + (Get-Date -Format 'MMdd-HHmmss')
$runDir = Join-Path $Out $id; New-Item -ItemType Directory -Force $runDir | Out-Null
$mask = 'FFFFFFFF'
$coreFlag = ''
if ($Cores -gt 0) {
    $mask = '{0:X}' -f ([int64][math]::Pow(2, $Cores) - 1)
    # HotSpot sizes its compiler/GC threads from the core count, not from the affinity mask.
    $coreFlag = "-XX:ActiveProcessorCount=$Cores"
}
$marker = "-Dboot_optim.benchRunId=$id"
$all = ("-Dboot_optim.benchmark.exitOnTitle=true $coreFlag $Flags $marker").Trim() -replace '\s+', ' '
$j = Get-Content -LiteralPath $info -Raw | ConvertFrom-Json
$f = [pscustomobject]@{ enabled = $true; flags = $all }
if ($j.PSObject.Properties['jvm_flags']) { $j.jvm_flags = $f } else { $j | Add-Member jvm_flags $f }
[IO.File]::WriteAllText($info, ($j | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding $false))
Remove-Item (Join-Path $game 'logs\bootoptim-startup.log') -EA SilentlyContinue
# The launcher rotates launcher.log at start: the whole file is this run.

# Affinity and priority class are inherited by the game JVM and by the create JVM.
$env:WBOMBO_AOT_CACHE = $(if ($Aot -eq 'off') { 'off' } else { '' })
$prio = "/$Priority"
Start-Process cmd.exe -ArgumentList "/c start `"`" $prio /affinity $mask `"$Exe`" --run-instance-normal `"$Instance`" --no-update" -WindowStyle Hidden
Remove-Item Env:\WBOMBO_AOT_CACHE -EA SilentlyContinue

$java = $null; $deadline = (Get-Date).AddMinutes(5)
while (-not $java -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $java = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($id) } | Select-Object -First 1
}
if (-not $java) { throw "no Java with marker $id (see $launcherLog)" }
$p = Get-Process -Id $java.ProcessId
$javaStart = $p.StartTime
$aff = '{0:X}' -f [int64]$p.ProcessorAffinity
$prioClass = $p.PriorityClass
$ok = $p.WaitForExit($TimeoutMin * 60000)
$gameExit = Get-Date
if (-not $ok) { Stop-Process -Id $p.Id -Force; 'TIMEOUT' | Out-File "$runDir\status.txt" }

# Wait for the launcher's optimization: the create JVM, then a final log line.
$createSeen = $false
$deadline = (Get-Date).AddMinutes($TimeoutMin)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $create = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match 'AOTMode=create' -and $_.CommandLine -match [regex]::Escape($inst.Replace('\', '/')) }
    if ($create) { $createSeen = $true; continue }
    $tail = ''
    if (Test-Path $launcherLog) {
        $fs = [IO.File]::Open($launcherLog, 'Open', 'Read', 'ReadWrite')
        try { $tail = (New-Object IO.StreamReader($fs)).ReadToEnd() } finally { $fs.Close() }
    }
    $recorded = $tail -match 'mode=recorded'
    $done = $tail -match 'mode=created|create-failed|create-output-invalid|could not start the create step|training discarded'
    if ($done -or (-not $recorded -and ($tail -match 'mode=use|not used|FALLBACK|retry'))) { break }
    if (-not $recorded -and (Get-Date) -gt $gameExit.AddSeconds(15)) { break }  # nothing recorded: plain run
}
$optEnd = Get-Date
Start-Sleep 2
Get-Process -Name $launcherName -EA SilentlyContinue | Stop-Process -Force
Copy-Item (Join-Path $game 'logs\bootoptim-startup.log') $runDir -EA SilentlyContinue
Copy-Item (Join-Path $game 'logs\latest.log') $runDir -EA SilentlyContinue
$tail | Out-File (Join-Path $runDir 'launcher-tail.log') -Encoding utf8

$rep = Join-Path $runDir 'bootoptim-startup.log'
$mm = if (Test-Path $rep) { (Select-String $rep -Pattern 'PHASE name=main_menu uptime_ms=(\d+)' | Select-Object -First 1).Matches.Groups[1].Value } else { '' }
$me = if (Test-Path $rep) { (Select-String $rep -Pattern 'PHASE name=mod_entrypoint uptime_ms=(\d+)' | Select-Object -First 1).Matches.Groups[1].Value } else { '' }
$mode = if ($tail -match 'mechanism=jdk25-aot-cache \S+ mode=(\w+)') { $Matches[1] } else { 'none' }
$write = if ($tail -match 'mode=recorded .*write_seconds=([\d.?]+)') { $Matches[1] } else { '' }
$profile = if ($tail -match 'mode=recorded .*profile_mib=(\d+)') { $Matches[1] } else { '' }
$createS = if ($tail -match 'mode=created .*create_seconds=([\d.]+)') { $Matches[1] } else { '' }
$cacheMib = if ($tail -match 'mode=created .*size_mib=(\d+)') { $Matches[1] } else { '' }
$closeS = ''
if ($mm) { $closeS = '{0:N1}' -f (($gameExit - $javaStart.AddMilliseconds([int]$mm)).TotalSeconds) }
$optS = '{0:N1}' -f (($optEnd - $gameExit).TotalSeconds)
$line = "$id tag=$Tag aot=$Aot mode=$mode cores=$Cores priority=$Priority javaAffinity=$aff javaPriority=$prioClass mod_entrypoint_ms=$me main_menu_ms=$mm close_after_menu_s=$closeS record_write_s=$write profile_mib=$profile create_s=$createS cache_mib=$cacheMib after_exit_s=$optS exit=$($p.ExitCode)"
Add-Result (Join-Path $Out 'results.txt') $line
$line
