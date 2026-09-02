param(
    [Parameter(Mandatory = $true)][string]$Variant,
    [Parameter(Mandatory = $true)][int]$Iteration,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'

$consoleLog = Join-Path $PWD 'exact-pack-console.log'
$threadDump = Join-Path $PWD 'exact-pack-thread-dump.log'
$resultJson = Join-Path $PWD 'result.json'
$latestLog = Join-Path $PWD 'run-pack-benchmark/logs/latest.log'
$startupLog = Join-Path $PWD 'run-pack-benchmark/logs/bootoptim-startup.log'

Remove-Item -LiteralPath $consoleLog -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $threadDump -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $resultJson -Force -ErrorAction SilentlyContinue

$command = 'gradlew.bat runPackBenchmarkClient --no-daemon --console=plain > exact-pack-console.log 2>&1'
Write-Host "Launching exact-pack benchmark variant=$Variant iteration=$Iteration"
$process = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/s', '/c', $command -PassThru -NoNewWindow
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$markerFound = $false

try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2

        if (Test-Path -LiteralPath $consoleLog) {
            if (Select-String -LiteralPath $consoleLog -SimpleMatch 'BOOTOPTIM_STARTUP phase=main_menu' -Quiet) {
                $markerFound = $true
                break
            }
        }

        if ($process.HasExited) {
            break
        }
        $process.Refresh()
    }

    if (-not $markerFound) {
        New-Item -ItemType File -Path $threadDump -Force | Out-Null
        $javaPids = @(& jps -q 2>$null)
        foreach ($pidText in $javaPids) {
            if ($pidText -match '^\d+$') {
                Add-Content -LiteralPath $threadDump -Value "===== JVM $pidText ====="
                & jcmd $pidText Thread.print 2>&1 | Add-Content -LiteralPath $threadDump
            }
        }

        if (-not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F | Out-Host
        }

        if (Test-Path -LiteralPath $consoleLog) {
            Get-Content -LiteralPath $consoleLog -Tail 200 | Out-Host
        }
        throw "Exact-pack benchmark did not reach the main-menu marker within $TimeoutSeconds seconds."
    }

    $exitDeadline = (Get-Date).AddSeconds(30)
    while (-not $process.HasExited -and (Get-Date) -lt $exitDeadline) {
        Start-Sleep -Seconds 1
        $process.Refresh()
    }
    if (-not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F | Out-Host
    }

    if (-not (Test-Path -LiteralPath $latestLog)) {
        throw "Exact-pack run reached the marker but latest.log is missing: $latestLog"
    }
    if (-not (Test-Path -LiteralPath $startupLog)) {
        throw "Exact-pack run reached the marker but bootoptim-startup.log is missing: $startupLog"
    }

    if (Select-String -LiteralPath $latestLog -Pattern 'InvalidInjectionException|Mixin (apply|prepare) for mod boot_optim failed' -Quiet) {
        throw 'BootOptim Mixin failure detected in exact-pack latest.log.'
    }

    & python scripts/exact-pack/summarize_startup.py single `
        --latest $latestLog `
        --startup $startupLog `
        --variant $Variant `
        --iteration $Iteration `
        --output $resultJson
    if ($LASTEXITCODE -ne 0) {
        throw "Exact-pack summarizer failed with exit $LASTEXITCODE"
    }
} finally {
    if (-not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null
    }
}
