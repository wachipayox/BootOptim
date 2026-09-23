$ErrorActionPreference='Stop'
# Extract just the detector; never invoke transaction or real process discovery.
foreach($scriptName in @('remote_laptop_transaction.ps1','remote_laptop_interactive_run.ps1')) {
    $tokens=$null;$errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $scriptName),[ref]$tokens,[ref]$errors)
    if($errors){throw "$scriptName does not parse"}
    $fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Target-Java'},$true)
    if(-not$fn){throw "missing Target-Java in $scriptName"}
    . ([scriptblock]::Create($fn.Extent.Text))
    function Get-CimInstance { param($ClassName,$Filter) $script:fakeProcesses }
    $script:fakeProcesses=@(
        [pscustomobject]@{ProcessId=1;CommandLine='java -Djava.library.path=C:/BootOptimBench/prism/instances/BootOptimBench/natives -cp libs org.prismlauncher.EntryPoint'},
        [pscustomobject]@{ProcessId=2;CommandLine='java --gameDir "C:\BootOptimBench\prism\instances\BootOptimBench\.minecraft"'},
        [pscustomobject]@{ProcessId=3;CommandLine='java --gameDir c:/bootoptimbench/prism/instances/bootoptimbench/.minecraft'},
        [pscustomobject]@{ProcessId=4;CommandLine='java --gameDir C:/BootOptimBench/prism/instances/BootOptimBench-other/.minecraft'},
        [pscustomobject]@{ProcessId=5;CommandLine=$null},
        [pscustomobject]@{ProcessId=6;CommandLine='java --gameDir C:/Other/.minecraft'}
    )
    $found=@(Target-Java 'C:\BootOptimBench\prism\instances\BootOptimBench\.minecraft' 'C:\BootOptimBench\prism\instances\BootOptimBench')
    if(($found.ProcessId -join ',') -ne '1,2,3'){throw "$scriptName wrong identities: $($found.ProcessId -join ',')"}
    "PASS ${scriptName}: forward/backslash/case, Prism natives, sibling and missing-path cases"
}
