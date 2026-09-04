param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,

    [switch]$Json
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
    throw "Log file not found: $LogPath"
}

$lines = Get-Content -LiteralPath $LogPath
$mainMenuMs = $null
$entries = @{}
$blockForceMs = $null
$entityForceMs = $null
$coordinatorMs = $null
$mixinErrors = 0

function Get-Entry([int]$Id) {
    if (-not $entries.ContainsKey($Id)) {
        $entries[$Id] = [ordered]@{
            Entry = $Id
            RendererReloadPending = $null
            AttachBeginMs = $null
            AttachReadyMs = $null
            FirstRenderMs = $null
        }
    }
    return $entries[$Id]
}

foreach ($line in $lines) {
    if ($line -match 'BOOTOPTIM_STARTUP phase=main_menu uptime_ms=(\d+)') {
        $mainMenuMs = [long]$Matches[1]
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_begin entry=(\d+) uptime_ms=(\d+) renderer_reload_pending=(true|false)') {
        $entry = Get-Entry ([int]$Matches[1])
        $entry.AttachBeginMs = [long]$Matches[2]
        $entry.RendererReloadPending = [bool]::Parse($Matches[3])
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_WORLD_ENTRY status=attach_ready entry=(\d+) uptime_ms=(\d+) warmup_ms=(-?\d+)') {
        $entry = Get-Entry ([int]$Matches[1])
        $entry.AttachReadyMs = [long]$Matches[2]
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_WORLD_ENTRY status=first_render entry=(\d+) uptime_ms=(\d+) since_attach_begin_ms=(-?\d+) since_attach_ready_ms=(-?\d+)') {
        $entry = Get-Entry ([int]$Matches[1])
        $entry.FirstRenderMs = [long]$Matches[2]
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=block_entity status=forced consumer=coordinator:world_attach force_ms=([0-9.]+)') {
        $blockForceMs = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_FIRST_CONSUMER dispatcher=entity status=forced consumer=coordinator:world_attach force_ms=([0-9.]+)') {
        $entityForceMs = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        continue
    }

    if ($line -match 'BOOTOPTIM_RENDERER_RELOAD_COORDINATOR status=complete reason=world_attach total_ms=([0-9.]+)') {
        $coordinatorMs = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        continue
    }

    if ($line -match 'InvalidInjectionException|Mixin apply for mod boot_optim failed|Mixin prepare for mod boot_optim failed') {
        $mixinErrors++
    }
}

$entryRows = @(
    foreach ($id in ($entries.Keys | Sort-Object)) {
        $entry = $entries[$id]
        $titleToAttach = if ($null -ne $mainMenuMs -and $null -ne $entry.AttachBeginMs) {
            $entry.AttachBeginMs - $mainMenuMs
        } else { $null }
        $warmup = if ($null -ne $entry.AttachBeginMs -and $null -ne $entry.AttachReadyMs) {
            $entry.AttachReadyMs - $entry.AttachBeginMs
        } else { $null }
        $attachToRender = if ($null -ne $entry.AttachBeginMs -and $null -ne $entry.FirstRenderMs) {
            $entry.FirstRenderMs - $entry.AttachBeginMs
        } else { $null }
        $readyToRender = if ($null -ne $entry.AttachReadyMs -and $null -ne $entry.FirstRenderMs) {
            $entry.FirstRenderMs - $entry.AttachReadyMs
        } else { $null }

        [pscustomobject]@{
            Entry = $id
            RendererReloadPending = $entry.RendererReloadPending
            MainMenuUptimeMs = $mainMenuMs
            AttachBeginUptimeMs = $entry.AttachBeginMs
            AttachReadyUptimeMs = $entry.AttachReadyMs
            FirstRenderUptimeMs = $entry.FirstRenderMs
            TitleToAttachMs = $titleToAttach
            WarmupMs = $warmup
            AttachToFirstRenderMs = $attachToRender
            ReadyToFirstRenderMs = $readyToRender
        }
    }
)

$result = [pscustomobject]@{
    LogPath = (Resolve-Path -LiteralPath $LogPath).Path
    MainMenuUptimeMs = $mainMenuMs
    BlockEntityForceMs = $blockForceMs
    EntityForceMs = $entityForceMs
    CoordinatorForceMs = $coordinatorMs
    BootOptimMixinErrors = $mixinErrors
    Entries = $entryRows
}

if ($Json) {
    $result | ConvertTo-Json -Depth 5
    exit 0
}

Write-Host "Renderer world-entry gate"
Write-Host "  log: $($result.LogPath)"
Write-Host "  main menu uptime: $mainMenuMs ms"
Write-Host "  force: block=$blockForceMs ms entity=$entityForceMs ms total=$coordinatorMs ms"
Write-Host "  BootOptim Mixin errors: $mixinErrors"
Write-Host ""

if ($entryRows.Count -eq 0) {
    Write-Warning 'No BOOTOPTIM_RENDERER_WORLD_ENTRY markers found. Enable -Dboot_optim.experimentRendererWorldEntryProbe=true.'
    exit 2
}

$entryRows | Format-Table -AutoSize
