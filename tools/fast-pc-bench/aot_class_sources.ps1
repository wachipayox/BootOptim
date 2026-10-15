# Counts where classes come from up to the startup markers, for one run with -Xlog:class+load.
# Usage (PowerShell 5.1):
#   .\aot_class_sources.ps1 -ClassLoadLog <classload.log> -Report <bootoptim-startup.log>
# Needs the JVM flag  -Xlog:class+load=info:file=<path>:uptime  (uptime is the same clock as
# BootOptim's uptime_ms markers: both are JVM uptime).
# Classes are grouped by source:
#   AOT/CDS  "shared objects file" in a non-JDK package: a hit served from the AOT cache (in a run with
#            -XX:AOTCache). Non-JDK packages never come from the JDK's base CDS archive.
#   JDK-CDS  "shared objects file" in a java./javax./jdk./sun./com.sun. package (base archive or AOT).
#   DEFINED  "__JVM_DefineClass__": defined from bytes by a custom loader (parsed, not from cache).
#   JAR      "file:" source (loaded from disk, parsed).
#   JRT      "jrt:/" source (JDK module image, parsed).
#   OTHER    anything else.
# With -TrainingLog (class+load log of the -XX:AOTCacheOutput training run), DEFINED classes that the
# training run also loaded are reported as MISMATCH: their bytes differ from the training bytes, so the
# cache was rejected for them. That separates "not in cache" from "transformed bytes not reproducible".
param(
    [Parameter(Mandatory = $true)][string]$ClassLoadLog,
    [Parameter(Mandatory = $true)][string]$Report,
    [string]$TrainingLog = ''
)
$ErrorActionPreference = 'Stop'

$markers = @{}
foreach ($m in 'mod_entrypoint', 'main_menu') {
    $hit = Select-String -Path $Report -Pattern "PHASE name=$m uptime_ms=(\d+)" | Select-Object -First 1
    if (-not $hit) { throw "marker $m missing in $Report" }
    $markers[$m] = [double]$hit.Matches[0].Groups[1].Value
}

$jdkPkg = '^(java|javax|jdk|sun|com\.sun)\.'
$rx = '^\[(\d+(?:\.\d+)?)s\]\S*\s*\[class,load\]\s+(\S+)\s+source:\s*(.*)$'

$trained = New-Object 'System.Collections.Generic.HashSet[string]'
if ($TrainingLog) {
    foreach ($line in [System.IO.File]::ReadLines($TrainingLog)) {
        if ($line -match $rx) { [void]$trained.Add($matches[2]) }
    }
}
$rows = New-Object System.Collections.Generic.List[object]
foreach ($line in [System.IO.File]::ReadLines($ClassLoadLog)) {
    if ($line -match $rx) {
        $ms = [double]$matches[1] * 1000.0
        $name = $matches[2]
        $src = $matches[3].Trim()
        if ($src -like 'shared objects file*') {
            if ($name -match $jdkPkg) { $cls = 'JDK-CDS' } else { $cls = 'AOT/CDS' }
        } elseif ($src -eq '__JVM_DefineClass__') {
            if ($trained.Contains($name)) { $cls = 'MISMATCH' } else { $cls = 'DEFINED' }
        }
        elseif ($src -like 'file:*') { $cls = 'JAR' }
        elseif ($src -like 'jrt:*') { $cls = 'JRT' }
        else { $cls = 'OTHER' }
        $rows.Add([pscustomobject]@{ ms = $ms; name = $name; cls = $cls })
    }
}

foreach ($m in 'mod_entrypoint', 'main_menu') {
    $limit = $markers[$m]
    $upto = @($rows | Where-Object { $_.ms -le $limit })
    $groups = $upto | Group-Object cls
    $out = [ordered]@{ marker = $m; uptime_ms = $limit; loaded = $upto.Count }
    foreach ($k in 'AOT/CDS', 'JDK-CDS', 'DEFINED', 'MISMATCH', 'JAR', 'JRT', 'OTHER') {
        $g = $groups | Where-Object { $_.Name -eq $k }
        $out[$k] = if ($g) { $g.Count } else { 0 }
    }
    $nonJdk = $upto | Where-Object { $_.name -notmatch $jdkPkg }
    $out['non_jdk_loaded'] = @($nonJdk).Count
    $out['non_jdk_hit_pct'] = if (@($nonJdk).Count) { [math]::Round(100.0 * @($nonJdk | Where-Object { $_.cls -eq 'AOT/CDS' }).Count / @($nonJdk).Count, 2) } else { 0 }
    [pscustomobject]$out | Format-List
}
