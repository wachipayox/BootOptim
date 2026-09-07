[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$root = Join-Path $env:RUNNER_TEMP ("bootoptim-native-exit-probe-test-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $root | Out-Null
$probe = Join-Path $PSScriptRoot 'remote_laptop_native_exit_probe.ps1'
$source = Join-Path $root 'NativeExitProbeFixture.java'
$class = Join-Path $root 'NativeExitProbeFixture.class'
$fixture = $null
$fixture2 = $null

$sourceText = @'
public final class NativeExitProbeFixture {
    public static void main(String[] args) throws Exception {
        Thread.sleep(30000L);
    }
}
'@
[IO.File]::WriteAllText($source, $sourceText, $Utf8NoBom)

& (Join-Path $env:JAVA_HOME 'bin\javac.exe') $source
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $class)) {
    throw 'Failed to compile native-exit observer fixture'
}

function Start-Fixture {
    $p = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-cp', $root, 'NativeExitProbeFixture') -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 100
        $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue
    } while (-not $cim -and [DateTime]::UtcNow -lt $deadline)
    if (-not $cim) { throw "Fixture Java PID $($p.Id) did not become visible through CIM" }
    [pscustomobject]@{process=$p;creation=([DateTime]$cim.CreationDate).ToString('o')}
}

try {
    # Abrupt termination: the observer must still produce a final primary JSON.
    $fixture = Start-Fixture
    $successOut = Join-Path $root 'forced-exit.json'
    $job = Start-Job -ScriptBlock {
        param($Probe, $JavaPid, $Creation, $Out)
        & $Probe -JavaPid $JavaPid -JavaCreationDate $Creation -OutputFile $Out -PollMilliseconds 50 -PostExitEventGraceMilliseconds 0
    } -ArgumentList $probe, $fixture.process.Id, $fixture.creation, $successOut
    Start-Sleep -Milliseconds 750
    Stop-Process -Id $fixture.process.Id -Force -ErrorAction Stop
    if (-not (Wait-Job -Job $job -Timeout 30)) { throw 'Observer job did not finish after forced Java termination' }
    $jobOutput = Receive-Job -Job $job -ErrorAction SilentlyContinue
    if ($job.State -ne 'Completed') { throw "Observer failed on forced Java termination: $($job.State) $jobOutput" }
    if (-not (Test-Path -LiteralPath $successOut -PathType Leaf)) { throw 'Forced-exit observer did not write primary JSON' }
    $success = Get-Content -LiteralPath $successOut -Raw | ConvertFrom-Json
    if ($success.status -ne 'complete') { throw "Forced-exit observer status is '$($success.status)', expected complete" }
    if ([int]$success.javaPid -ne [int]$fixture.process.Id) { throw 'Forced-exit observer recorded the wrong Java PID' }
    if (-not $success.exitedUtc) { throw 'Forced-exit observer did not checkpoint parent exit time' }
    Remove-Job -Job $job -Force

    # Fatal observer error before attach completion: both primary partial result and sidecar must survive.
    $fixture2 = Start-Fixture
    $badOut = Join-Path $root 'identity-error.json'
    $badCreation = '2000-01-01T00:00:00.0000000+00:00'
    $failed = $false
    try {
        & $probe -JavaPid $fixture2.process.Id -JavaCreationDate $badCreation -OutputFile $badOut -PollMilliseconds 50 -PostExitEventGraceMilliseconds 0
    } catch {
        $failed = $true
    }
    if (-not $failed) { throw 'Identity-mismatch observer invocation unexpectedly succeeded' }
    if (-not (Test-Path -LiteralPath $badOut -PathType Leaf)) { throw 'Identity-mismatch observer did not preserve primary partial JSON' }
    if (-not (Test-Path -LiteralPath "$badOut.observer-error.json" -PathType Leaf)) { throw 'Identity-mismatch observer did not preserve error sidecar' }
    $partial = Get-Content -LiteralPath $badOut -Raw | ConvertFrom-Json
    $sidecar = Get-Content -LiteralPath "$badOut.observer-error.json" -Raw | ConvertFrom-Json
    if ($partial.status -ne 'observer_error') { throw "Identity-error primary status is '$($partial.status)'" }
    if ($sidecar.stage -ne 'identity_query') { throw "Identity-error sidecar stage is '$($sidecar.stage)'" }
    if (-not [bool]$sidecar.primaryResultSaved) { throw 'Identity-error sidecar says primary result was not saved' }
    Stop-Process -Id $fixture2.process.Id -Force -ErrorAction SilentlyContinue

    Write-Host 'Native exit observer recovery tests passed.'
} finally {
    Get-Job | Where-Object { $_.Command -match 'remote_laptop_native_exit_probe' } | Remove-Job -Force -ErrorAction SilentlyContinue
    foreach ($candidate in @($fixture, $fixture2)) {
        if ($candidate -and $candidate.process) {
            try { Stop-Process -Id ([int]$candidate.process.Id) -Force -ErrorAction SilentlyContinue } catch {}
        }
    }
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
