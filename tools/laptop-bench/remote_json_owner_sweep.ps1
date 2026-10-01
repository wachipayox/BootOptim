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
function Collect-RunLog($gameRoot, $destination, $sinceUtc) {
    # Owned JVM has exited. Exclude debug archives (duplicate events) and older processes.
    $builder = [Text.StringBuilder]::new()
    $archives = @(Get-ChildItem -LiteralPath (Join-Path $gameRoot 'logs') -Filter '*.log.gz' -File |
        Where-Object {$_.Name -match '^\d{4}-\d{2}-\d{2}-\d+\.log\.gz$' -and $_.LastWriteTimeUtc -ge [DateTime]::Parse($sinceUtc).ToUniversalTime()} |
        Sort-Object LastWriteTimeUtc,Name)
    foreach ($archive in $archives) {
        $inputFile = [IO.File]::OpenRead($archive.FullName)
        $gzip = [IO.Compression.GZipStream]::new($inputFile, [IO.Compression.CompressionMode]::Decompress)
        $reader = [IO.StreamReader]::new($gzip, [Text.Encoding]::UTF8)
        try { [void]$builder.AppendLine($reader.ReadToEnd()) }
        finally {$reader.Dispose();$gzip.Dispose();$inputFile.Dispose()}
    }
    [void]$builder.AppendLine([IO.File]::ReadAllText((Join-Path $gameRoot 'logs/latest.log')))
    [IO.File]::WriteAllText($destination,$builder.ToString(),[Text.UTF8Encoding]::new($false))
}
$game = Join-Path ([string]$plan.instanceRoot) '.minecraft'
$cfg = Join-Path ([string]$plan.instanceRoot) 'instance.cfg'
$options = Join-Path $game 'options.txt'
$tx = Join-Path $PSScriptRoot 'remote_laptop_transaction.ps1'
$lockPath = Join-Path $game '.bootoptim-express-sweep.lock'
$lock = $null
$comparison = if ($plan.PSObject.Properties.Name -contains 'comparisonMode') { [string]$plan.comparisonMode } else { '' }
if ($comparison -ne 'decocraft-json' -or $plan.timingScope -ne 'json_read_parse_close' -or $plan.worldEntry -or $plan.includeMenuReload -or $plan.artifactSourceSha -notmatch '^[a-f0-9]{40}$') { throw 'Invalid bounded Decocraft JSON plan' }
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
    $keys = @('experimentalDecocraftCornerRotationReuseV2','ferriteCoreQuadCapacity','generatedItemLayerDeltaHoist','ferriteCoreQuadPresize')
    # Preserve normal production switches and user runtime/GC tuning.
    $replaceKeys = ($keys | ForEach-Object { [regex]::Escape($_) }) -join '|'
    $baseArgs = [regex]::Replace($baseArgs, '(?<!\S)-Dboot_optim\.(' + $replaceKeys + '|profile[A-Za-z0-9_.]*|verify[A-Za-z0-9_.]*|benchmark\.[A-Za-z0-9_.]*)(?:=[^\s]*)?(?=\s|$)', '').Trim()
    if ($baseArgs -match 'StartFlightRecording|agentlib|javaagent|Xlog:|\-Dboot_optim\.experiment') { throw 'Unexpected tracing or another experiment in baseline; prepare an explicit timed baseline first' }
    $modes = @('decocraft-json')
    $summary.status = 'running'
    Save $summary (Join-Path $root 'summary.json')
    foreach ($index in 0..($modes.Count - 1)) {
        $name = $modes[$index]
        $runId = ([string]$plan.campaignId) + '-' + $name
        $dir = Join-Path $root $name
        New-Item -ItemType Directory -Path $dir | Out-Null
        $result = [ordered]@{name=$name; valid=$false; issues=@(); origin='jvm_uptime'; endpoint='main_menu_presented_after_initial_reload'; startupMs=$null; initialReloadCompleteMs=$null; menuReloadMs=$null; gcCount=$null; gcMs=$null; jarSha256=$plan.jarSha256; javaVersion=$javaVersion; restored=$false}
        $switches = @('-Dboot_optim.profileStartup=true','-Dboot_optim.benchmark.decocraftJsonTrials=true','-Dboot_optim.profileDecocraftJsonOwner=true','-Dboot_optim.experimentDecocraftModelArchiveBatch=true','-Dboot_optim.experimentDecocraftModelArchiveBatchVerify=false','-Dboot_optim.benchmark.expressDeclineOptionalWelcome=true')
        $sweepArgs = @{
            RunId=$runId; InstanceRoot=[string]$plan.instanceRoot; PrismExe=[string]$plan.prismExe;
            PrismRoot=[string]$plan.prismRoot; InstanceId=[string]$plan.instanceId;
            InteractiveUser=[string]$plan.interactiveUser; ArtifactJar=[string]$plan.artifactJar;
            ExpectedJarSha256=[string]$plan.jarSha256; JvmArgs=($baseArgs + ' ' + ($switches -join ' ')).Trim();
            RequiredJvmArg=$switches; ForbiddenJvmArg=@('-Dboot_optim.benchmark.exitOnTitle=true');
            ExpectedJavaExe=$java; TimeoutSeconds=14400; StateRoot=(Join-Path $root 'state')
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
            Collect-RunLog $game (Join-Path $dir 'latest.log') $state.launchStartedUtc
            $log = [IO.File]::ReadAllText((Join-Path $dir 'latest.log'))
            if ($comparison -eq 'decocraft-json') {
                $menu = [regex]::Matches($log, 'BOOTOPTIM_JSON_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms=(\d+)')
                $done = [regex]::Matches($log, 'BOOTOPTIM_JSON_TRIAL stage=initial_complete success=true')
                $finish = [regex]::Matches($log, 'BOOTOPTIM_JSON_TRIAL stage=finished observations=4 controls=2 candidates=2 primers=4')
                if ($menu.Count -ne 1 -or $done.Count -ne 1 -or $finish.Count -ne 1 -or ([regex]::Matches($log,'BOOTOPTIM_JSON_TRIAL stage=complete').Count -ne 8) -or ([regex]::Matches($log,'Reloading ResourceManager:').Count -ne 9)) { throw 'Incomplete Decocraft JSON owner trial; offline checker must also pass' }
                if ($log -match 'success=false|BOOTOPTIM_JSON_TRIAL stage=invalid|Caught error loading resourcepacks|Direct generated-item bake failed open') { throw 'Failed Decocraft JSON trial or resource fallback' }
                if ((PackSelection $options) -ne $selected) { throw 'Resource packs changed during owner trial' }
                $result.startupMs=[long]$menu[0].Groups[1].Value
                $result.endpoint='reload_invocation_to_future_completion'; $result.origin='reload_invocation'
                if ($log -match 'BOOTOPTIM_FERRITE_QUAD_CAPACITY |BOOTOPTIM_FERRITE_PRESIZE ') { throw 'Retired mechanism leaked into JSON trial' }
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
