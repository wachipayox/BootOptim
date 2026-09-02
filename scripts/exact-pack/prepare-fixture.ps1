param(
    [Parameter(Mandatory = $true)][string]$ZipPath,
    [Parameter(Mandatory = $true)][string]$ExpectedSha256,
    [Parameter(Mandatory = $true)][string]$ExtractDirectory,
    [string]$GithubEnv = $env:GITHUB_ENV
)

$ErrorActionPreference = 'Stop'

$zip = (Resolve-Path $ZipPath).Path
$actualHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedHash = $ExpectedSha256.ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "Exact-pack SHA-256 mismatch. expected=$expectedHash actual=$actualHash"
}

if (Test-Path -LiteralPath $ExtractDirectory) {
    Remove-Item -LiteralPath $ExtractDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $ExtractDirectory -Force | Out-Null

& tar.exe -xf $zip -C $ExtractDirectory
if ($LASTEXITCODE -ne 0) {
    throw "tar.exe failed to extract exact-pack fixture (exit $LASTEXITCODE)"
}

$extractRoot = (Resolve-Path $ExtractDirectory).Path
$candidates = @()
if (Test-Path -LiteralPath (Join-Path $extractRoot 'mods')) {
    $candidates += Get-Item -LiteralPath $extractRoot
}
$candidates += @(Get-ChildItem -LiteralPath $extractRoot -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'mods')
})
$candidates = @($candidates | Sort-Object FullName -Unique)
if ($candidates.Count -ne 1) {
    $candidateText = ($candidates | ForEach-Object FullName) -join ', '
    throw "Could not identify one exact-pack root containing mods/. candidates=[$candidateText]"
}

$packRoot = $candidates[0].FullName
$options = Join-Path $packRoot 'options.txt'
if (-not (Test-Path -LiteralPath $options)) {
    throw 'Exact-pack fixture must contain options.txt so enabled resource packs are reproducible.'
}

$mcefLibraries = Join-Path $packRoot 'mods/mcef-libraries'
if (-not (Test-Path -LiteralPath $mcefLibraries)) {
    throw 'Exact-pack fixture is missing mods/mcef-libraries. The hosted baseline requires preseeded MCEF native libraries.'
}

$mcefCache = Join-Path $packRoot 'mods/mcef-cache'
if (Test-Path -LiteralPath $mcefCache) {
    throw 'Exact-pack fixture contains mods/mcef-cache. Browser cache is deliberately excluded from the cold reproducible baseline.'
}

$bootOptimJars = @(Get-ChildItem -LiteralPath (Join-Path $packRoot 'mods') -File -Filter '*.jar' | Where-Object {
    $name = $_.Name.ToLowerInvariant()
    $name.Contains('bootoptim') -or $name.Contains('boot_optim')
})
if ($bootOptimJars.Count -gt 0) {
    throw "Exact-pack fixture must not contain BootOptim; the PR build is injected by ModDevGradle. Found: $($bootOptimJars.Name -join ', ')"
}

# GitHub's hosted Windows Server runner has no usable graphics adapter/interactive desktop for
# Drippy's ImmediateWindowProvider. Keep the Drippy mod and all of its normal resources/config,
# but disable only FML's pre-Minecraft early window in this ephemeral extracted copy. FML treats
# earlyWindowControl=false as a first-class opt-out and continues without loading any provider.
$fmlConfig = Join-Path $packRoot 'config/fml.toml'
if (-not (Test-Path -LiteralPath $fmlConfig)) {
    throw 'Exact-pack fixture is missing config/fml.toml; cannot apply the documented hosted early-window exception.'
}
$fmlText = Get-Content -LiteralPath $fmlConfig -Raw
$earlyWindowPattern = '(?m)^\s*earlyWindowControl\s*=\s*(true|false)\s*$'
if ([regex]::IsMatch($fmlText, $earlyWindowPattern)) {
    $fmlText = [regex]::Replace($fmlText, $earlyWindowPattern, 'earlyWindowControl = false', 1)
} else {
    $fmlText = $fmlText.TrimEnd() + "`r`n`r`n# BootOptim hosted exact-pack CI exception: no interactive early window.`r`nearlyWindowControl = false`r`n"
}
Set-Content -LiteralPath $fmlConfig -Value $fmlText -Encoding utf8
Write-Host 'Exact-pack hosted exception applied: config/fml.toml earlyWindowControl=false (Drippy mod retained).'

$modJarCount = @(Get-ChildItem -LiteralPath (Join-Path $packRoot 'mods') -File -Filter '*.jar').Count
Write-Host "Exact-pack fixture verified: sha256=$actualHash mod_jars=$modJarCount root=$packRoot"

if ($GithubEnv) {
    Add-Content -LiteralPath $GithubEnv -Value "BOOTOPTIM_PACK_DIR=$packRoot"
}

Write-Output $packRoot
