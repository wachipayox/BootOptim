<#
.SYNOPSIS
Captures a non-sensitive Prism UI snapshot from the interactive benchmark session.

.DESCRIPTION
This is a setup diagnostic, not part of a timed run. It enumerates only Prism
window/control names, types, enabled state, and automation IDs from the same
logged-in Windows session in which the launcher is running. It deliberately
does not read text fields, account records, tokens, or game files.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-ui-state.json'
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

$windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.Condition]::TrueCondition
)
$capturedWindows = @()
foreach ($window in $windows) {
    if ($prismProcessIds -notcontains $window.Current.ProcessId) {
        continue
    }

    $controls = @()
    foreach ($control in $window.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )) {
        $controls += [ordered]@{
            name = $control.Current.Name
            automationId = $control.Current.AutomationId
            controlType = $control.Current.ControlType.ProgrammaticName
            enabled = $control.Current.IsEnabled
            bounds = [ordered]@{
                left = $control.Current.BoundingRectangle.Left
                top = $control.Current.BoundingRectangle.Top
                width = $control.Current.BoundingRectangle.Width
                height = $control.Current.BoundingRectangle.Height
            }
        }
    }
    $capturedWindows += [ordered]@{
        name = $window.Current.Name
        automationId = $window.Current.AutomationId
        controlType = $window.Current.ControlType.ProgrammaticName
        enabled = $window.Current.IsEnabled
        controls = $controls
    }
}

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    prismProcessIds = $prismProcessIds
    windows = $capturedWindows
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $StatePath -Encoding utf8
