[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
foreach($name in @('remote_laptop_transaction.ps1','remote_laptop_interactive_run.ps1')) {
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw "Parse failed: $name"}
    $helper=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Replace-AtomicWithSharingRetry'},$true)
    if(-not $helper){throw "Missing atomic helper: $name"}
    Invoke-Expression $helper.Extent.Text
    $directory=Join-Path ([IO.Path]::GetTempPath()) ('bootoptim-state-test-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    $destination=Join-Path $directory 'state.json'; $temp=Join-Path $directory 'state.tmp'; $ready=Join-Path $directory 'ready'
    $reader=$null
    try {
        [IO.File]::WriteAllText($destination,'old'); [IO.File]::WriteAllText($temp,'new')
        $reader=Start-Job -ArgumentList $destination,$ready -ScriptBlock {
            param($file,$ready)
            $handle=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
            try { [IO.File]::WriteAllText($ready,'ready'); Start-Sleep -Milliseconds 1500 }
            finally { $handle.Dispose() }
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(20)
        while(-not(Test-Path -LiteralPath $ready)) {
            if([DateTime]::UtcNow -gt $deadline){throw 'Reader failed to acquire the handle'}
            Start-Sleep -Milliseconds 50
        }
        if([IO.File]::ReadAllText($destination) -ne 'old'){throw 'Old state changed before publication'}
        Replace-AtomicWithSharingRetry $temp $destination
        if([IO.File]::ReadAllText($destination) -ne 'new' -or (Test-Path -LiteralPath $temp)){throw 'Atomic publication failed'}
        $reader | Wait-Job | Receive-Job
        Remove-Item -LiteralPath $destination
        [IO.File]::WriteAllText($temp,'unused')
        $threw=$false
        try { Replace-AtomicWithSharingRetry $temp $destination }
        catch { $threw=$true; if($_.Exception.GetBaseException() -isnot [IO.IOException]){throw} }
        if(-not $threw){throw 'A non-sharing error was suppressed'}
        if([IO.File]::ReadAllText($temp) -ne 'unused'){throw 'Failed publication changed temporary bytes'}
        Write-Output "$name PASS: real read-sharing lock recovered; unrelated IO error propagated"
    } finally {
        if($reader){$reader | Stop-Job; $reader | Remove-Job}
        foreach($file in @($destination,$temp,$ready)) { if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file} }
        Remove-Item -LiteralPath $directory
    }
}
