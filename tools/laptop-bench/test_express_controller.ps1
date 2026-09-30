# Offline transaction doubles: no SSH, Prism, Java or game launch.
$ErrorActionPreference='Stop'
$sandbox=Join-Path ([IO.Path]::GetTempPath()) ('bootoptim-sweep-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sandbox | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'remote_express_sweep.ps1') -Destination $sandbox
$mock=@'
param($Action,$RunId,$InstanceRoot,$PrismExe,$PrismRoot,$InstanceId,$InteractiveUser,$ArtifactJar,$ExpectedJarSha256,$JvmArgs,$RequiredJvmArg,$ForbiddenJvmArg,$ExpectedJavaExe,$TimeoutSeconds,$StateRoot,[switch]$ForceStopOwned)
$ErrorActionPreference='Stop'
$dir=Join-Path $StateRoot $RunId
$file=Join-Path $dir 'state.json'
if (-not $InstanceRoot -and (Test-Path -LiteralPath $file)) { $InstanceRoot=(Get-Content -LiteralPath $file -Raw|ConvertFrom-Json).instanceRoot }
$game=Join-Path $InstanceRoot '.minecraft'
switch($Action) {
 'Preflight' { @{status='ok'} | ConvertTo-Json }
 'Stage' {
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $InstanceRoot 'instance.cfg') -Destination (Join-Path $dir 'cfg.before')
  Set-Content -LiteralPath (Join-Path $InstanceRoot 'instance.cfg') -Value $JvmArgs
  @{phase='staged';valid=$false;reason=$null;instanceRoot=$InstanceRoot} | ConvertTo-Json | Set-Content -LiteralPath $file
 }
 'Run' {
  $mode=[regex]::Match($RunId,'(control|decocraft-v2|ferrite-capacity|sodium-axis|layer-delta)$').Value
  $expected=@{control='';'decocraft-v2'='experimentalDecocraftCornerRotationReuseV2';'ferrite-capacity'='ferriteCoreQuadCapacity';'sodium-axis'='sodiumAxisQuadFlags';'layer-delta'='generatedItemLayerDeltaHoist'}[$mode]
  $args=Get-Content -LiteralPath (Join-Path $InstanceRoot 'instance.cfg') -Raw
  $enabled=@([regex]::Matches($args,'-Dboot_optim\.(experimentalDecocraftCornerRotationReuseV2|ferriteCoreQuadCapacity|sodiumAxisQuadFlags|generatedItemLayerDeltaHoist)=true'))
  if (($mode -eq 'control' -and $enabled.Count -ne 0) -or ($mode -ne 'control' -and ($enabled.Count -ne 1 -or $enabled[0].Groups[1].Value -ne $expected))) { throw 'Wrong independent flag matrix' }
  if ($args.TrimStart().StartsWith('"') -or $args.Contains('-Dboot_optim.benchmark.exitOnTitle=true')) { throw 'Quoted QSettings baseline or stale exitOnTitle leaked into effective JVM args' }
  if (-not $args.Contains('-XX:+UseG1GC')) { throw 'User GC baseline was lost' }
  New-Item -ItemType Directory -Path (Join-Path $game 'logs') -Force | Out-Null
  @('BOOTOPTIM_SWEEP stage=initial_reload_complete success=true uptime_ms=1000','BOOTOPTIM_SWEEP stage=main_menu_presented origin=jvm_uptime uptime_ms=1200','BOOTOPTIM_SWEEP stage=finished origin=jvm_uptime uptime_ms=3200 gc_count=2 gc_ms=3') | Set-Content -LiteralPath (Join-Path $game 'logs/latest.log')
  Add-Content -LiteralPath (Join-Path $game 'logs/latest.log') -Value 'Reloading ResourceManager: vanilla, file/test.zip'
  if ($args.Contains('-Dboot_optim.benchmark.expressMenuReloads=1')) {
   Add-Content -LiteralPath (Join-Path $game 'logs/latest.log') -Value @('BOOTOPTIM_SWEEP stage=menu_reload_complete success=true wall_ns=2000000','Reloading ResourceManager: vanilla, file/test.zip')
  }
  $fail=$env:BOOTOPTIM_SWEEP_TEST_FAIL -eq $mode
  @{instanceRoot=$InstanceRoot;phase=$(if($fail){'invalid'}else{'finished'});valid=(-not $fail);reason=$(if($fail){'fixture_failure'}else{$null});javaCreationDate='2026-09-30T00:00:00';effectiveCommandLineSha256='fixture'} | ConvertTo-Json | Set-Content -LiteralPath $file
  @{status='dispatched'} | ConvertTo-Json
 }
 'Recover' {
  Copy-Item -LiteralPath (Join-Path $dir 'cfg.before') -Destination (Join-Path $InstanceRoot 'instance.cfg') -Force
  @{status='restored'} | ConvertTo-Json
 }
}
'@
[IO.File]::WriteAllText((Join-Path $sandbox 'remote_laptop_transaction.ps1'),$mock)
# Shadow sleeps only in the test parent scope; the production script is unchanged.
function Start-Sleep { param($Seconds,$Milliseconds) }
foreach($case in @('success','failure','menu-repeat','quoted-config')) {
 $fail=if($case -eq 'failure'){'decocraft-v2'}else{''}
 $caseRoot=Join-Path $sandbox $case
 $instance=Join-Path $caseRoot 'instance'
 $javaRoot=Join-Path $caseRoot 'jdk'
 New-Item -ItemType Directory -Path (Join-Path $instance '.minecraft'),(Join-Path $javaRoot 'bin') -Force | Out-Null
 $java=Join-Path $javaRoot 'bin/javaw.exe'
 Set-Content -LiteralPath $java -Value 'fake'
 Set-Content -LiteralPath (Join-Path $javaRoot 'release') -Value 'JAVA_VERSION="25.0.4"'
 $cfg=Join-Path $instance 'instance.cfg'
 $fixtureArgs = if ($case -eq 'quoted-config') { 'JvmArgs="-XX:+UseG1GC -Dboot_optim.sodiumAxisQuadFlags=true -Dboot_optim.benchmark.exitOnTitle=true"' } else { 'JvmArgs=-XX:+UseG1GC -Dboot_optim.sodiumAxisQuadFlags=true' }
 @('OverrideJavaLocation=true',('JavaPath='+$java),'OverrideJavaArgs=true',$fixtureArgs) | Set-Content -LiteralPath $cfg
 $original=[IO.File]::ReadAllText($cfg)
 Set-Content -LiteralPath (Join-Path $instance '.minecraft/options.txt') -Value 'resourcePacks:["vanilla","file/test.zip"]'
 $plan=@{outputRoot=(Join-Path $caseRoot 'results');campaignId='fixture';computerName=$env:COMPUTERNAME;instanceRoot=$instance;prismRoot=$caseRoot;prismExe='fake';instanceId='fixture';interactiveUser='fixture';artifactJar='fake';jarSha256='fixture';expectedJavaVersion='25.0.4';includeMenuReload=($case -eq 'menu-repeat')}
 $planFile=Join-Path $caseRoot 'plan.json'
 $plan | ConvertTo-Json | Set-Content -LiteralPath $planFile
 $env:BOOTOPTIM_SWEEP_TEST_FAIL=$fail
 & (Join-Path $sandbox 'remote_express_sweep.ps1') -PlanPath $planFile
 $summary=Get-Content -LiteralPath (Join-Path $plan.outputRoot 'summary.json') -Raw | ConvertFrom-Json
 if ([IO.File]::ReadAllText($cfg) -ne $original) { throw 'Original config was not restored' }
 if (Test-Path -LiteralPath (Join-Path $instance '.minecraft/.bootoptim-express-sweep.lock')) { throw 'Owned lock leaked' }
 if ($fail) {
  if ($summary.status -ne 'failed' -or $summary.runs.Count -ne 2 -or -not $summary.runs[1].restored) { throw 'Fail-fast recovery failed' }
 } else {
  if ($summary.status -ne 'finished' -or $summary.runs.Count -ne 5 -or @($summary.runs | Where-Object {-not $_.valid -or -not $_.restored}).Count) { throw 'Five-run evidence/restoration failed' }
  if ($case -eq 'menu-repeat' -and @($summary.runs | Where-Object {$_.menuReloadMs -ne 2}).Count) { throw 'Optional repeat timings missing' }
 }
 Write-Output "PASS $case evidence, flag matrix, config restoration and lock cleanup"
}
Remove-Item Env:BOOTOPTIM_SWEEP_TEST_FAIL
Write-Output ('Offline fixture retained at '+$sandbox)
