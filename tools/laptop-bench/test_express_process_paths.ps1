$ErrorActionPreference='Stop'
function Get-CimInstance { param($Filter) $script:fixtureProcesses }
$script:fixtureProcesses=@(
 [pscustomobject]@{ProcessId=1;CommandLine='javaw -Djava.library.path=C:/Bench/prism/instances/Test/natives'},
 [pscustomobject]@{ProcessId=2;CommandLine='javaw -Djava.library.path=C:\Bench\prism\instances\Test\natives'},
 [pscustomobject]@{ProcessId=3;CommandLine='javaw -Djava.library.path=c:/bench/prism/instances/test/natives'},
 [pscustomobject]@{ProcessId=4;CommandLine='javaw -Djava.library.path=C:/Bench/prism/instances/Other/natives'},
 [pscustomobject]@{ProcessId=5;CommandLine=$null}
)
foreach($name in @('remote_laptop_transaction.ps1','remote_laptop_interactive_run.ps1')) {
 $tokens=$null;$errors=$null
 $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
 if($errors.Count){throw "Syntax errors in $name"}
 $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name-eq'Target-Java'},$true)
 Invoke-Expression $function.Extent.Text
 foreach($root in @('C:\Bench\prism\instances\Test','C:/Bench/prism/instances/Test')) {
  $actual=@(Target-Java "$root/.minecraft" $root | ForEach-Object ProcessId)
  if(($actual -join ',')-ne'1,2,3'){throw "Incorrect path matches in $name`: $actual"}
 }
 Write-Output "PASS $name forward/backslash paths, case, unrelated and absent command lines"
}
