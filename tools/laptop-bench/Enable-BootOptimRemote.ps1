<#
.SYNOPSIS
Enrols a Windows laptop as a LAN-only BootOptim benchmark host.

.DESCRIPTION
This is the only script that must be run locally on the laptop. It installs the
Windows OpenSSH Server feature when necessary, authorizes one controller public
key for the current interactive user, and replaces the broad OpenSSH firewall
rule with a rule that accepts TCP/22 only from the controller's IPv4/IPv6
address. It never enables RDP, starts a game, changes Java, or modifies a
Minecraft instance.

Run this once from an elevated PowerShell *in the Windows account that will
later own the graphical Minecraft session*. The machine must remain logged in
while benchmark tasks run; Minecraft cannot be benchmarked from Windows session
0.
#>
[CmdletBinding(DefaultParameterSetName = 'InlineKey')]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ControllerAddress,

    [Parameter(Mandatory, ParameterSetName = 'InlineKey')]
    [ValidateNotNullOrEmpty()]
    [string]$ControllerPublicKey,

    [Parameter(Mandatory, ParameterSetName = 'KeyUrl')]
    [ValidateNotNullOrEmpty()]
    [string]$ControllerPublicKeyUrl,

    [ValidateNotNullOrEmpty()]
    [string]$BenchRoot = 'C:\BootOptimBench'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this script from an elevated PowerShell window (Run as administrator).'
    }
}

function Assert-ControllerAddress([string]$Address) {
    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($Address, [ref]$parsed)) {
        throw "ControllerAddress must be a single IPv4 or IPv6 address, not '$Address'."
    }
    return $parsed.IPAddressToString
}

function Get-ControllerPublicKey {
    if ($PSCmdlet.ParameterSetName -eq 'KeyUrl') {
        if ($ControllerPublicKeyUrl -notmatch '^https?://') {
            throw 'ControllerPublicKeyUrl must use http:// or https://.'
        }
        $downloaded = Invoke-WebRequest -UseBasicParsing -Uri $ControllerPublicKeyUrl
        $key = [string]$downloaded.Content
    }
    else {
        $key = $ControllerPublicKey
    }

    $key = $key.Trim()
    if ($key -notmatch '^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\.com|ssh-rsa)\s+[A-Za-z0-9+/=]+(?:\s+[^\r\n]+)?$') {
        throw 'The supplied controller public key is not a supported one-line OpenSSH public key.'
    }
    return $key
}

function Ensure-OpenSshServer {
    $capabilityName = 'OpenSSH.Server~~~~0.0.1.0'
    $capability = Get-WindowsCapability -Online -Name $capabilityName
    if ($capability.State -ne 'Installed') {
        Write-Host 'Installing the Windows OpenSSH Server optional feature...'
        Add-WindowsCapability -Online -Name $capabilityName | Out-Null
    }

    Set-Service -Name sshd -StartupType Automatic
    Start-Service -Name sshd
}

function Add-AuthorizedKey([string]$Key) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $currentUser = $identity.Name

    # The stock Windows sshd_config has a Match Group administrators clause
    # which changes this location for administrator accounts. Honouring it is
    # necessary for key login to work on the common one-account benchmark PCs.
    if ($isAdministrator) {
        $sshDirectory = Join-Path $env:ProgramData 'ssh'
        $authorizedKeys = Join-Path $sshDirectory 'administrators_authorized_keys'
    }
    else {
        $profileDirectory = [Environment]::GetFolderPath('UserProfile')
        if ([string]::IsNullOrWhiteSpace($profileDirectory)) {
            throw 'Windows did not return a profile directory for the current user.'
        }
        $sshDirectory = Join-Path $profileDirectory '.ssh'
        $authorizedKeys = Join-Path $sshDirectory 'authorized_keys'
    }

    New-Item -ItemType Directory -Path $sshDirectory -Force | Out-Null
    New-Item -ItemType File -Path $authorizedKeys -Force | Out-Null

    $existing = @(Get-Content -LiteralPath $authorizedKeys -ErrorAction SilentlyContinue)
    if ($existing -notcontains $Key) {
        Add-Content -LiteralPath $authorizedKeys -Value $Key -Encoding ascii
    }

    # OpenSSH on Windows rejects user key files with overly broad ACLs. The
    # administrator key file follows Windows' documented Administrators/System
    # ACL; regular user accounts receive an owner/System-only ACL.
    if ($isAdministrator) {
        & icacls.exe $authorizedKeys /inheritance:r /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F' | Out-Null
    }
    else {
        & icacls.exe $sshDirectory /inheritance:r /grant:r "${currentUser}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
        & icacls.exe $authorizedKeys /inheritance:r /grant:r "${currentUser}:F" 'SYSTEM:F' | Out-Null
    }

    return $authorizedKeys
}

