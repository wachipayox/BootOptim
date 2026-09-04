<#
.SYNOPSIS
Initializes Prism portable without its first-run graphical wizard.

.DESCRIPTION
The benchmark launcher owns no user credentials. Its only account record is a
local, synthetic offline identity which lets Prism complete initial validation;
benchmark jobs still launch explicitly with `--offline`. This prevents an
unattended task from displaying a login/setup dialog in the laptop's session.
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$PrismRoot = 'C:\BootOptimBench\prism',

    [ValidateNotNullOrEmpty()]
    [string]$JavaPath = 'C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$launcherPath = Join-Path $PrismRoot 'prismlauncher.exe'
$configPath = Join-Path $PrismRoot 'prismlauncher.cfg'
$accountsPath = Join-Path $PrismRoot 'accounts.json'
if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
    throw "Prism portable executable is missing: '$launcherPath'."
}
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw "The inspected Java runtime is missing: '$JavaPath'."
}
foreach ($path in @($configPath, $accountsPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing Prism state: '$path'."
    }
}

$prismJavaPath = $JavaPath -replace '\\', '/'
$configuration = @(
    '[General]'
    'Language=en'
    'IconTheme=pe_colored'
    'ApplicationTheme=bright'
    'IgnoreJavaWizard=true'
    'AutomaticJavaDownload=false'
    'AutomaticJavaSwitch=false'
    'UserAskedAboutAutomaticJavaDownload=true'
    "JavaPath=$prismJavaPath"
    # The reference pack deliberately uses Java 25 even though 1.21.1 metadata
    # advertises 21; Prism's guard must not change the benchmark JVM.
    'IgnoreJavaCompatibility=true'
    'ShowConsole=false'
    'AutoCloseConsole=true'
)

# This is deliberately not a Microsoft account, token, or copy of a user
# profile. Prism's v3 account parser requires a profile-shaped record; no
# gameplay or network identity is derived from it because the job always uses
# `--offline BootOptimBench`.
$offlineAccount = [ordered]@{
    formatVersion = 3
    accounts = @(
        [ordered]@{
            type = 'Offline'
            ygg = [ordered]@{
                token = '0'
                extra = [ordered]@{
                    userName = 'BootOptimBench'
                    clientToken = '00000000000000000000000000000001'
                }
            }
            profile = [ordered]@{
                id = '00000000000000000000000000000001'
                name = 'BootOptimBench'
                skin = [ordered]@{ id = ''; url = ''; variant = '' }
                capes = @()
            }
            entitlement = [ordered]@{
                ownsMinecraft = $true
                canPlayMinecraft = $true
            }
            active = $true
        }
    )
}

try {
    Set-Content -LiteralPath $configPath -Value $configuration -Encoding utf8
    $offlineAccount | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $accountsPath -Encoding utf8
}
catch {
    Remove-Item -LiteralPath $configPath, $accountsPath -Force -ErrorAction SilentlyContinue
    throw
}

[ordered]@{
    config = $configPath
    accounts = $accountsPath
    offlineOnly = $true
    storedUserCredentials = $false
} | ConvertTo-Json -Compress
