<#
.SYNOPSIS
Starts BootOptimBench through Prism's documented Launch Offline shortcut.

.DESCRIPTION
Runs only in the benchmark's logged-in interactive session. It foregrounds the
uniquely identified BootOptimBench console, confirms its Launch control is
enabled, and sends Prism's Ctrl+Shift+O shortcut. This excludes account refresh
and external authentication from the game-start measurement.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\prism-offline-launch.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BootOptimKeys {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extraInfo);
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
$launchButtons = @()
foreach ($window in $windows) {
    if ($prismProcessIds -notcontains $window.Current.ProcessId -or
        $window.Current.AutomationId -ne 'Application.InstanceWindow' -or
        $window.Current.Name -ne 'Console window for BootOptimBench - Prism Launcher 11.1.0') {
        continue
    }
    $targetWindow = $window
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
if (-not $targetWindow -or $launchButtons.Count -ne 1) {
    throw "Expected one enabled BootOptimBench Launch button; found $($launchButtons.Count)."
}
if (-not [BootOptimKeys]::SetForegroundWindow($targetWindow.Current.NativeWindowHandle)) {
    throw 'Could not foreground the identified BootOptimBench Prism console.'
}
Start-Sleep -Milliseconds 100
# Virtual-key codes: Ctrl (0x11), Shift (0x10), O (0x4F), KEYEVENTF_KEYUP (0x2).
[BootOptimKeys]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero)
[BootOptimKeys]::keybd_event(0x10, 0, 0, [UIntPtr]::Zero)
[BootOptimKeys]::keybd_event(0x4F, 0, 0, [UIntPtr]::Zero)
[BootOptimKeys]::keybd_event(0x4F, 0, 0x2, [UIntPtr]::Zero)
[BootOptimKeys]::keybd_event(0x10, 0, 0x2, [UIntPtr]::Zero)
[BootOptimKeys]::keybd_event(0x11, 0, 0x2, [UIntPtr]::Zero)

[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    sessionId = $currentSession
    prismProcessIds = $prismProcessIds
    shortcut = 'Ctrl+Shift+O'
    timedBenchmark = $false
} | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
