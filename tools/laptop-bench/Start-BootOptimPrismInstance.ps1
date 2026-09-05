<#
.SYNOPSIS
Starts the prepared BootOptim Prism instance from its interactive console window.

.DESCRIPTION
Prism may open its instance console while it resolves first-run metadata instead
of launching immediately. This setup-only helper invokes one uniquely identified
enabled Launch button for BootOptimBench in the logged-in interactive session.
It does not interact with the user's live launcher, alter accounts, or form part
of a timed benchmark.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-instance-start.json'
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
$launchButtons = @()
foreach ($window in $windows) {
    if ($prismProcessIds -notcontains $window.Current.ProcessId -or
        $window.Current.AutomationId -ne 'Application.InstanceWindow' -or
        $window.Current.Name -ne 'Console window for BootOptimBench - Prism Launcher 11.1.0') {
        continue
    }
    foreach ($control in $window.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )) {
        if ($control.Current.Name -eq 'Launch' -and
            $control.Current.AutomationId -eq 'Application.InstanceWindow.PageContainer.QToolButton' -and
            $control.Current.IsEnabled) {
            $launchButtons += $control
        }
    }
}
if ($launchButtons.Count -ne 1) {
    throw "Expected exactly one enabled BootOptimBench Launch button; found $($launchButtons.Count)."
}

$pattern = $null
if (-not $launchButtons[0].TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
    throw 'BootOptimBench Launch button does not expose InvokePattern.'
}
$pattern.Invoke()

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    prismProcessIds = $prismProcessIds
    invokedButton = 'Launch'
    timedBenchmark = $false
} | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
