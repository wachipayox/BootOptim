# Offline transaction double: no SSH, Prism or game; exercise PS5 config/recovery.
$ErrorActionPreference='Stop'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('bootoptim-presize-controller-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$controller=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'remote_express_sweep.ps1'))
$controller=$controller.Replace('Start-Sleep -Seconds 3','Start-Sleep -Milliseconds 1').Replace('Start-Sleep -Seconds 15','Start-Sleep -Milliseconds 1')
[IO.File]::WriteAllText((Join-Path $testRoot 'remote_express_sweep.ps1'),$controller)
$mock=@'
param($Action,$RunId,$InstanceRoot,$PrismExe,$PrismRoot,$InstanceId,$InteractiveUser,$ArtifactJar,$ExpectedJarSha256,$JvmArgs,$RequiredJvmArg,$ForbiddenJvmArg,$ExpectedJavaExe,$TimeoutSeconds,$StateRoot,[switch]$ForceStopOwned)
$ErrorActionPreference='Stop'
$dir=Join-Path $StateRoot $RunId
$stateFile=Join-Path $dir 'state.json'
if(-not $InstanceRoot){$InstanceRoot=(Get-Content -LiteralPath $stateFile -Raw|ConvertFrom-Json).instanceRoot}
$game=Join-Path $InstanceRoot '.minecraft'
$cfg=Join-Path $InstanceRoot 'instance.cfg'
switch($Action){
 'Preflight'{@{status='ok'}|ConvertTo-Json}
 'Stage'{
  if($TimeoutSeconds -ne 14400){throw 'Wrong deadline'}
  foreach($flag in @('-Dboot_optim.benchmark.ferritePhaseTrials=true','-Dboot_optim.ferriteCoreQuadPresize=true','-Dboot_optim.benchmark.ferriteInsertionTiming=false','-Dboot_optim.generatedItemDirectBake=true')){if(-not $JvmArgs.Contains($flag) -or $flag -notin $RequiredJvmArg){throw 'Missing required flag'}}
  if($JvmArgs -match 'ferriteCoreQuadCapacity|generatedItemLayerDeltaHoist|experimentalDecocraft|benchmark.exitOnTitle|ferriteInsertionTiming=true'){throw 'Retired/high-volume/stale flag leaked'}
  if($JvmArgs.TrimStart().StartsWith('"') -or -not $JvmArgs.Contains('-XX:+UseG1GC')){throw 'Quoted baseline/GC tuning damaged'}
  New-Item -ItemType Directory -Path $dir -Force|Out-Null
  Copy-Item -LiteralPath $cfg -Destination (Join-Path $dir 'cfg.before')
  [IO.File]::WriteAllText($cfg,$JvmArgs)
  @{phase='staged';valid=$false;instanceRoot=$InstanceRoot;reason=$null}|ConvertTo-Json|Set-Content -LiteralPath $stateFile
 }
 'Run'{
  $text='BOOTOPTIM_FERRITE_PHASE stage=initial_menu origin=jvm_uptime uptime_ms=1000 timing_scope=growth_cleanup'+"`n"+'BOOTOPTIM_FERRITE_PHASE stage=initial_complete success=true'+"`n"
  for($i=0;$i -lt 9;$i++){$text+='Reloading ResourceManager:'+"`n"}
  for($i=0;$i -lt 8;$i++){$text+='BOOTOPTIM_FERRITE_PHASE stage=complete index='+$i+"`n"}
  if($env:BOOTOPTIM_PRESIZE_TEST_BAD -ne 'true'){$text+='BOOTOPTIM_FERRITE_PHASE stage=finished observations=4 controls=2 candidates=2 primers=4'}
  [IO.File]::WriteAllText((Join-Path $game 'logs/latest.log'),$text)
  @{phase='finished';valid=$true;reason=$null;instanceRoot=$InstanceRoot;launchStartedUtc=[DateTime]::UtcNow.AddMinutes(-1).ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath $stateFile
  @{status='dispatched'}|ConvertTo-Json
 }
 'Recover'{
  if(-not $ForceStopOwned){throw 'Missing bounded recovery'}
  Copy-Item -LiteralPath (Join-Path $dir 'cfg.before') -Destination $cfg -Force
  @{status='restored'}|ConvertTo-Json
 }
}
'@
[IO.File]::WriteAllText((Join-Path $testRoot 'remote_laptop_transaction.ps1'),$mock)
foreach($case in @('success','incomplete')){
 $caseRoot=Join-Path $testRoot $case
 $instance=Join-Path $caseRoot 'instance'
 $bin=Join-Path $caseRoot 'jdk/bin'
 New-Item -ItemType Directory -Path (Join-Path $instance '.minecraft/logs'),$bin -Force|Out-Null
 $java=Join-Path $bin 'javaw.exe'
 [IO.File]::WriteAllText($java,'fixture')
 [IO.File]::WriteAllText((Join-Path $caseRoot 'jdk/release'),'JAVA_VERSION="21.0.9"')
 $cfg=Join-Path $instance 'instance.cfg'
 $original='OverrideJavaLocation=true'+"`n"+'JavaPath='+$java+"`n"+'OverrideJavaArgs=true'+"`n"+'JvmArgs="-XX:+UseG1GC -Xmx6G -Dboot_optim.ferriteCoreQuadCapacity=true -Dboot_optim.benchmark.exitOnTitle=true -Dboot_optim.benchmark.ferriteInsertionTiming=true"'+"`n"
 [IO.File]::WriteAllText($cfg,$original)
 [IO.File]::WriteAllText((Join-Path $instance '.minecraft/options.txt'),'resourcePacks:["vanilla","file/test.zip"]')
 $plan=@{outputRoot=(Join-Path $caseRoot 'results');campaignId='fixture';computerName=$env:COMPUTERNAME;instanceRoot=$instance;prismRoot=$caseRoot;prismExe='fixture';instanceId='fixture';interactiveUser='fixture';artifactJar='fixture';jarSha256='fixture';expectedJavaVersion='21.0.9';comparisonMode='ferrite-presize';timingScope='growth_cleanup';artifactSourceSha=('a'*40);worldEntry=$false;includeMenuReload=$false}
 $planFile=Join-Path $caseRoot 'plan.json'
 $plan|ConvertTo-Json|Set-Content -LiteralPath $planFile
 $env:BOOTOPTIM_PRESIZE_TEST_BAD=($case -eq 'incomplete').ToString().ToLowerInvariant()
 & (Join-Path $testRoot 'remote_express_sweep.ps1') -PlanPath $planFile
 $summary=Get-Content -LiteralPath (Join-Path $plan.outputRoot 'summary.json') -Raw|ConvertFrom-Json
 if([IO.File]::ReadAllText($cfg) -ne $original -or (Test-Path -LiteralPath (Join-Path $instance '.minecraft/.bootoptim-express-sweep.lock'))){throw 'Original config/owned lock not restored'}
 if($summary.runs.Count -ne 1 -or -not $summary.runs[0].restored){throw 'Wrong bounded run or recovery'}
 if($case -eq 'success' -and ($summary.status -ne 'finished' -or -not $summary.runs[0].valid)){throw 'Valid trial rejected'}
 if($case -eq 'incomplete' -and ($summary.status -ne 'failed' -or $summary.runs[0].valid)){throw 'Incomplete trial accepted'}
 Write-Output "PASS $case flag scope, original config restoration, owned lock cleanup"
}
Remove-Item Env:BOOTOPTIM_PRESIZE_TEST_BAD
Write-Output "Fixture evidence retained at $testRoot"
