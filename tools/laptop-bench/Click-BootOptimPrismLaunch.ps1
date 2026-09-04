<#
.SYNOPSIS
Uses a guarded pointer click when Prism's Qt accessibility Invoke action is inert.

.DESCRIPTION
This setup-only fallback obtains the BootOptimBench console and its enabled
Launch button through UI Automation first. It then sends one pointer click to
the center of that exact button. The script refuses to act unless the window
and button identifiers are the expected Prism 11.1.0 values.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-launch-click.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BootOptimPointer {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
}
'@

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
$targetWindow = $null
$launchButton = $null
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
            $control.Current.IsEnabled -and
            $control.Current.BoundingRectangle.Width -gt 0 -and
            $control.Current.BoundingRectangle.Height -gt 0) {
            if ($launchButton) {
                throw 'Found more than one enabled BootOptimBench Launch button.'
            }
            $targetWindow = $window
            $launchButton = $control
        }
    }
}
if (-not $launchButton) {
    throw 'Expected one enabled BootOptimBench Launch button.'
}

$bounds = $launchButton.Current.BoundingRectangle
$x = [int][Math]::Round($bounds.Left + ($bounds.Width / 2))
$y = [int][Math]::Round($bounds.Top + ($bounds.Height / 2))
if (-not [BootOptimPointer]::SetForegroundWindow($targetWindow.Current.NativeWindowHandle)) {
    throw 'Could not foreground the identified BootOptimBench Prism window.'
}
if (-not [BootOptimPointer]::SetCursorPos($x, $y)) {
    throw 'Could not position the pointer on the identified Launch button.'
}
Start-Sleep -Milliseconds 100
[BootOptimPointer]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
[BootOptimPointer]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    prismProcessIds = $prismProcessIds
    clickedButton = 'Launch'
    center = @{ x = $x; y = $y }
    timedBenchmark = $false
} | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
