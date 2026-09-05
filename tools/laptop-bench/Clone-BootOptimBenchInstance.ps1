<#
.SYNOPSIS
Creates the disposable Prism instance used for BootOptim laptop benchmarks.

.DESCRIPTION
This script is deliberately selective.  It copies the pack inputs that can
affect loading while excluding the live profile's worlds, accounts, logs and
mutable caches.  It refuses to populate a non-empty destination, so invoking
it again cannot overwrite benchmark evidence or a previous isolated instance.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$SourceGameDirectory = 'C:\Users\wachi\AppData\Roaming\.minecraft_welite_beta',

    [ValidateNotNullOrEmpty()]
    [string]$InstanceDirectory = 'C:\BootOptimBench\prism\instances\BootOptimBench',

    [ValidateNotNullOrEmpty()]
    [string]$BenchRoot = 'C:\BootOptimBench'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$statePath = Join-Path $BenchRoot 'state\instance-clone.json'
$logPath = Join-Path $BenchRoot 'state\instance-clone.log'
$destinationGameDirectory = Join-Path $InstanceDirectory '.minecraft'

function Write-State([string]$Status, [string]$Detail) {
    $state = [ordered]@{
        schemaVersion = 1
        status = $Status
        timestamp = (Get-Date).ToUniversalTime().ToString('o')
        sourceGameDirectory = $SourceGameDirectory
        instanceDirectory = $InstanceDirectory
        destinationGameDirectory = $destinationGameDirectory
        detail = $Detail
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
}

function Copy-PackDirectory([string]$Name) {
    $source = Join-Path $SourceGameDirectory $Name
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        Add-Content -LiteralPath $logPath -Value "skip missing directory: $Name" -Encoding utf8
        return
    }

    $target = Join-Path $destinationGameDirectory $Name
    $robocopyArguments = @(
        $source, $target, '/E', '/COPY:DAT', '/DCOPY:DAT', '/R:1', '/W:1',
        '/XJ', '/NFL', '/NDL', '/NP', '/NJH', '/NJS'
    )
    # MCEF's libraries are immutable pack inputs; its cache is generated state
    # and is intentionally excluded even though it lives below `mods`.
    if ($Name -eq 'mods') {
        $robocopyArguments += @('/XD', (Join-Path $source 'mcef-cache'))
    }
    & robocopy.exe @robocopyArguments
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed for '$Name' with exit code $LASTEXITCODE."
    }
    Add-Content -LiteralPath $logPath -Value "copied directory: $Name (robocopy exit $LASTEXITCODE)" -Encoding utf8
}

try {
    New-Item -ItemType Directory -Path (Join-Path $BenchRoot 'state') -Force | Out-Null
    Set-Content -LiteralPath $logPath -Value "started $(Get-Date -Format o)" -Encoding utf8

    if (-not (Test-Path -LiteralPath $SourceGameDirectory -PathType Container)) {
        throw "Source game directory does not exist: '$SourceGameDirectory'."
    }
    if (Test-Path -LiteralPath $destinationGameDirectory) {
        $existing = @(Get-ChildItem -LiteralPath $destinationGameDirectory -Force -ErrorAction Stop)
        if ($existing.Count -gt 0) {
            throw "Refusing to overwrite non-empty benchmark game directory: '$destinationGameDirectory'."
        }
    }

    New-Item -ItemType Directory -Path $destinationGameDirectory -Force | Out-Null
    Write-State -Status 'running' -Detail 'Copying selected pack inputs into an isolated Prism instance.'

    # Keep this list explicit.  Omitting runtime outputs is an invariant, not an
    # optimisation: saves, logs, launcher accounts and mutable MCEF caches are
    # never part of the benchmark clone.
    $directories = @(
        'mods', 'config', 'defaultconfigs', 'automodpack', 'resourcepacks',
        'shaderpacks', 'kubejs', 'scripts', 'paxi', 'openloader',
        'global_packs', 'fancymenu_data', '.analogaudio', 'ldlib2',
        'music_sheets', 'patched_shaders'
    )
    foreach ($directory in $directories) {
        Copy-PackDirectory -Name $directory
    }

    foreach ($file in @('options.txt', 'optionsviveprofiles.txt')) {
        $source = Join-Path $SourceGameDirectory $file
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $destinationGameDirectory $file) -Force
            Add-Content -LiteralPath $logPath -Value "copied file: $file" -Encoding utf8
        }
    }

    $modCount = @(Get-ChildItem -LiteralPath (Join-Path $destinationGameDirectory 'mods') -File -ErrorAction Stop).Count
    if ($modCount -eq 0) {
        throw 'The isolated instance contains no mod JARs; refusing to mark the clone ready.'
    }

    Write-State -Status 'ready' -Detail "Isolated instance ready with $modCount mod JARs. Live profile was read only."
    Add-Content -LiteralPath $logPath -Value "completed $(Get-Date -Format o); mod_count=$modCount" -Encoding utf8
}
catch {
    $message = $_.Exception.Message
    try {
        Write-State -Status 'failed' -Detail $message
        Add-Content -LiteralPath $logPath -Value "failed $(Get-Date -Format o): $message" -Encoding utf8
    }
    catch {
        Write-Error "Clone failed and state could not be recorded: $message"
    }
    throw
}
