[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PlanPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$plan = Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json
$root = [IO.Path]::GetFullPath([string]$plan.outputRoot)
if (Test-Path -LiteralPath $root) { throw 'Campaign output already exists; refuse stale or concurrent campaign' }
New-Item -ItemType Directory -Path $root | Out-Null
function Save($value, $path) {
    $tmp = "$path.tmp-$PID"
    [IO.File]::WriteAllText($tmp, ($value | ConvertTo-Json -Depth 12), $utf8)
    if ([IO.File]::Exists($path)) { [IO.File]::Replace($tmp, $path, [NullString]::Value) }
    else { [IO.File]::Move($tmp, $path) }
}
function ConfigValue($file, $key) {
    $m = @([regex]::Matches([IO.File]::ReadAllText($file), '(?m)^' + [regex]::Escape($key) + '=(.*)\r?$'))
    if ($m.Count -gt 1) { throw "Duplicate Prism key: $key" }
    if ($m.Count -eq 0) { return '' }
    # QSettings wraps some string values in quotes. They are serialization,
    # not literal shell/JVM argument quotes; remove them before unescaping.
    $value = $m[0].Groups[1].Value.TrimEnd("`r")
    if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $value.Replace('\\', '\').Replace('\"', '"')
}
function PackSelection($file) {
    $lines = @(Get-Content -LiteralPath $file | Where-Object { $_.StartsWith('resourcePacks:') })
    if ($lines.Count -ne 1) { throw 'Ambiguous resource-pack selection' }
    $lines[0]
}
$game = Join-Path ([string]$plan.instanceRoot) '.minecraft'
$cfg = Join-Path ([string]$plan.instanceRoot) 'instance.cfg'
$options = Join-Path $game 'options.txt'
$tx = Join-Path $PSScriptRoot 'remote_laptop_transaction.ps1'
$lockPath = Join-Path $game '.bootoptim-express-sweep.lock'
$lock = $null
$comparison = if ($plan.PSObject.Properties.Name -contains 'comparisonMode') { [string]$plan.comparisonMode } else { 'individual' }
if ($comparison -notin @('individual','combined-abba','inprocess')) { throw 'Unknown comparison mode' }
$summary = [ordered]@{schema=1; comparisonMode=$comparison; status='preflight'; origin='physical_laptop'; cacheState='session_uncontrolled'; startedUtc=[DateTime]::UtcNow.ToString('o'); runs=@(); error=$null; finishedUtc=$null}
try {
    # Lock ownership is a handle, not a process-name guess. Never remove another campaign's lock.
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if ($env:COMPUTERNAME -ne [string]$plan.computerName) { throw 'Laptop computer identity mismatch' }
    Copy-Item -LiteralPath $PlanPath -Destination (Join-Path $root 'plan.json')
    Copy-Item -LiteralPath $cfg -Destination (Join-Path $root 'instance.cfg.before')
    Copy-Item -LiteralPath $options -Destination (Join-Path $root 'options.before.txt')
    $selected = PackSelection $options
    $globalCfg = Join-Path ([string]$plan.prismRoot) 'prismlauncher.cfg'
    $java = ConfigValue $cfg 'JavaPath'
    if ((ConfigValue $cfg 'OverrideJavaLocation') -ne 'true' -or -not $java) { $java = ConfigValue $globalCfg 'JavaPath' }
    if (-not $java -or -not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Configured Java path cannot be verified' }
    $release = Join-Path (Split-Path (Split-Path $java -Parent) -Parent) 'release'
    $javaVersion = [regex]::Match([IO.File]::ReadAllText($release), '(?m)^JAVA_VERSION="([^"]+)"').Groups[1].Value
    if ($javaVersion -ne [string]$plan.expectedJavaVersion) { throw "Unexpected configured Java version $javaVersion; no silent JVM migration" }
    $baseArgs = ConfigValue $cfg 'JvmArgs'
    if ((ConfigValue $cfg 'OverrideJavaArgs') -ne 'true') { $baseArgs = ConfigValue $globalCfg 'JvmArgs' }
    $keys = @('experimentalDecocraftCornerRotationReuseV2','ferriteCoreQuadCapacity','sodiumAxisQuadFlags','generatedItemLayerDeltaHoist')
    # Preserve normal production switches and user runtime/GC tuning.
    $replaceKeys = ($keys + @('generatedItemDirectBake') | ForEach-Object { [regex]::Escape($_) }) -join '|'
    $baseArgs = [regex]::Replace($baseArgs, '(?<!\S)-Dboot_optim\.(' + $replaceKeys + '|profile[A-Za-z0-9_.]*|verify[A-Za-z0-9_.]*|benchmark\.[A-Za-z0-9_.]*)(?:=[^\s]*)?(?=\s|$)', '').Trim()
    if ($baseArgs -match 'StartFlightRecording|agentlib|javaagent|Xlog:|\-Dboot_optim\.experiment') { throw 'Unexpected tracing or another experiment in baseline; prepare an explicit timed baseline first' }
    $modes = if ($comparison -eq 'inprocess') { @('inprocess') } elseif ($comparison -eq 'combined-abba') { @('control-1','combined-1','combined-2','control-2') } else { @('control','decocraft-v2','ferrite-capacity','sodium-axis','layer-delta') }
    $modes = @($modes)
    $summary.status = 'running'
    Save $summary (Join-Path $root 'summary.json')
    foreach ($index in 0..($modes.Count - 1)) {
        $name = $modes[$index]
        $runId = ([string]$plan.campaignId) + '-' + $name
        $dir = Join-Path $root $name
        New-Item -ItemType Directory -Path $dir | Out-Null
        $result = [ordered]@{name=$name; valid=$false; issues=@(); origin='jvm_uptime'; endpoint='main_menu_presented_after_initial_reload'; startupMs=$null; initialReloadCompleteMs=$null; menuReloadMs=$null; gcCount=$null; gcMs=$null; jarSha256=$plan.jarSha256; javaVersion=$javaVersion; restored=$false}
        $switches = @('-Dboot_optim.profileStartup=true','-Dboot_optim.benchmark.expressSweep=true','-Dboot_optim.generatedItemDirectBake=true')
        if ($comparison -eq 'inprocess') { $switches = @('-Dboot_optim.profileStartup=true','-Dboot_optim.benchmark.inProcessCandidates=true','-Dboot_optim.generatedItemDirectBake=true') }
        $switches += '-Dboot_optim.benchmark.expressMenuReloads=' + $(if ($plan.includeMenuReload) { '1' } else { '0' })
        foreach ($k in 0..3) { $enabled = if (($comparison -eq 'inprocess') -or ($comparison -eq 'combined-abba' -and $name.StartsWith('combined-')) -or ($comparison -eq 'individual' -and $index -eq $k + 1)) { 'true' } else { 'false' }; $switches += '-Dboot_optim.' + $keys[$k] + '=' + $enabled }
        $sweepArgs = @{
            RunId=$runId; InstanceRoot=[string]$plan.instanceRoot; PrismExe=[string]$plan.prismExe;
            PrismRoot=[string]$plan.prismRoot; InstanceId=[string]$plan.instanceId;
            InteractiveUser=[string]$plan.interactiveUser; ArtifactJar=[string]$plan.artifactJar;
            ExpectedJarSha256=[string]$plan.jarSha256; JvmArgs=($baseArgs + ' ' + ($switches -join ' ')).Trim();
            RequiredJvmArg=$switches; ForbiddenJvmArg=@('-Dboot_optim.benchmark.exitOnTitle=true');
            ExpectedJavaExe=$java; TimeoutSeconds=$(if($comparison -eq 'inprocess'){14400}else{1200}); StateRoot=(Join-Path $root 'state')
        }
        $stateFile = Join-Path $sweepArgs.StateRoot ($runId + '/state.json')
        try {
            if ((PackSelection $options) -ne $selected) { throw 'Resource-pack selection drift before run' }
            $pre = (& $tx -Action Preflight @sweepArgs | Out-String | ConvertFrom-Json)
            if ($pre.status -ne 'ok') { throw 'Transaction preflight failed' }
            Save $pre (Join-Path $dir 'preflight.json')
            Copy-Item -LiteralPath $options -Destination (Join-Path $dir 'options.before.txt')
            # Rotate only named log files inside the resolved instance. Keep their bytes as evidence.
            foreach ($file in @('latest.log','bootoptim-startup.log')) {
                $live = [IO.Path]::GetFullPath((Join-Path $game ('logs/' + $file)))
                if (-not $live.StartsWith(([IO.Path]::GetFullPath($game).TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Log path escaped instance' }
                if (Test-Path -LiteralPath $live) { Move-Item -LiteralPath $live -Destination (Join-Path $dir ($file + '.previous')) }
            }
            & $tx -Action Stage @sweepArgs | Out-Null
            $dispatch = (& $tx -Action Run -RunId $runId -StateRoot $sweepArgs.StateRoot | Out-String | ConvertFrom-Json)
            if ($dispatch.status -ne 'dispatched') { throw 'Interactive game was not dispatched' }
            $deadline = [DateTime]::UtcNow.AddSeconds($sweepArgs.TimeoutSeconds + 350)
            do {
                Start-Sleep -Seconds 3
                $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
                if ($state.phase -in @('finished','invalid')) { break }
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($state.phase -ne 'finished' -or -not $state.valid) { throw "Invalid or timed-out transaction: $($state.phase) $($state.reason)" }
            Copy-Item -LiteralPath $stateFile -Destination (Join-Path $dir 'transaction.finished.json')
            Copy-Item -LiteralPath $options -Destination (Join-Path $dir 'options.after.txt')
            foreach ($file in @('latest.log','bootoptim-startup.log')) {
                $live = Join-Path $game ('logs/' + $file)
                if (Test-Path -LiteralPath $live) { Copy-Item -LiteralPath $live -Destination (Join-Path $dir $file) }
            }
            $log = [IO.File]::ReadAllText((Join-Path $dir 'latest.log'))
            if ($comparison -eq 'inprocess') {
                $menu = [regex]::Matches($log, 'BOOTOPTIM_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms=(\d+)')
                $done = [regex]::Matches($log, 'BOOTOPTIM_SWEEP stage=initial_reload_complete success=true uptime_ms=(\d+)')
                $finish = [regex]::Matches($log, 'BOOTOPTIM_TRIAL stage=finished steps=21 measured=16 origin=reload_invocation endpoint=future_completion')
                if ($menu.Count -ne 1 -or $done.Count -ne 1 -or $finish.Count -ne 1 -or ([regex]::Matches($log,'BOOTOPTIM_TRIAL stage=complete').Count -ne 21) -or ([regex]::Matches($log,'Reloading ResourceManager:').Count -ne 22)) { throw 'Incomplete within-JVM trial; offline checker must also pass' }
                if ($log -match 'success=false|BOOTOPTIM_TRIAL stage=invalid|Caught error loading resourcepacks|Direct generated-item bake failed open|BOOTOPTIM_SWEEP stage=invalid') { throw 'Failed trial or resource fallback' }
                if ((PackSelection $options) -ne $selected) { throw 'Resource packs changed during trial' }
                if ([long]$done[0].Groups[1].Value -gt [long]$menu[0].Groups[1].Value) { throw 'Initial menu predates initial generation completion' }
                $result.startupMs = [long]$menu[0].Groups[1].Value
                $result.initialReloadCompleteMs = [long]$done[0].Groups[1].Value
                $result.endpoint='initial_menu_after_complete_generation_plus_21_reload_trials'
            } else {
            $menu = [regex]::Matches($log, 'BOOTOPTIM_SWEEP stage=main_menu_presented origin=jvm_uptime uptime_ms=(\d+)')
            $done = [regex]::Matches($log, 'BOOTOPTIM_SWEEP stage=initial_reload_complete success=true uptime_ms=(\d+)')
            $finish = [regex]::Matches($log, 'BOOTOPTIM_SWEEP stage=finished origin=jvm_uptime uptime_ms=(\d+) gc_count=(\d+) gc_ms=(\d+)')
            if ($menu.Count -ne 1 -or $done.Count -ne 1 -or $finish.Count -ne 1) { throw 'Missing or ambiguous completion/presentation markers' }
            $repeat = [regex]::Matches($log, 'BOOTOPTIM_SWEEP stage=menu_reload_complete success=true wall_ns=(\d+)')
            if ($plan.includeMenuReload) {
                if ($repeat.Count -ne 1) { throw 'Missing or ambiguous menu reload endpoint' }
                $result.menuReloadMs = [long]$repeat[0].Groups[1].Value / 1e6
            } elseif ($repeat.Count -ne 0) { throw 'Unexpected extra reload' }
            if ($log -match 'Caught error loading resourcepacks|Direct generated-item bake failed open|BOOTOPTIM_SWEEP stage=invalid') { throw 'Reload fallback or invalid benchmark marker' }
            if ((PackSelection $options) -ne $selected) { throw 'Resource packs changed during run' }
            if ([long]$done[0].Groups[1].Value -gt [long]$menu[0].Groups[1].Value) { throw 'Menu endpoint predates complete initial reload' }
            $result.startupMs = [long]$menu[0].Groups[1].Value
            $result.initialReloadCompleteMs = [long]$done[0].Groups[1].Value
            $result.gcCount = [long]$finish[0].Groups[2].Value
            $result.gcMs = [long]$finish[0].Groups[3].Value
            }
            $result.valid = $true
        } catch {
            $result.issues += $_.Exception.Message
            # Collect partial logs/crashes before recovery, even for failed starts.
            foreach ($file in @('latest.log','bootoptim-startup.log')) {
                $live = Join-Path $game ('logs/' + $file)
                if (Test-Path -LiteralPath $live) { Copy-Item -LiteralPath $live -Destination (Join-Path $dir $file) -Force }
            }
        } finally {
            if (Test-Path -LiteralPath $stateFile) {
                try {
                    $restore = (& $tx -Action Recover -RunId $runId -StateRoot $sweepArgs.StateRoot -ForceStopOwned | Out-String | ConvertFrom-Json)
                    $result.restored = $restore.status -eq 'restored'
                } catch { $result.valid=$false; $result.issues += 'Recovery failed: ' + $_.Exception.Message }
            }
            Save $result (Join-Path $dir 'result.json')
            $summary.runs += $result
            Save $summary (Join-Path $root 'summary.json')
        }
        if (-not $result.valid -or -not $result.restored) { throw "Campaign stopped at $name; inspect result and recovery before continuing" }
        Start-Sleep -Seconds 15
    }
    $summary.status = 'finished'
} catch { $summary.status='failed'; $summary.error=$_.Exception.Message }
finally {
    $summary.finishedUtc=[DateTime]::UtcNow.ToString('o')
    Save $summary (Join-Path $root 'summary.json')
    if ($lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath }
}
if ($summary.status -ne 'finished') { exit 1 }
