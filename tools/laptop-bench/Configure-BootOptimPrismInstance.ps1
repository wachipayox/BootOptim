<#
.SYNOPSIS
Writes the minimal Prism metadata for the isolated BootOptim benchmark instance.

.DESCRIPTION
The values mirror the user's inspected Microsoft Launcher profile, except for
the diagnostic LevelRenderer property, which is intentionally excluded from
normal performance runs. Prism necessarily supplies an explicit `-Xms`; 128 MiB
is the value selected by the same Java 25 runtime when no initial heap is
specified on this laptop.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$InstanceDirectory = 'C:\BootOptimBench\prism\instances\BootOptimBench',

    [ValidateNotNullOrEmpty()]
    [string]$JavaPath = 'C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe',

    [ValidateRange(1, 8192)]
    [int]$InitialHeapMiB = 128,

    [ValidateRange(1024, 8192)]
    [int]$MaximumHeapMiB = 6144
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$gameDirectory = Join-Path $InstanceDirectory '.minecraft'
$instanceConfigPath = Join-Path $InstanceDirectory 'instance.cfg'
$componentPath = Join-Path $InstanceDirectory 'mmc-pack.json'

if (-not (Test-Path -LiteralPath $gameDirectory -PathType Container)) {
    throw "The isolated game directory is missing: '$gameDirectory'. Clone it before configuring Prism."
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw "The inspected benchmark Java runtime is missing: '$JavaPath'."
}
$prismJavaPath = $JavaPath -replace '\\', '/'
foreach ($path in @($instanceConfigPath, $componentPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing Prism metadata: '$path'."
    }
}

$instanceConfig = @(
    # Prism's instance settings belong to the General INI group. Without this
    # header it can later rewrite a setting after the UI group, where it is
    # ignored (notably LowMemWarning).
    '[General]'
    'InstanceType=OneSix'
    'iconKey=default'
    'name=BootOptimBench'
    'notes=Disposable isolated instance for BootOptim laptop benchmark runs.'
    'OverrideJavaLocation=true'
    # Prism reads this file as an INI and treats backslash as an escape.
    "JavaPath=$prismJavaPath"
    'OverrideMemory=true'
    "MinMemAlloc=$InitialHeapMiB"
    "MaxMemAlloc=$MaximumHeapMiB"
    # Preserve the pack's 6 GiB heap without an interactive Prism warning.
    'LowMemWarning=false'
    'OverrideJavaArgs=true'
    'JvmArgs=-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -Dboot_optim.profileStartup=true -Dboot_optim.benchmark.exitOnTitle=true'
)

$components = [ordered]@{
    formatVersion = 1
    components = @(
        [ordered]@{
            cachedName = 'Minecraft'
            cachedRequires = @()
            cachedVersion = '1.21.1'
            dependencyOnly = $false
            uid = 'net.minecraft'
            version = '1.21.1'
        },
        [ordered]@{
            cachedName = 'NeoForge'
            cachedRequires = @([ordered]@{
                equals = '1.21.1'
                uid = 'net.minecraft'
            })
            cachedVersion = '21.1.248'
            dependencyOnly = $false
            uid = 'net.neoforged'
            version = '21.1.248'
        }
    )
}

try {
    Set-Content -LiteralPath $instanceConfigPath -Value $instanceConfig -Encoding utf8
    $components | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $componentPath -Encoding utf8
}
catch {
    # The files did not exist before this script. Remove only files created by
    # this failed attempt so the user is never left with ambiguous metadata.
    Remove-Item -LiteralPath $instanceConfigPath, $componentPath -Force -ErrorAction SilentlyContinue
    throw
}

[ordered]@{
    instanceConfig = $instanceConfigPath
    componentManifest = $componentPath
    javaPath = $prismJavaPath
    initialHeapMiB = $InitialHeapMiB
    maximumHeapMiB = $MaximumHeapMiB
    gameDirectory = $gameDirectory
    excludedDiagnosticProperty = 'boot_optim.profileLevelRendererReload'
} | ConvertTo-Json -Compress
