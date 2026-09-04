<#
.SYNOPSIS
Records whether a scheduled task is running in the logged-in desktop session.

.DESCRIPTION
This probe does not start Prism or Minecraft. It is the safety gate before an
interactive scheduled task may launch graphical benchmark work.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$StatePath = 'C:\BootOptimBench\state\interactive-session-probe.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$self = Get-Process -Id $PID
[ordered]@{
    schemaVersion = 1
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    sessionId = $self.SessionId
    userInteractive = [Environment]::UserInteractive
    processId = $PID
} | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
