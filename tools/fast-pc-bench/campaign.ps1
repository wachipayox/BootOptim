# Runs a plan of startups with run_once.ps1 and stops at the first invalid run (no main_menu).
# Plan lines: tag|affinityMaskHex|extra JVM flags|optional javaw.exe path   ('#' comments allowed)
param(
    [Parameter(Mandatory = $true)][string]$Plan,
    [Parameter(Mandatory = $true)][string]$Exe,
    [string]$Out = 'C:\BootOptimBench\local\runs'
)
$ErrorActionPreference = 'Stop'
$PID | Set-Content (Join-Path $Out 'campaign.pid')
foreach ($l in Get-Content $Plan) {
    if (-not $l.Trim() -or $l.StartsWith('#')) { continue }
    $tag, $mask, $flags, $java = $l.Split('|')
    $r = & (Join-Path $PSScriptRoot 'run_once.ps1') -Tag $tag -Mask $mask -Flags ([string]$flags) -JavaPath ([string]$java) -Exe $Exe -Out $Out
    if ($r -notmatch 'main_menu_ms=\d+') { "INVALID: $r" | Add-Content (Join-Path $Out 'results.txt'); throw "invalid run $tag" }
    Start-Sleep 10
}
'CAMPAIGN DONE' | Add-Content (Join-Path $Out 'results.txt')
