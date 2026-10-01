[CmdletBinding()]
param(
    [ValidateSet('Prepare','Start','Status','Collect')][string]$Action='Prepare',
    [ValidatePattern('^\d{1,3}(\.\d{1,3}){3}$')][string]$LaptopIp='192.168.1.139',
    [string]$CampaignId='ferrite-presize-20261001',
    [string]$LocalRoot='C:/BootOptimBench/artifacts/ferrite-presize-20261001',
    [string]$ArtifactJar='',
    [ValidatePattern('^[a-f0-9]{40}$')][string]$ArtifactSourceSha,
    [ValidateSet('ferrite-presize')][string]$ComparisonMode='ferrite-presize',
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$ExpectedJavaVersion='21.0.9'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($ArtifactJar)) { $ArtifactJar=Join-Path $PSScriptRoot '../../bootstrap/build/libs/boot_optim-bootstrap-0.1.0.jar' }
if ($CampaignId -notmatch '^[a-z0-9-]{1,40}$') { throw 'Invalid campaign identifier' }
$LocalRoot=[IO.Path]::GetFullPath($LocalRoot)
$bundle=Join-Path $LocalRoot 'bundle'
$remote='C:/BootOptimBench/'+$CampaignId
$hostName='wachi@'+$LaptopIp
$key='C:/Users/Wachii/.ssh/bootoptim-laptop-ed25519-v2'
$sshArgs=@('-o','ConnectTimeout=8','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-i',$key)
function Remote([string]$script) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    & ssh @sshArgs $hostName "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed: $LASTEXITCODE" }
}
switch ($Action) {
    'Prepare' {
        # Deliberately no SSH, staging or laptop launch in this action.
        if (-not $ArtifactSourceSha) { throw 'Declare the exact tested push source SHA' }
        if (Test-Path -LiteralPath $bundle) { throw 'Bundle already exists; use a fresh LocalRoot' }
        New-Item -ItemType Directory -Path $bundle -Force | Out-Null
        Copy-Item -LiteralPath $ArtifactJar -Destination (Join-Path $bundle 'candidate.jar')
        foreach ($file in @('remote_express_sweep.ps1','remote_laptop_transaction.ps1','remote_laptop_interactive_run.ps1')) {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $bundle
        }
        $sha=(Get-FileHash -LiteralPath (Join-Path $bundle 'candidate.jar') -Algorithm SHA256).Hash
        $plan=[ordered]@{
            schema=2; artifactSourceSha=$ArtifactSourceSha; timingScope='growth_cleanup'; comparisonMode=$ComparisonMode; campaignId=$CampaignId; outputRoot=$remote+'/results';
            artifactJar=$remote+'/candidate.jar'; jarSha256=$sha;
            computerName='DESKTOP-8D4B389'; interactiveUser='DESKTOP-8D4B389\wachi';
            instanceRoot='C:/BootOptimBench/prism/instances/BootOptimBench';
            prismRoot='C:/BootOptimBench/prism'; prismExe='C:/BootOptimBench/prism/prismlauncher.exe';
            instanceId='BootOptimBench'; expectedJavaVersion=$ExpectedJavaVersion;
            origin='physical_laptop'; worldEntry=$false; includeMenuReload=$false
        }
        [IO.File]::WriteAllText((Join-Path $bundle 'plan.json'),($plan|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        [pscustomobject]@{status='prepared_locally'; runs=1; jarSha256=$sha; plan=(Join-Path $bundle 'plan.json')}|ConvertTo-Json
    }
    'Start' {
        $plan=Get-Content -LiteralPath (Join-Path $bundle 'plan.json') -Raw|ConvertFrom-Json
        if ($plan.campaignId -ne $CampaignId) { throw 'Plan/campaign mismatch' }
        if ((Get-FileHash -LiteralPath (Join-Path $bundle 'candidate.jar') -Algorithm SHA256).Hash -ne $plan.jarSha256) { throw 'Prepared JAR changed' }
        Remote "if (Test-Path -LiteralPath '$remote') { throw 'Remote campaign already exists' }; New-Item -ItemType Directory -Path '$remote' | Out-Null"
        $files=@(Get-ChildItem -LiteralPath $bundle -File | Select-Object -ExpandProperty FullName)
        & scp @sshArgs @files "${hostName}:${remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'Bundle transfer failed; no game dispatched' }
        # Task Scheduler survives disconnects; the controller does not depend on this chat or SSH.
        Remote @"
`$ErrorActionPreference='Stop'
`$name='BootOptimSweep-$CampaignId'
if (Get-ScheduledTask -TaskName `$name -ErrorAction SilentlyContinue) { throw 'Controller task already exists' }
`$args='-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$remote/remote_express_sweep.ps1" -PlanPath "$remote/plan.json"'
`$act=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument `$args
`$principal=New-ScheduledTaskPrincipal -UserId 'DESKTOP-8D4B389\wachi' -LogonType Interactive -RunLevel Limited
`$settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 6)
Register-ScheduledTask -TaskName `$name -Action `$act -Principal `$principal -Settings `$settings | Out-Null
Start-ScheduledTask -TaskName `$name
[pscustomobject]@{status='dispatched';task=`$name;results='$remote/results';runs=1}|ConvertTo-Json
"@
    }
    'Status' {
        Remote "if (Test-Path -LiteralPath '$remote/results/summary.json') { Get-Content -LiteralPath '$remote/results/summary.json' -Raw } else { (Get-ScheduledTask -TaskName 'BootOptimSweep-$CampaignId').State.ToString() }"
    }
    'Collect' {
        $state=Remote "Get-Content -LiteralPath '$remote/results/summary.json' -Raw" | Out-String | ConvertFrom-Json
        if ($state.status -notin @('finished','failed')) { throw 'Campaign is still running' }
        $dest=Join-Path $LocalRoot 'results'
        if (Test-Path -LiteralPath $dest) { throw 'Local collected results already exist' }
        New-Item -ItemType Directory -Path $dest | Out-Null
        & scp @sshArgs -r "${hostName}:${remote}/results/." $dest
        if ($LASTEXITCODE -ne 0) { throw 'Results transfer failed; evidence remains on laptop' }
        if ($state.comparisonMode -ne 'ferrite-presize') { throw 'Unsupported campaign' }
        python (Join-Path $PSScriptRoot 'check_resource_selection.py') --reference (Join-Path $dest 'options.before.txt') --options (Join-Path $dest 'ferrite-presize/options.after.txt') --log (Join-Path $dest 'ferrite-presize/latest.log')
        if ($LASTEXITCODE -ne 0) { throw 'Resource selection drift in Ferrite trial' }
        python (Join-Path $PSScriptRoot 'check_ferrite_phase.py') (Join-Path $dest 'ferrite-presize/latest.log') --output (Join-Path $dest 'ferrite-phase-result.json')
        if ($LASTEXITCODE -ne 0) { throw 'Collected campaign did not pass offline validity gates' }
    }
}
