<#
.SYNOPSIS
Dismisses Prism's first-run account page in the logged-in benchmark session.

.DESCRIPTION
Prism 11 deliberately excludes offline identities from its "owns Minecraft"
setup check even when its command line is explicitly `--offline`. This helper
does not add, alter, or read a Microsoft account. It finds only a button named
Finish (or Finalizar) belonging to a Prism process in its own interactive
Windows session, invokes it once, and writes a small result record.
#>
[CmdletBinding()]
param(
    [ValidateRange(0, 30)]
    [int]$InitialDelaySeconds = 4,

    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-setup-dismiss.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Start-Sleep -Seconds $InitialDelaySeconds
Add-Type -AssemblyName UIAutomationClient

$currentSession = (Get-Process -Id $PID).SessionId
$prismProcessIds = @(
    Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue |
        Where-Object { $_.SessionId -eq $currentSession } |
        Select-Object -ExpandProperty Id
)
if ($prismProcessIds.Count -eq 0) {
    throw "No Prism process exists in interactive session $currentSession."
}

$windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.Condition]::TrueCondition
)
$buttonCondition = [System.Windows.Automation.PropertyCondition]::new(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Button
)
$finishButtons = @()
foreach ($window in $windows) {
    if ($prismProcessIds -notcontains $window.Current.ProcessId) {
        continue
    }
    foreach ($button in $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition)) {
        if ($button.Current.Name -in @('Finish', 'Finalizar')) {
            $finishButtons += $button
        }
    }
}
if ($finishButtons.Count -ne 1) {
    throw "Expected exactly one Prism setup Finish button; found $($finishButtons.Count)."
}

$pattern = $null
if (-not $finishButtons[0].TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
    throw 'Prism setup Finish button does not expose InvokePattern.'
}
$pattern.Invoke()

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    prismProcessIds = $prismProcessIds
    invokedButton = $finishButtons[0].Current.Name
    usedMicrosoftAccount = $false
} | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