function Set-KeyOnlySshAuthentication([string]$Root) {
    $configPath = Join-Path $env:ProgramData 'ssh\sshd_config'
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "OpenSSH configuration was not created at '$configPath'."
    }

    $backupPath = Join-Path $Root 'state\sshd_config.before-bootoptim'
    Copy-Item -LiteralPath $configPath -Destination $backupPath -Force
    $lines = @(Get-Content -LiteralPath $configPath)
    $matchIndex = $lines.Count
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match '^\s*Match\s+') {
            $matchIndex = $index
            break
        }
    }

    $globalLines = if ($matchIndex -gt 0) {
        @($lines[0..($matchIndex - 1)] | Where-Object {
                $_ -notmatch '^\s*#?\s*(PasswordAuthentication|PubkeyAuthentication)\s+'
            })
    }
    else {
        @()
    }
    $matchLines = if ($matchIndex -lt $lines.Count) { @($lines[$matchIndex..($lines.Count - 1)]) } else { @() }
    $newLines = @($globalLines + 'PubkeyAuthentication yes' + 'PasswordAuthentication no' + $matchLines)

    try {
        Set-Content -LiteralPath $configPath -Value $newLines -Encoding ascii
        $sshdPath = Join-Path $env:WINDIR 'System32\OpenSSH\sshd.exe'
        & $sshdPath -t
        if ($LASTEXITCODE -ne 0) {
            throw "sshd configuration check failed with exit $LASTEXITCODE."
        }
        Restart-Service -Name sshd
    }
    catch {
        Copy-Item -LiteralPath $backupPath -Destination $configPath -Force
        Restart-Service -Name sshd -ErrorAction SilentlyContinue
        throw
    }
}

function Set-RestrictedSshFirewall([string]$Address) {
    $broadRule = Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
    if ($null -ne $broadRule) {
        Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' | Out-Null
    }

    $ruleName = 'BootOptimBench-OpenSSH-LAN'
    Remove-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    New-NetFirewallRule `
        -Name $ruleName `
        -DisplayName 'BootOptim benchmark controller SSH' `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort 22 `
        -RemoteAddress $Address | Out-Null
}

function Get-LanAddresses {
    return @(
        Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -ne '127.0.0.1' -and
                $_.PrefixOrigin -ne 'WellKnown' -and
                $_.AddressState -eq 'Preferred'
            } |
            Select-Object -ExpandProperty IPAddress
    )
}

Assert-Administrator
$controllerIp = Assert-ControllerAddress $ControllerAddress
$publicKey = Get-ControllerPublicKey

New-Item -ItemType Directory -Path $BenchRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $BenchRoot 'incoming') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $BenchRoot 'results') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $BenchRoot 'state') -Force | Out-Null

Ensure-OpenSshServer
$authorizedKeys = Add-AuthorizedKey $publicKey
Set-KeyOnlySshAuthentication $BenchRoot
Set-RestrictedSshFirewall $controllerIp

$state = [ordered]@{
    schemaVersion = 1
    configuredAt = (Get-Date).ToUniversalTime().ToString('o')
    computerName = $env:COMPUTERNAME
    benchUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    controllerAddress = $controllerIp
    benchRoot = $BenchRoot
    authorizedKeys = $authorizedKeys
    lanAddresses = @(Get-LanAddresses)
}
$statePath = Join-Path $BenchRoot 'state\remote-access.json'
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8

Write-Host ''
Write-Host 'BOOTOPTIM_REMOTE_READY'
Write-Host "computer_name=$($state.computerName)"
Write-Host "bench_user=$($state.benchUser)"
Write-Host "lan_addresses=$($state.lanAddresses -join ',')"
Write-Host "controller_address=$controllerIp"
Write-Host "state_path=$statePath"
Write-Host ''
Write-Host 'Do not open RDP while a benchmark is running. Keep this Windows session logged in, plugged in, and connected to the LAN.'
