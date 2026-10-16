# Builds the zipfs read-only agent with a JDK 25 and runs SelfTest twice:
# once without the agent (stock behaviour) and once with -javaagent (agent behaviour).
# Usage: ./run-selftest.ps1 -Java <JDK 25 home>   (defaults to $env:JAVA_HOME)
# Touches only ./build and temporary folders under ./build. Never touches a game or launcher folder.
param([string]$Java = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if (-not $Java) { throw 'Pass -Java <JDK 25 home> or set JAVA_HOME.' }

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here 'build'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $work 'agent'), (Join-Path $work 'test') | Out-Null

$javac = Join-Path $Java 'bin/javac.exe'
$jar = Join-Path $Java 'bin/jar.exe'
$javaExe = Join-Path $Java 'bin/java.exe'

$agentSources = Get-ChildItem -Path (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 25 -Xlint:all -d (Join-Path $work 'agent') $agentSources
if ($LASTEXITCODE -ne 0) { throw 'agent compile failed' }

$testSources = Get-ChildItem -Path (Join-Path $here 'selftest') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 25 -cp (Join-Path $work 'agent') -d (Join-Path $work 'test') $testSources
if ($LASTEXITCODE -ne 0) { throw 'self-test compile failed' }

$agentJar = Join-Path $work 'zipfs-readonly-agent.jar'
& $jar --create --file $agentJar --manifest (Join-Path $here 'manifest.txt') -C (Join-Path $work 'agent') .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

$cp = (Join-Path $work 'test') + ';' + (Join-Path $work 'agent')
$main = 'dev.wachipayox.bootoptim.zipfsagent.SelfTest'

& $javaExe -cp $cp $main (Join-Path $work 'root-stock') false
if ($LASTEXITCODE -ne 0) { throw 'stock self-test failed' }

& $javaExe ("-javaagent:" + $agentJar) -cp $cp $main (Join-Path $work 'root-agent') true
if ($LASTEXITCODE -ne 0) { throw 'agent self-test failed' }

Write-Output 'ZIPFS-READONLY-AGENT SELFTEST OK'
