# AOT cache series for one simulated machine (README.md, "AOT cache"): a record run from an empty cache (training cost,
# record write and create durations), one discarded primer, then ABBA x Repeats (A = cache off, B = cache on).
# Keep the Beta revision fixed for the whole series (the launcher is started with --no-update).
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [int]$Cores = 0,
    [ValidateSet('normal', 'belownormal', 'low')][string]$Priority = 'normal',
    [int]$Repeats = 2,
    [string]$Instance = 'Wachiland Elite BETA',
    [string]$Out = 'C:\BootOptimBench\local\runs'
)
$ErrorActionPreference = 'Stop'
function Add-Result([string]$file, [string]$text) {
    for ($i = 0; $i -lt 50; $i++) { try { [IO.File]::AppendAllText($file, $text + "`r`n"); return } catch { Start-Sleep -Milliseconds 200 } }
    throw "cannot write $file"
}
New-Item -ItemType Directory -Force $Out | Out-Null
$PID | Set-Content (Join-Path $Out 'campaign.pid')
$cfg = "c$Cores-$Priority"
$once = Join-Path $PSScriptRoot 'aot_run_once.ps1'
function Run([string]$tag, [string]$aot, [switch]$reset) {
    $r = & $once -Tag "$cfg-$tag" -Exe $Exe -Aot $aot -Cores $Cores -Priority $Priority -Instance $Instance -Out $Out -ResetCache:$reset
    if ($r -notmatch 'main_menu_ms=\d+') { "INVALID: $r" | ForEach-Object { Add-Result (Join-Path $Out 'results.txt') $_ }; throw "invalid run $tag" }
    Start-Sleep 10
    $r
}
"# series $cfg $(Get-Date -Format s)" | ForEach-Object { Add-Result (Join-Path $Out 'results.txt') $_ }
$train = Run 'train' 'on' -reset
if ($train -notmatch 'mode=record' -or $train -notmatch 'create_s=[\d.]+') { throw "training did not record and create: $train" }
Run 'primer' 'off' | Out-Null
for ($i = 0; $i -lt $Repeats; $i++) {
    Run 'A' 'off' | Out-Null
    $b = Run 'B' 'on'
    if ($b -notmatch 'mode=use') { throw "cache not used: $b" }
    Run 'B' 'on' | Out-Null
    Run 'A' 'off' | Out-Null
}
"# series $cfg done" | ForEach-Object { Add-Result (Join-Path $Out 'results.txt') $_ }
