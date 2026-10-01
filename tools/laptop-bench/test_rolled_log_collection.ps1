$ErrorActionPreference='Stop'
$scriptPath=Join-Path $PSScriptRoot 'remote_express_sweep.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($scriptPath,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Controller does not parse'}
$fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Collect-RunLog'},$true)
Invoke-Expression $fn.Extent.Text
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('bootoptim-rolled-'+[Guid]::NewGuid().ToString('N'))
$logs=Join-Path $fixture 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
function Write-Gzip($name,$text,$time) {
    $path=Join-Path $logs $name
    $file=[IO.File]::Create($path)
    $zip=[IO.Compression.GZipStream]::new($file,[IO.Compression.CompressionMode]::Compress)
    $writer=[IO.StreamWriter]::new($zip,[Text.UTF8Encoding]::new($false))
    try{$writer.Write($text)}finally{$writer.Dispose();$zip.Dispose();$file.Dispose()}
    [IO.File]::SetLastWriteTimeUtc($path,$time)
}
$origin=[DateTime]::UtcNow.AddMinutes(-2)
Write-Gzip '2026-09-30-1.log.gz' "first-process-marker`n" $origin.AddSeconds(1)
Write-Gzip '2026-10-01-1.log.gz' "second-process-marker`n" $origin.AddSeconds(2)
Write-Gzip 'debug-1.log.gz' "first-process-marker`n" $origin.AddSeconds(3)
Write-Gzip '2026-09-29-1.log.gz' "stale-process-marker`n" $origin.AddSeconds(-1)
[IO.File]::WriteAllText((Join-Path $logs 'latest.log'),"terminal-marker`n")
$dest=Join-Path $fixture 'merged.log'
Collect-RunLog $fixture $dest $origin.ToString('o')
$actual=[IO.File]::ReadAllText($dest)
if(($actual -split '\s+' | Where-Object {$_}) -join ',' -ne 'first-process-marker,second-process-marker,terminal-marker'){throw 'Rolled logs were missing, duplicated, reordered or stale'}
Write-Output "PASS rolled logs preserve order; debug/stale archives excluded; fixture $fixture"
