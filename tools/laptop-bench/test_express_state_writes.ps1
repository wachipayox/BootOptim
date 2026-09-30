param([string]$ScriptsRoot=$PSScriptRoot)
$ErrorActionPreference='Stop'
$Utf8=New-Object System.Text.UTF8Encoding($false)
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('bootoptim-state-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
foreach($file in @('remote_laptop_transaction.ps1','remote_laptop_interactive_run.ps1','remote_express_sweep.ps1')) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw "Parse failed: $file"}
    $fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Save'},$true)
    . ([scriptblock]::Create($fn.Extent.Text))
    $StateFile=Join-Path $testRoot ($file+'.json')
    for($i=0;$i -lt 100;$i++) {
        if($file -eq 'remote_laptop_interactive_run.ps1'){Save @{sequence=$i}}
        else {Save @{sequence=$i} $StateFile}
        if((Get-Content -LiteralPath $StateFile -Raw|ConvertFrom-Json).sequence -ne $i){throw "Lost state: $file"}
    }
    Write-Output "PASS ${file}: 100 successive atomic writes"
}
$source=[IO.File]::ReadAllText((Join-Path $ScriptsRoot 'remote_laptop_transaction.ps1'))
if(([regex]::Matches($source,'-force:\$ForceStopOwned')).Count -ne 2){throw 'Recovery must bind both force switches by name'}
Write-Output 'PASS recovery force switches bound by name'
