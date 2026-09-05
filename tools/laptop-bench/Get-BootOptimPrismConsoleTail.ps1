<#
.SYNOPSIS
Captures the tail of the prepared instance's Prism console for setup diagnosis.

.DESCRIPTION
This runs only in the interactive benchmark session and reads the text shown by
the BootOptimBench Prism console. Potential authentication values and UUIDs are
redacted before the short tail is written to state. It is not a timed run.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-console-tail.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient

$currentSession = (Get-Process -Id $PID).SessionId
$prismProcessIds = @(
    Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $currentSession } |
        Select-Object -ExpandProperty Id
)
$console = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.Condition]::TrueCondition
) | Where-Object {
    $_.Current.ProcessId -in $prismProcessIds -and
    $_.Current.AutomationId -eq 'Application.InstanceWindow' -and
    $_.Current.Name -eq 'Console window for BootOptimBench - Prism Launcher 11.1.0'
} | Select-Object -First 1
if (-not $console) {
    throw 'BootOptimBench Prism console window was not found.'
}

$edits = @($console.FindAll(
    [System.Windows.Automation.TreeScope]::Descendants,
    [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Edit
    )
))
$text = ''
foreach ($edit in $edits) {
    $pattern = $null
    if ($edit.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern, [ref]$pattern)) {
        $candidate = $pattern.DocumentRange.GetText(-1)
        if ($candidate.Length -gt $text.Length) {
            $text = $candidate
        }
    }
}
if ([string]::IsNullOrWhiteSpace($text)) {
    throw 'The Prism console did not expose readable text.'
}

$redacted = $text `
    -replace '(?i)(--accessToken\s+)(\S+)', '$1[redacted]' `
    -replace '(?i)(--clientId\s+)(\S+)', '$1[redacted]' `
    -replace '(?i)(--uuid\s+)(\S+)', '$1[redacted]'
$lines = $redacted -split "`r?`n" | Where-Object { $_.Length -gt 0 }
$start = [Math]::Max(0, $lines.Count - 80)

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    tail = @($lines[$start..($lines.Count - 1)])
    timedBenchmark = $false
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $StatePath -Encoding utf8
